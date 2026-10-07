package com.example.orderservice.controller;

import com.example.orderservice.dto.NewSupportTicket;
import com.example.orderservice.dto.SupportTicketView;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.SupportTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// Support requests. /tickets, /tickets/mine and /tickets/{id}/reply are the signed-in customer's own (or the service
// key's); everything under /admin is service-key only (SecurityConfig default).
@RestController
@RequestMapping("/support")
public class SupportTicketController {
    @Autowired
    private SupportTicketService supportTicketService;

    @PostMapping("/tickets")
    public SupportTicketView open(@RequestBody NewSupportTicket request) {
        CustomerAccess.requireSelfOrService(request.customerPhno());
        return supportTicketService.open(request);
    }

    @GetMapping("/tickets/mine")
    public List<SupportTicketView> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return supportTicketService.mine(phno);
    }

    // Body: {"message": "..."}
    @PostMapping("/tickets/{id}/reply")
    public SupportTicketView reply(@PathVariable long id, @RequestParam long phno, @RequestBody Map<String, String> body) {
        CustomerAccess.requireSelfOrService(phno);
        return supportTicketService.customerReply(id, phno, body.get("message"));
    }

    // status = UNRESOLVED (default), ALL, OPEN, ANSWERED or RESOLVED.
    @GetMapping("/admin/tickets")
    public List<SupportTicket> queue(@RequestParam(required = false) String status) {
        return supportTicketService.queue(status);
    }

    @GetMapping("/admin/tickets/{id}")
    public SupportTicketView get(@PathVariable long id) {
        return supportTicketService.get(id);
    }

    @PostMapping("/admin/tickets/{id}/reply")
    public SupportTicketView staffReply(@PathVariable long id, @RequestBody Map<String, String> body) {
        return supportTicketService.staffReply(id, body.get("message"));
    }

    @PostMapping("/admin/tickets/{id}/resolve")
    public SupportTicketView resolve(@PathVariable long id, @RequestParam(required = false) String note) {
        return supportTicketService.resolve(id, note);
    }
}
