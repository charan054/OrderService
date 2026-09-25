package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.CartRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService {
    @Autowired
    private CartRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    ProductClient productClient;
    public Cart order(Cart cart)
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
        cart.setTotalPrice(price);
        Cart saved=orderRepository.save(cart);
        for(OrderItem orderItem : saved.getOrderItems())
        {
            orderItem.setOrderId(saved.getOrderId());
            productClient.updateProductStock(orderItem.getProductId(),-orderItem.getProductQuantity());
        }
        return orderRepository.save(saved);
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
                    productClient.updateProductStock(orderItems.get(i).getProductId(),+orderItems.get(i).getProductQuantity());
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
