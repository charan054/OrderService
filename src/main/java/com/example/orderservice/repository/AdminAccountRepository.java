package com.example.orderservice.repository;

import com.example.orderservice.entity.AdminAccount;
import com.example.orderservice.entity.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {
    Optional<AdminAccount> findByUsername(String username);
    List<AdminAccount> findAllByOrderByUsernameAsc();
    long countByRoleAndActiveTrue(AdminRole role);
}
