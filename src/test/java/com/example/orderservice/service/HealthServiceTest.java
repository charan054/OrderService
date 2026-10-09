package com.example.orderservice.service;

import com.example.orderservice.dto.ServiceHealth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;

    @Test
    void reportsUpForAnyHttpAnswerAndDownForRefusedConnections() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.sendResponseHeaders(401, -1); ex.close(); });
        server.start();
        try {
            int up = server.getAddress().getPort();
            // Nothing listens on port 1.
            HealthService service = new HealthService(dataSource,
                    "Alive=http://127.0.0.1:" + up + ", Dead=http://127.0.0.1:1 ,bad-entry");
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(2)).thenReturn(true);

            List<ServiceHealth> rows = service.check();

            assertEquals(4, rows.size()); // self, database, Alive, Dead - the malformed entry is ignored
            assertEquals("UP", find(rows, "OrderService").status());
            assertEquals("UP", find(rows, "Database").status());
            assertEquals("UP", find(rows, "Alive").status());
            assertEquals("HTTP 401", find(rows, "Alive").detail());
            assertEquals("DOWN", find(rows, "Dead").status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void databaseFailureIsReportedDownNotThrown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("refused"));

        ServiceHealth db = new HealthService(dataSource, "").checkDatabase();

        assertEquals("DOWN", db.status());
        assertTrue(db.responseMillis() >= 0);
    }

    private static ServiceHealth find(List<ServiceHealth> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }
}
