package com.example.orderservice.controller;

import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
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
    @GetMapping("/display")
    public List<Product> findAll(){
        return orderService.getProducts();
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
