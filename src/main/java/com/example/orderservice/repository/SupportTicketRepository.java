package com.example.orderservice.repository;

import com.example.orderservice.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findByCustomerPhnoOrderByIdDesc(long customerPhno);

    long countByCustomerPhnoAndStatusIn(long customerPhno, Collection<SupportTicket.Status> statuses);

    List<SupportTicket> findByOrderIdAndStatusIn(long orderId, Collection<SupportTicket.Status> statuses);

    List<SupportTicket> findByStatusInOrderByUpdatedAtAsc(Collection<SupportTicket.Status> statuses);
}
