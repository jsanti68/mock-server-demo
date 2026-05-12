package com.demo.mockserver;

import org.junit.jupiter.api.*;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Example: mocking the user-service microservice.
 * Requires MockServer running at localhost:1080 (docker run -p 1080:1080 mockserver/mockserver).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserServiceMockTest {

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
    void getUser_returnsUser() throws Exception {
        mockServer
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/api/users/42")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {"id": 42, "name": "Ada Lovelace", "email": "ada@example.com"}
                        """)
            );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/users/42"))
            .GET()
            .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Ada Lovelace"));
    }

    @Test
    @Order(2)
    void getUser_notFound_returns404() throws Exception {
        mockServer
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/api/users/999")
            )
            .respond(
                response()
                    .withStatusCode(404)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {"error": "User not found"}
                        """)
            );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/users/999"))
            .GET()
            .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, res.statusCode());
    }

    @Test
    @Order(3)
    void createUser_returnsCreated() throws Exception {
        mockServer
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/api/users")
                    .withContentType(MediaType.APPLICATION_JSON)
            )
            .respond(
                response()
                    .withStatusCode(201)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {"id": 100, "name": "New User", "email": "new@example.com"}
                        """)
            );

        var body = """
            {"name": "New User", "email": "new@example.com"}
            """;

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/users"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, res.statusCode());
        assertTrue(res.body().contains("\"id\": 100"));
    }
}
