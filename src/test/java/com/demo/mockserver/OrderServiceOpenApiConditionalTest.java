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
import java.util.List;
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

    /**
     * Velocity template with built-in randomization functions: each request gets a freshly
     * generated body while the openAPI() matcher ensures only spec-valid requests match.
     *
     * MockServer re-evaluates all $rand_* and $uuid references on every render, so two
     * requests to the same endpoint produce structurally identical but value-distinct bodies.
     * The UUID in correlationId is the hard guarantee that the bodies differ.
     *
     * Available built-ins: $uuid, $rand_int, $rand_int_10, $rand_int_100,
     *                       $now_epoch, $now_iso_8601, $now_rfc_1123
     */
    @Test
    @Order(5)
    void getOrderById_velocityTemplate_randomizedBodyConformsToSpec() throws Exception {
        mockServer.when(openAPI(spec, "getOrderById")).respond(
            template(
                HttpTemplate.TemplateType.VELOCITY,
                """
                {
                  'statusCode': 200,
                  'headers': { 'Content-Type': ['application/json'] },
                  'body': '{"id": $request.path.replaceAll("^.*/", ""), "userId": $rand_int_10, "product": "Widget-$rand_int_10", "total": $rand_int_100, "status": "SHIPPED", "correlationId": "$uuid"}'
                }
                """
            )
        );

        var res5 = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/5")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        var res99 = http.send(
            HttpRequest.newBuilder().uri(URI.create(BASE + "/api/orders/99")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, res5.statusCode());
        assertEquals(200, res99.statusCode());

        // id must echo the orderId from the URL path
        assertTrue(res5.body().contains("\"id\": 5"),   "expected id 5, body was: "  + res5.body());
        assertTrue(res99.body().contains("\"id\": 99"), "expected id 99, body was: " + res99.body());

        // Remaining fields conform to the Order schema shape defined in the spec
        for (var body : List.of(res5.body(), res99.body())) {
            assertTrue(body.matches("(?s).*\"userId\":\\s*\\d+.*"),        "userId must be an integer: " + body);
            assertTrue(body.matches("(?s).*\"product\":\\s*\"[^\"]+\".*"), "product must be a string: "  + body);
            assertTrue(body.matches("(?s).*\"status\":\\s*\"[^\"]+\".*"),  "status must be a string: "   + body);
            assertTrue(body.matches("(?s).*\"correlationId\":\\s*\"[0-9a-f\\-]{36}\".*"), "correlationId must be a UUID: " + body);
        }

        // UUID guarantees the two responses are distinct even when hitting the same endpoint
        assertNotEquals(res5.body(), res99.body(), "Each render should produce a unique body");
    }
}
