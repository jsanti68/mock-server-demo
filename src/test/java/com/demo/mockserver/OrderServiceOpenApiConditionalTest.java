package com.demo.mockserver;

import org.junit.jupiter.api.*;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpTemplate;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.JsonBody;
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
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.OpenAPIDefinition.openAPI;

/**
 * OpenAPI-backed tests that demonstrate conditional response strategies.
 *
 * Each test registers expectations against the order-service OpenAPI spec and
 * layers one of four conditional mechanisms on top:
 *   1. Priority routing       — a specific high-priority expectation overrides the general one
 *   2. Times-based routing    — the first expectation fires once then falls through to a second
 *   3. Mustache template      — the response body is generated from request data at runtime
 *   4. Body-based routing     — a partial JSON body match drives a high-priority expectation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderServiceOpenApiConditionalTest {

    private static MockServerClient mockServer;
    private static HttpClient http;
    private static String spec;

    private static final String BASE = "http://%s:%d".formatted(MockServerConfig.HOST, MockServerConfig.PORT);

    @BeforeAll
    static void setUp() throws Exception {
        mockServer = MockServerConfig.newClient();
        http = HttpClient.newHttpClient();
        spec = new String(
            Objects.requireNonNull(
                OrderServiceOpenApiConditionalTest.class.getResourceAsStream("/openapi/order-service.yaml")
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );
    }

    @AfterEach
    void reset() {
        mockServer.reset();
    }

    /**
     * Priority routing: a high-priority expectation for /api/orders/999 returns 404 while
     * the low-priority OpenAPI-backed expectation handles all other valid order IDs with 200.
     */
    @Test
    @Order(1)
    void getOrderById_priorityRouting_specificIdReturns404() throws Exception {
        mockServer.when(
            request().withMethod("GET").withPath("/api/orders/999"),
            Times.unlimited(), TimeToLive.unlimited(), 10
        ).respond(
            response()
                .withStatusCode(404)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"error": "Order not found"}
                    """)
        );

        mockServer.when(
            openAPI(spec, "getOrderById"),
            Times.unlimited(), TimeToLive.unlimited(), 0
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"id": 1, "userId": 42, "product": "Widget", "total": 29.99, "status": "SHIPPED"}
                    """)
        );

        var found = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/1")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        var notFound = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/999")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, found.statusCode());
        assertEquals(404, notFound.statusCode());
        assertTrue(notFound.body().contains("Order not found"));
    }

    /**
     * Times-based routing: the first call receives a 503 (simulating a transient failure);
     * that expectation exhausts after one use and the retry gets the 200 fallback.
     */
    @Test
    @Order(2)
    void listOrders_timesRouting_transientErrorThenSuccess() throws Exception {
        mockServer.when(openAPI(spec, "listOrders"), Times.once()).respond(
            response()
                .withStatusCode(503)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"error": "Service temporarily unavailable"}
                    """)
        );

        mockServer.when(openAPI(spec, "listOrders"), Times.unlimited()).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    [{"id": 1, "userId": 42, "product": "Widget", "total": 29.99}]
                    """)
        );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/orders?userId=42"))
            .GET()
            .build();

        var first  = http.send(req, HttpResponse.BodyHandlers.ofString());
        var retry  = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(503, first.statusCode());
        assertEquals(200, retry.statusCode());
        assertTrue(retry.body().contains("Widget"));
    }

    /**
     * Mustache template: the response body is rendered at request time using Mustache,
     * reflecting the requested path back so each order ID gets a distinct response.
     *
     * The template must produce a full HttpResponseDTO-shaped object (statusCode + body);
     * single-quoted keys let us embed double-quoted JSON in the body string without escaping.
     */
    @Test
    @Order(3)
    void getOrderById_mustacheTemplate_dynamicPathInResponse() throws Exception {
        mockServer.when(openAPI(spec, "getOrderById")).respond(
            template(
                HttpTemplate.TemplateType.MUSTACHE,
                """
                {
                  'statusCode': 200,
                  'headers': { 'Content-Type': ['application/json'] },
                  'body': '{"requestedPath": "{{request.path}}", "userId": 42, "product": "Widget", "status": "SHIPPED"}'
                }
                """
            )
        );

        var res7  = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/7")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        var res99 = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/99")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, res7.statusCode());
        assertTrue(res7.body().contains("/api/orders/7"),  "body was: " + res7.body());
        assertTrue(res99.body().contains("/api/orders/99"), "body was: " + res99.body());
    }

    /**
     * Body-based routing: a POST body that contains "vip": true is intercepted by a
     * high-priority expectation that returns a discounted response; all other valid
     * createOrder requests fall through to the low-priority OpenAPI-backed expectation.
     */
    @Test
    @Order(4)
    void createOrder_bodyRouting_vipOrderGetsDiscount() throws Exception {
        mockServer.when(
            request()
                .withMethod("POST")
                .withPath("/api/orders")
                .withBody(json("{\"vip\": true}", MatchType.ONLY_MATCHING_FIELDS)),
            Times.unlimited(), TimeToLive.unlimited(), 10
        ).respond(
            response()
                .withStatusCode(201)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"id": 200, "status": "CREATED", "discount": 20}
                    """)
        );

        mockServer.when(
            openAPI(spec, "createOrder"),
            Times.unlimited(), TimeToLive.unlimited(), 0
        ).respond(
            response()
                .withStatusCode(201)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"id": 100, "status": "CREATED", "discount": 0}
                    """)
        );

        var standard = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                    {"userId": 42, "product": "Widget", "total": 29.99}
                    """))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        var vip = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                    {"userId": 1, "product": "Gadget", "total": 99.99, "vip": true}
                    """))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, standard.statusCode());
        assertTrue(standard.body().contains("\"discount\": 0"));

        assertEquals(201, vip.statusCode());
        assertTrue(vip.body().contains("\"discount\": 20"));
    }
}
