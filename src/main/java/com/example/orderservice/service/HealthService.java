package com.example.orderservice.service;

import com.example.orderservice.dto.ServiceHealth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Admin health board: is each service of the stack answering, and is the database reachable. A service counts as UP
 * when it answers HTTP at all on its base URL (a 401 or 404 still proves it is running - none of them share an
 * unauthenticated health route), DOWN when the connection fails or times out. Probes run in parallel with a short
 * timeout so the board answers in about two seconds even when several services are stopped. Targets come from
 * health.targets ("Name=url,Name=url"); OrderService itself is implied by this very call being answered.
 */
@Service
public class HealthService {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final DataSource dataSource;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final List<String[]> targets = new ArrayList<>();

    public HealthService(DataSource dataSource, @Value("${health.targets:}") String targetSpec) {
        this.dataSource = dataSource;
        if (targetSpec != null) {
            for (String part : targetSpec.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0 && eq < part.length() - 1) {
                    targets.add(new String[]{part.substring(0, eq).trim(), part.substring(eq + 1).trim()});
                }
            }
        }
    }

    public List<ServiceHealth> check() {
        List<CompletableFuture<ServiceHealth>> probes = new ArrayList<>();
        probes.add(CompletableFuture.completedFuture(new ServiceHealth("OrderService", "UP", 0L, "answering this request")));
        probes.add(CompletableFuture.supplyAsync(this::checkDatabase));
        for (String[] t : targets) {
            probes.add(CompletableFuture.supplyAsync(() -> probe(t[0], t[1])));
        }
        return probes.stream().map(CompletableFuture::join).toList();
    }

    ServiceHealth checkDatabase() {
        long start = System.nanoTime();
        try (var connection = dataSource.getConnection()) {
            boolean ok = connection.isValid(2);
            return new ServiceHealth("Database", ok ? "UP" : "DOWN", millisSince(start), ok ? null : "connection not valid");
        } catch (Exception e) {
            return new ServiceHealth("Database", "DOWN", millisSince(start), e.getClass().getSimpleName());
        }
    }

    ServiceHealth probe(String name, String url) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new ServiceHealth(name, "UP", millisSince(start), "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ServiceHealth(name, "DOWN", millisSince(start), "interrupted");
        } catch (Exception e) {
            return new ServiceHealth(name, "DOWN", millisSince(start), "not reachable");
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
