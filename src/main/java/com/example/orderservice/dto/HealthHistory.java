package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// GET /cart/health/history - how each service of the stack has been doing over the last `hours` hours, from the probes
// HealthHistoryService has recorded. One hourly bucket per hour (oldest first, the last one is the current hour), so
// the dashboard can draw an uptime strip. uptimePercent is null when a service has no samples in the window.
public record HealthHistory(int hours, Instant from, Instant to, int retentionDays, long totalSamples,
                            List<ServiceUptime> services) {
    public record ServiceUptime(String name, Double uptimePercent, long samples, String lastStatus, Instant lastCheckedAt,
                                List<Bucket> buckets) {
    }

    // samples = probes in that hour, up = how many of them found the service UP (0 samples = no data for the hour).
    public record Bucket(Instant hourStart, int samples, int up) {
    }
}
