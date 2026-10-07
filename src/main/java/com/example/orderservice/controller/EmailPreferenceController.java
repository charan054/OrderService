package com.example.orderservice.controller;

import com.example.orderservice.dto.EmailPreferences;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.EmailPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Promotional-email preferences. /unsubscribe is the public link at the bottom of every promotional email (guarded by
// its signed token, not a session); /mine is the signed-in customer's own switch - see CustomerAccess.
@RestController
@RequestMapping("/prefs")
public class EmailPreferenceController {
    @Autowired
    private EmailPreferenceService preferences;

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@RequestParam long phno, @RequestParam String token) {
        preferences.unsubscribe(phno, token);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Unsubscribed</title></head><body style=\"font-family:sans-serif;max-width:480px;margin:48px auto;padding:0 16px;\">"
                + "<h2>You're unsubscribed</h2><p>You won't get cart reminders, restock or price-drop alerts, or points-expiry "
                + "warnings from Charan Mart any more. Emails about your own orders and sign-in codes will still arrive.</p>"
                + "<p>Changed your mind? Turn them back on in the shop under \"My account\".</p></body></html>";
    }

    @GetMapping("/mine")
    public EmailPreferences mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return preferences.get(phno);
    }

    @PutMapping("/mine")
    public EmailPreferences update(@RequestParam long phno, @RequestParam boolean marketingEmails) {
        CustomerAccess.requireSelfOrService(phno);
        return preferences.set(phno, marketingEmails);
    }
}
