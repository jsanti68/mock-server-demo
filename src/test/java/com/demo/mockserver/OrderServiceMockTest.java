package com.demo.mockserver;

import org.junit.jupiter.api.*;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.OpenAPIDefinition.openAPI;

/**
 * Example: mocking the order-service microservice.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderServiceMockTest {

    private static MockServerClient mockServer;
    private static HttpClient http;

    private static final String BASE = "http://%s:%d".formatted(MockServerConfig.HOST, MockServerConfig.PORT);

    @BeforeAll
    static void setUp() {
        mockServer = MockServerConfig.newClient();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void reset() {
        mockServer.reset();
    }

    @Test
    @Order(1)
    void getOrdersByUser_returnsList() throws Exception {
        mockServer
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/api/orders")
                    .withQueryStringParameter("userId", "42")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        [
                          {"id": 1, "userId": 42, "product": "Widget", "total": 29.99},
                          {"id": 2, "userId": 42, "product": "Gadget", "total": 49.99}
                        ]
                        """)
            );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/orders?userId=42"))
            .GET()
            .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Widget"));
    }

    @Test
    @Order(2)
    void getOrderById_openApiSpec_returnsOrder() throws Exception {
        String spec = new String(
            Objects.requireNonNull(getClass().getResourceAsStream("/openapi/order-service.yaml"))
                   .readAllBytes(),
            StandardCharsets.UTF_8
        );

        mockServer
            .when(openAPI(spec, "getOrderById"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {"id": 1, "userId": 42, "product": "Widget", "total": 29.99, "status": "SHIPPED"}
                        """)
            );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/orders/1"))
            .GET()
            .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("SHIPPED"));
    }

    @Test
    @Order(3)
    void createOrder_withDelay_simulatesLatency() throws Exception {
        // Simulates a slow downstream service
        mockServer
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/api/orders")
            )
            .respond(
                response()
                    .withStatusCode(201)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withDelay(org.mockserver.model.Delay.milliseconds(300))
                    .withBody("""
                        {"id": 99, "status": "CREATED"}
                        """)
            );

        var body = """
            {"userId": 42, "product": "Widget", "total": 29.99}
            """;

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/orders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        long start = System.currentTimeMillis();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(201, res.statusCode());
        assertTrue(elapsed >= 300, "Expected simulated latency >= 300ms");
    }
}
