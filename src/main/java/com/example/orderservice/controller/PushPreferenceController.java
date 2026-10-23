package com.example.orderservice.controller;

import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.PushKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Which kinds of browser notification a customer wants. The customer's own signed-in session (or the service key).
@RestController
@RequestMapping("/cart/push/prefs")
public class PushPreferenceController {
    public record Option(String key, String label, boolean on) {
    }

    public record Update(long phno, List<String> off) {
    }

    @Autowired
    private CustomerAccountRepository accounts;

    @GetMapping
    public List<Option> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return options(accounts.findById(phno).map(CustomerAccount::getPushMuted).orElse(null));
    }

    // "off" lists the kinds to switch off; everything else is on.
    @PutMapping
    public List<Option> change(@RequestBody Update request) {
        CustomerAccess.requireSelfOrService(request.phno());
        Set<PushKind> muted = EnumSet.noneOf(PushKind.class);
        for (String key : request.off() == null ? List.<String>of() : request.off()) {
            Set<PushKind> one = PushKind.parse(key);
            if (one.isEmpty()) {
                throw new ProductException("Unknown notification kind: " + key);
            }
            muted.addAll(one);
        }
        CustomerAccount account = accounts.findById(request.phno())
                .orElseThrow(() -> new ProductException("Sign in with a verified account first"));
        account.setPushMuted(PushKind.serialize(muted));
        accounts.save(account);
        return options(account.getPushMuted());
    }

    private static List<Option> options(String stored) {
        Set<PushKind> muted = PushKind.parse(stored);
        List<Option> result = new ArrayList<>();
        for (PushKind kind : PushKind.values()) {
            result.add(new Option(kind.key(), kind.label(), !muted.contains(kind)));
        }
        return result;
    }
}
