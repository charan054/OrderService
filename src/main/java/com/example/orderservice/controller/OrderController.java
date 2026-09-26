package com.example.orderservice.controller;

import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.service.OrderService;
import jakarta.transaction.Transactional;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cart")
public class OrderController {
    @Autowired
    private OrderService orderService;
    // Authorization must be the buyer's OWN PhonepayService session token ("Bearer <token>") - that is who gets
    // charged. Idempotency-Key is optional: send the same value on a retry of the same checkout attempt (e.g.
    // after a lost response) to avoid paying twice; a different value, or none, is always a brand new payment.
    @PostMapping("/add")
    public Cart addOrder(@RequestBody Cart cart,
                         @RequestHeader("Authorization") String authorization,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey){
        return orderService.order(cart, authorization, idempotencyKey);
    }
    // Authorization must be the buyer's OWN PhonepayService session token - PhonepayService only refunds a
    // payment back to the person who made it, so this can never cancel (and refund) someone else's order.
    @PostMapping("/{orderId}/cancel")
    public Cart cancelOrder(@PathVariable long orderId,
                            @RequestHeader("Authorization") String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey){
        return orderService.cancel(orderId, authorization, idempotencyKey);
    }
    // Same buyer-token requirement as cancel above, but only usable once an order has reached DELIVERED - cancel
    // and return are mutually exclusive by status, never overlapping windows.
    @PostMapping("/{orderId}/return")
    public Cart returnOrder(@PathVariable long orderId,
                            @RequestParam String reason,
                            @RequestHeader("Authorization") String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey){
        return orderService.returnOrder(orderId, authorization, idempotencyKey, reason);
    }
    // Operational actions (warehouse/ops moving an order along), not something the buyer's own token gates -
    // authenticated the same way as every other trusted-caller endpoint here, via X-Service-Key.
    @PostMapping("/{orderId}/ship")
    public Cart shipOrder(@PathVariable long orderId){
        return orderService.ship(orderId);
    }
    @PostMapping("/{orderId}/deliver")
    public Cart deliverOrder(@PathVariable long orderId){
        return orderService.deliver(orderId);
    }
    // Public, same self-service trust level as GET /cart/byphno - the timeline is just a history of the same
    // status field that /cart/byphno already exposes, one row per transition instead of a single current value.
    @GetMapping("/{orderId}/tracking")
    public List<TrackingEvent> getTracking(@PathVariable long orderId){
        return orderService.getTracking(orderId);
    }
    // Same public trust level as tracking above - the audit trail of customer notifications OrderKafkaConsumer
    // has dispatched for this order so far.
    @GetMapping("/{orderId}/notifications")
    public List<NotificationLog> getNotifications(@PathVariable long orderId){
        return orderService.getNotifications(orderId);
    }
    @GetMapping("/display")
    public List<Product> findAll(){
        return orderService.getProducts();
    }
    // Public, same catalog-browsing trust level as /cart/display - a ranked list, not any one customer's data.
    @GetMapping("/frequentlyboughttogether")
    public List<FrequentlyBoughtTogether> getFrequentlyBoughtTogether(@RequestParam int productId,
                                                                       @RequestParam(required = false) Integer limit) {
        return orderService.getFrequentlyBoughtTogether(productId, limit);
    }
    @GetMapping("/byphno")
    public List<Cart> findByPhno(long phno){
        return orderService.ordersOfPhno(phno);
    }
    @GetMapping("/all")
    public List<Cart> getAll(){
        return orderService.findAll();
    }
    @Transactional
    @DeleteMapping("/deleteproduct")
    public List<Cart> deleteProduct(@RequestParam long phno,@RequestParam long productId){
        return orderService.deleteProduct(phno,productId);
    }
}
