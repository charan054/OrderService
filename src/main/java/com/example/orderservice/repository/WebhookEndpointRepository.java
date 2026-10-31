package com.example.orderservice.repository;

import com.example.orderservice.entity.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findAllByOrderByIdAsc();

    List<WebhookEndpoint> findByActiveTrue();
}
