package com.example.orderservice.client;

import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.OrderItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name="ProductService",url="http://localhost:8082")
public interface ProductClient {
    @GetMapping("/product/all")
    List<Product> findAll();
    @GetMapping("/product/byId")
    Product getProductById(@RequestParam int id);
    @PutMapping("/product/updateStock")
    public Product updateProductStock(@RequestParam Integer id,@RequestParam Integer stock);

}
