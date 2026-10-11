package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// The admin's "needs attention" inbox (GET /cart/attention): one row per kind of thing that is waiting on someone.
// count is null when the row could not be worked out (unavailable=true, e.g. ProductService is down) - that is not the
// same as 0 and is never added into total. more=true means count is a floor (the list it came from was cut off).
// orderIds, where given, are the orders involved (at most 10), so the dashboard can jump straight to them.
public record NeedsAttention(Instant generatedAt, int total, List<Item> items) {
    public record Item(String key, String title, Integer count, boolean more, boolean unavailable, String detail,
                       List<Long> orderIds) {
    }
}
