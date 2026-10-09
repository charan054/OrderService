package com.example.orderservice.service;

import com.example.orderservice.dto.HealthHistory;
import com.example.orderservice.dto.ServiceHealth;
import com.example.orderservice.entity.HealthSample;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.HealthSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthHistoryServiceTest {
    // 10:30 UTC: the current hour starts at 10:00, so a 24-hour window starts at 11:00 the day before.
    private static final Instant NOW = Instant.parse("2026-10-09T10:30:00Z");
    private static final Instant FIRST_HOUR = Instant.parse("2026-10-08T11:00:00Z");

    @Mock
    private HealthService healthService;
    @Mock
    private HealthSampleRepository samples;

    private HealthHistoryService service;
    private final List<HealthSample> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new HealthHistoryService(healthService, samples, Clock.fixed(NOW, ZoneOffset.UTC), 7);
        lenient().when(samples.findByCheckedAtGreaterThanEqualOrderByCheckedAtAscIdAsc(any())).thenAnswer(inv -> {
            Instant since = inv.getArgument(0);
            // Same contract as the real query: from `since`, oldest first.
            return stored.stream().filter(s -> !s.getCheckedAt().isBefore(since))
                    .sorted(java.util.Comparator.comparing(HealthSample::getCheckedAt)).toList();
        });
    }

    private void sample(String service, String status, String at) {
        HealthSample s = new HealthSample();
        s.setServiceName(service);
        s.setStatus(status);
        s.setCheckedAt(Instant.parse(at));
        stored.add(s);
    }

    @Test
    void recordStoresOneRowPerServiceAndPurgesPastRetention() {
        when(healthService.check()).thenReturn(List.of(
                new ServiceHealth("OrderService", "UP", 0, "answering this request"),
                new ServiceHealth("Database", "UP", 3, null),
                new ServiceHealth("ProductService", "DOWN", 2001, "not reachable"),
                new ServiceHealth("Weird", "SOMETHING", 1, "x".repeat(500))));

        List<ServiceHealth> result = service.record();

        assertEquals(4, result.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HealthSample>> rows = ArgumentCaptor.forClass(List.class);
        verify(samples).saveAll(rows.capture());
        assertEquals(List.of("UP", "UP", "DOWN", "DOWN"), rows.getValue().stream().map(HealthSample::getStatus).toList());
        assertEquals(NOW, rows.getValue().get(0).getCheckedAt());
        assertEquals(200, rows.getValue().get(3).getDetail().length());
        verify(samples).deleteOlderThan(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void historyGivesPerServiceUptimeAndOneBucketPerHourOldestFirst() {
        sample("ProductService", "UP", "2026-10-08T11:59:00Z"); // the very first hour of the window
        sample("ProductService", "UP", "2026-10-09T09:10:00Z");
        sample("ProductService", "DOWN", "2026-10-09T09:40:00Z");
        sample("OrderService", "UP", "2026-10-09T10:05:00Z");
        // The latest probe records both services together, OrderService first (the board's order).
        sample("OrderService", "UP", "2026-10-09T10:20:00Z");
        sample("ProductService", "DOWN", "2026-10-09T10:20:00Z");

        HealthHistory history = service.history(24);

        assertEquals(24, history.hours());
        assertEquals(FIRST_HOUR, history.from());
        assertEquals(Instant.parse("2026-10-09T11:00:00Z"), history.to());
        assertEquals(6, history.totalSamples());
        assertEquals(List.of("OrderService", "ProductService"), history.services().stream().map(HealthHistory.ServiceUptime::name).toList());

        HealthHistory.ServiceUptime orderService = history.services().get(0);
        assertEquals(100.0, orderService.uptimePercent());
        assertEquals(24, orderService.buckets().size());
        assertEquals(FIRST_HOUR, orderService.buckets().get(0).hourStart());
        assertEquals(Instant.parse("2026-10-09T10:00:00Z"), orderService.buckets().get(23).hourStart());
        assertEquals(2, orderService.buckets().get(23).samples());
        assertEquals(2, orderService.buckets().get(23).up());
        assertEquals(0, orderService.buckets().get(22).samples()); // no data is not "down"

        HealthHistory.ServiceUptime product = history.services().get(1);
        assertEquals(4, product.samples());
        assertEquals(50.0, product.uptimePercent());
        assertEquals("DOWN", product.lastStatus());
        assertEquals(Instant.parse("2026-10-09T10:20:00Z"), product.lastCheckedAt());
        assertEquals(1, product.buckets().get(0).up());       // 11:59 yesterday sits in the first bucket
        assertEquals(1, product.buckets().get(22).up());      // 09:10 up, 09:40 down
        assertEquals(2, product.buckets().get(22).samples());
        assertEquals(0, product.buckets().get(23).up());      // 10:20 down
        assertEquals(1, product.buckets().get(23).samples());
    }

    @Test
    void aShorterWindowOnlyCountsItsOwnHours() {
        sample("OrderService", "DOWN", "2026-10-09T07:30:00Z"); // outside a 3-hour window (08:00-10:59)
        sample("OrderService", "UP", "2026-10-09T09:30:00Z");

        HealthHistory history = service.history(3);

        assertEquals(Instant.parse("2026-10-09T08:00:00Z"), history.from());
        assertEquals(3, history.services().get(0).buckets().size());
        assertEquals(1, history.totalSamples());
        assertEquals(100.0, history.services().get(0).uptimePercent());
    }

    @Test
    void noSamplesMeansNoServicesAndTheDefaultIsTwentyFourHours() {
        HealthHistory history = service.history(null);

        assertEquals(24, history.hours());
        assertEquals(0, history.totalSamples());
        assertEquals(0, history.services().size());
        assertEquals(7, history.retentionDays());
    }

    @Test
    void aServiceWithNoSamplesInTheWindowHasNoUptimeFigure() {
        // Only possible for a sample stamped in the future (clock change): it is ignored rather than miscounted.
        sample("OrderService", "UP", "2026-10-09T13:00:00Z");

        HealthHistory.ServiceUptime uptime = service.history(24).services().get(0);

        assertNull(uptime.uptimePercent());
        assertEquals(0, uptime.samples());
    }

    @Test
    void rejectsAnOutOfRangeWindow() {
        assertThrows(ProductException.class, () -> service.history(0));
        assertThrows(ProductException.class, () -> service.history(169));
        assertEquals(168, service.history(168).hours());
    }
}
