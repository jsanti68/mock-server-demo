package com.demo.mockserver;

import org.mockserver.client.MockServerClient;

public class MockServerConfig {

    public static final String HOST = System.getenv().getOrDefault("MOCKSERVER_HOST", "localhost");
    public static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MOCKSERVER_PORT", "1080"));

    private MockServerConfig() {}

    public static MockServerClient newClient() {
        return new MockServerClient(HOST, PORT);
    }
}
