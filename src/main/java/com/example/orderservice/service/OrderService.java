package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.CartRepository;
import feign.FeignException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private CartRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    ProductClient productClient;
    @Autowired
    PhonepeClient phonepeClient;
    @Autowired
    private OrderKafkaProducer orderKafkaProducer;
    @Value("${internal.service.api-key}")
    private String serviceApiKey;
    public Cart order(Cart cart, String authorization, String idempotencyKey)
    {
        long phno=cart.getCustomerPhno();
        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new ProductException("Invalid mobile number");
        }
        // Validate every item and compute the price WITHOUT touching stock yet, so a later item failing
        // (not found, insufficient stock) can never leave an earlier item's stock decremented with no order to show for it.
        double price=0;
        for(OrderItem orderItem : cart.getOrderItems())
        {
            Product pro=productClient.getProductById(orderItem.getProductId());
            if(pro==null)
            {
                throw new ProductException("Product not found");
            }
            if(orderItem.getProductQuantity()>pro.getProductStock())
            {
                throw new ProductException("Product quantity exceeded");
            }
            price=price+(orderItem.getProductQuantity()*pro.getProductPrice());
        }
        // Charge the buyer BEFORE creating the order or touching stock: if PhonepayService refuses the payment
        // (insufficient funds, expired session, locked account, bank down, ...) nothing here should exist either.
        PaymentResponse payment = charge(authorization, price, idempotencyKey);
        cart.setTotalPrice(price);
        cart.setStatus(OrderStatus.PLACED);
        cart.setPaymentTransactionId(payment.transactionId());
        Cart saved=orderRepository.save(cart);
        for(OrderItem orderItem : saved.getOrderItems())
        {
            orderItem.setOrderId(saved.getOrderId());
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(),-orderItem.getProductQuantity());
        }
        Cart result = orderRepository.save(saved);
        sendNotification("Order placed successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Items: " + result.getOrderItems().size()
                + " Total: " + result.getTotalPrice());
        return result;
    }

    // Cancels an order that hasn't already been cancelled: refunds the buyer's own payment in full, and only on
    // a successful refund restores stock and marks the order CANCELLED - a declined/failed refund leaves the
    // order exactly as it was, the same fail-safe shape order() already uses for placing one.
    public Cart cancel(long orderId, String authorization, String idempotencyKey) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() == OrderStatus.CANCELLED) {
            throw new ProductException("This order is already cancelled");
        }
        if (cart.getPaymentTransactionId() == null) {
            throw new ProductException("This order cannot be cancelled");
        }

        refund(authorization, cart.getPaymentTransactionId(), idempotencyKey);

        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
        }
        cart.setStatus(OrderStatus.CANCELLED);
        Cart result = orderRepository.save(cart);
        sendNotification("Order cancelled successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Refunded: " + result.getTotalPrice());
        return result;
    }

    // Kafka is told only AFTER the save() above returns - neither order() nor cancel() wraps its DB writes in a
    // surrounding @Transactional, so by that point the write has already committed (each repository.save() is
    // its own auto-committed transaction). A broken notification channel must never turn a completed order/
    // cancellation into an error, so failures here are logged and swallowed, not propagated.
    private void sendNotification(String message) {
        try {
            orderKafkaProducer.sendMessage(message);
        } catch (RuntimeException e) {
            log.error("Kafka notification failed: {}", e.getMessage());
        }
    }

    // keep phone numbers out of the Kafka topic and its logs
    private static String mask(long phno) {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }

    private static final String COMPLETED = "COMPLETED";

    private PaymentResponse charge(String authorization, double price, String idempotencyKey) {
        BigDecimal amount = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        PaymentResponse payment;
        try {
            payment = phonepeClient.makePayment(authorization, new PaymentRequest(amount, "Order payment", idempotencyKey));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        // A fresh payment attempt only ever returns here once truly COMPLETED (anything else throws on
        // PhonepayService's side). But a retry that reuses an Idempotency-Key returns whatever status that
        // earlier attempt ended up with - including NEEDS_RECONCILIATION - with no exception at all, so a
        // 200 response alone is not proof the money actually moved; the status field has to be checked too.
        if (!COMPLETED.equals(payment.status())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY,
                    "Payment status is " + payment.status() + ", not completed. Reference: " + payment.transactionId());
        }
        return payment;
    }

    private void refund(String authorization, long paymentTransactionId, String idempotencyKey) {
        PaymentResponse refund;
        try {
            refund = phonepeClient.refund(authorization, paymentTransactionId, new RefundRequest(idempotencyKey));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        // Same reasoning as charge(): an Idempotency-Key retry can hand back a NEEDS_RECONCILIATION refund
        // without ever throwing, so stock must not be restored until the status itself confirms completion.
        if (!COMPLETED.equals(refund.status())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY,
                    "Refund status is " + refund.status() + ", not completed. Reference: " + refund.transactionId());
        }
    }
    public List<Cart>  findAll()
    {
        return orderRepository.findAll();
    }
    public List<Cart> ordersOfPhno(long phno)
    {
        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new ProductException("Invalid mobile number");
        }
        return orderRepository.findBycustomerPhno(phno);
    }
    public List<Product> getProducts()
    {
        return productClient.findAll();
    }
    @Transactional
    public List<Cart> deleteProduct(long phno, long productId) {
        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new ProductException("Invalid mobile number");
        }

        List<Cart> carts = orderRepository.findBycustomerPhno(phno);

        for (Cart cart : carts) {
            List<OrderItem> orderItems = cart.getOrderItems();
            double price = cart.getTotalPrice();
            for(int i=0;i<orderItems.size();i++)
            {
                if(orderItems.get(i).getProductId()==productId)
                {
                    long t=orderItems.get(i).getId();
                    Product pro=productClient.getProductById(orderItems.get(i).getProductId());
                    price=price-(orderItems.get(i).getProductQuantity()*pro.getProductPrice());
                    productClient.updateProductStock(serviceApiKey, orderItems.get(i).getProductId(),+orderItems.get(i).getProductQuantity());
                    orderItems.remove(i);
                    orderItemRepository.deleteById(t);
                    i--;
                }
            }

            cart.setTotalPrice(price);
            orderRepository.save(cart);
        }

        return carts;
    }
}
