package com.example.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class CartServiceApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    // Behind the ApiGateway the login rate limits and audit log need the real client address, not the gateway's.
    @Test
    void trustsForwardedClientAddressFromInternalProxiesByDefault() {
        assertEquals("native", environment.getProperty("server.forward-headers-strategy"));
    }

}
