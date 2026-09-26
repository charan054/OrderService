package com.example.orderservice.repository;

import com.example.orderservice.entity.ShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {
    List<ShippingAddress> findByCustomerPhno(long phno);
    List<ShippingAddress> findByCustomerPhnoAndIsDefaultTrue(long phno);
}
