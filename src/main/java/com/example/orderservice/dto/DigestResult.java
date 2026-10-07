package com.example.orderservice.dto;

// The admin daily digest: whether it was emailed (sent) and to whom, the subject/body as built, and when it was not
// sent, why (reason). preview() returns the same shape with sent=false so the text can be inspected first.
public record DigestResult(boolean sent, String to, String subject, String body, String reason) {
}
