package com.example.orderservice.service;

import com.example.orderservice.dto.HealthHistory;
import com.example.orderservice.dto.ServiceHealth;
import com.example.orderservice.entity.HealthSample;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.HealthSampleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what the admin health board showed over time. Each {@link #record()} runs the same probe as the board
 * (HealthService) and stores one row per service; {@link #history} turns the rows of the last N hours into an uptime
 * figure and an hourly strip per service. Probing on a timer is HealthHistoryScheduler's job and is off unless
 * health.history.enabled=true; POST /cart/health/history/record takes a sample by hand. Rows older than
 * health.history.retention-days (default 7) are deleted each time a sample is taken, so the table stays small.
 * <p>
 * Uptime here means "the probe got an answer", exactly as the board defines UP, and only counts the times somebody
 * (or the scheduler) actually looked: hours with no samples show as no data, not as down.
 */
@Service
public class HealthHistoryService {
    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 168;

    private final HealthService healthService;
    private final HealthSampleRepository samples;
    private final Clock clock;
    private final int retentionDays;

    public HealthHistoryService(HealthService healthService, HealthSampleRepository samples, Clock clock,
                                @Value("${health.history.retention-days:7}") int retentionDays) {
        this.healthService = healthService;
        this.samples = samples;
        this.clock = clock;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** Probes the stack now, stores the results and purges rows past retention. Returns what was probed. */
    public List<ServiceHealth> record() {
        List<ServiceHealth> results = healthService.check();
        Instant now = clock.instant();
        List<HealthSample> rows = new ArrayList<>();
        for (ServiceHealth result : results) {
            HealthSample row = new HealthSample();
            row.setCheckedAt(now);
            row.setServiceName(truncate(result.name(), 40));
            row.setStatus("UP".equals(result.status()) ? "UP" : "DOWN");
            row.setResponseMillis(result.responseMillis());
            row.setDetail(truncate(result.detail(), 200));
            rows.add(row);
        }
        samples.saveAll(rows);
        samples.deleteOlderThan(now.minus(Duration.ofDays(retentionDays)));
        return results;
    }

    public HealthHistory history(Integer hoursParam) {
        int hours = hoursParam == null ? DEFAULT_HOURS : hoursParam;
        if (hours < 1 || hours > MAX_HOURS) {
            throw new ProductException("hours must be between 1 and " + MAX_HOURS);
        }
        Instant now = clock.instant();
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        Instant firstHour = currentHour.minus(Duration.ofHours(hours - 1L));
        List<HealthSample> rows = samples.findByCheckedAtGreaterThanEqualOrderByCheckedAtAscIdAsc(firstHour);

        // Services in the order of their most recent probe: one probe records every service together in the board's own
        // order (OrderService, Database, then the targets), so this reproduces it, and a service that has since been
        // removed from the targets simply sinks to the top rather than reshuffling the rest.
        Map<String, List<HealthSample>> byService = new LinkedHashMap<>();
        Map<String, Integer> lastRowIndex = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            HealthSample row = rows.get(i);
            byService.computeIfAbsent(row.getServiceName(), k -> new ArrayList<>()).add(row);
            lastRowIndex.put(row.getServiceName(), i);
        }
        List<Map.Entry<String, List<HealthSample>>> ordered = new ArrayList<>(byService.entrySet());
        ordered.sort(Comparator.comparingInt(e -> lastRowIndex.get(e.getKey())));
        List<HealthHistory.ServiceUptime> services = new ArrayList<>();
        for (Map.Entry<String, List<HealthSample>> entry : ordered) {
            List<HealthSample> own = entry.getValue();
            int[] count = new int[hours];
            int[] up = new int[hours];
            long upTotal = 0;
            for (HealthSample s : own) {
                int bucket = (int) Duration.between(firstHour, s.getCheckedAt().truncatedTo(ChronoUnit.HOURS)).toHours();
                if (bucket < 0 || bucket >= hours) {
                    continue; // a sample stamped in the future (clock change): not part of this window
                }
                count[bucket]++;
                if ("UP".equals(s.getStatus())) {
                    up[bucket]++;
                    upTotal++;
                }
            }
            List<HealthHistory.Bucket> buckets = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < hours; i++) {
                buckets.add(new HealthHistory.Bucket(firstHour.plus(Duration.ofHours(i)), count[i], up[i]));
                total += count[i];
            }
            HealthSample last = own.get(own.size() - 1);
            Double uptime = total == 0 ? null : Math.round(upTotal * 1000.0 / total) / 10.0;
            services.add(new HealthHistory.ServiceUptime(entry.getKey(), uptime, total, last.getStatus(), last.getCheckedAt(), buckets));
        }
        long totalSamples = services.stream().mapToLong(HealthHistory.ServiceUptime::samples).sum();
        return new HealthHistory(hours, firstHour, currentHour.plus(Duration.ofHours(1)), retentionDays, totalSamples, services);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
