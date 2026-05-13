## Project overview

A Java 17 / Maven demo that shows how to use the **MockServer Java client** (`mockserver-client-java` v5.15.0) against a live MockServer instance (Docker). Tests are written with **JUnit 5** and use Java's built-in `HttpClient` to make real HTTP calls to the mock.

## Prerequisites

MockServer must be running before executing tests. Pin the tag to match the client version — mismatched major.minor versions cause `ClientException` on every expectation call:

```bash
docker run -d -p 1080:1080 mockserver/mockserver:mockserver-5.15.0
```

Connection coordinates are read from environment variables:

| Variable | Default |
|---|---|
| `MOCKSERVER_HOST` | `localhost` |
| `MOCKSERVER_PORT` | `1080` |

MockServer's status endpoint requires a `PUT` (not `GET`):
```bash
curl -s -X PUT http://localhost:1080/mockserver/status
```

## Commands

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=OrderServiceOpenApiConditionalTest

# Run a single test method
mvn test -Dtest=UserServiceMockTest#getUser_returnsUser

# Build without running tests
mvn package -DskipTests
```

## Architecture

```
MockServerConfig                    — reads MOCKSERVER_HOST / MOCKSERVER_PORT and
                                      vends MockServerClient instances

*MockTest                           — basic expectation/request/assert pattern;
                                      one MockServerClient per class (@BeforeAll),
                                      mockServer.reset() in @AfterEach for isolation

OrderServiceOpenApiConditionalTest  — OpenAPI-backed tests with conditional routing;
                                      loads spec inline from classpath (see below)

src/test/resources/
  openapi/order-service.yaml        — OpenAPI 3.0 spec defining listOrders,
                                      createOrder, getOrderById operations
  expectations/
    all-conditional-expectations.json — all 8 expectations as a single JSON array
                                        ready to preload into any MockServer instance
```

**Basic test pattern**: each test (1) registers an expectation via `mockServer.when(...).respond(...)`, (2) fires a real HTTP request with `HttpClient`, and (3) asserts on the response.

## OpenAPI expectation pattern

Because MockServer runs in Docker, it cannot read local files. The spec must be read in the test and passed inline:

```java
String spec = new String(
    getClass().getResourceAsStream("/openapi/order-service.yaml").readAllBytes(),
    StandardCharsets.UTF_8
);
mockServer.when(openAPI(spec, "operationId")).respond(response()...);
```

## Conditional response strategies

Four patterns are demonstrated in `OrderServiceOpenApiConditionalTest`:

**Priority routing** — combine a specific `request()` at high priority with an `openAPI()` fallback at lower priority. Higher integer = matched first.
```java
mockServer.when(request().withPath("/api/orders/999"), Times.unlimited(), TimeToLive.unlimited(), 10).respond(/* 404 */);
mockServer.when(openAPI(spec, "getOrderById"),         Times.unlimited(), TimeToLive.unlimited(), 0).respond(/* 200 */);
```

**Times-based routing** — `Times.once()` exhausts after the first match; subsequent requests fall through to the next registered expectation.
```java
mockServer.when(openAPI(spec, "listOrders"), Times.once()).respond(/* 503 */);
mockServer.when(openAPI(spec, "listOrders"), Times.unlimited()).respond(/* 200 */);
```

**Mustache response template** — `respond(template(...))` renders a dynamic response. The template string must produce a complete `HttpResponseDTO`-shaped object (with `statusCode`), not just the body. Use single-quoted keys to avoid escaping conflicts with the double-quoted JSON body:
```
{
  'statusCode': 200,
  'headers': { 'Content-Type': ['application/json'] },
  'body': '{"requestedPath": "{{request.path}}"}'
}
```
> JavaScript templates (`TemplateType.JAVASCRIPT`) are not supported on Java 17 — Nashorn was removed and the Docker image does not bundle GraalVM.

**Body-based routing** — `json(payload, MatchType.ONLY_MATCHING_FIELDS)` matches any request body that contains the specified fields, ignoring extras. `MatchType` is at `org.mockserver.matchers.MatchType`.
```java
request().withBody(json("{\"vip\": true}", MatchType.ONLY_MATCHING_FIELDS))
```

**Velocity response template** — same shape as Mustache (`statusCode` + `body` required) but uses `$variable` syntax. MockServer re-evaluates built-in helpers on every render:

| Built-in | Output |
|---|---|
| `$uuid` | UUID v4 string |
| `$rand_int_10` | random integer 0–9 |
| `$rand_int_100` | random integer 0–99 |
| `$now_iso_8601` | current timestamp |

> **Path parameter caveat**: `$request.pathParameters` is empty when using the `openAPI()` matcher — MockServer validates the path against the spec but does not backfill extracted parameters into the template context. Use `$request.path.replaceAll("^.*/", "")` to extract the last URL segment instead.

```java
mockServer.when(openAPI(spec, "getOrderById")).respond(
    template(HttpTemplate.TemplateType.VELOCITY,
        """
        {
          'statusCode': 200,
          'headers': { 'Content-Type': ['application/json'] },
          'body': '{"id": $request.path.replaceAll("^.*/", ""), "userId": $rand_int_10, "correlationId": "$uuid"}'
        }
        """)
);
```

## Loading expectations via the Admin API

MockServer exposes a REST API at `PUT /mockserver/expectation`. The file `src/test/resources/expectations/all-conditional-expectations.json` contains all 8 expectations from `OrderServiceOpenApiConditionalTest` as a prebuilt JSON array — load them all in one shot:

```bash
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' \
  -d @src/test/resources/expectations/all-conditional-expectations.json
```

The sections below show how to build each expectation individually. All OpenAPI-based calls use `jq` to safely embed the YAML spec as a JSON string.

```bash
# Load the spec once into a shell variable
SPEC=$(cat src/test/resources/openapi/order-service.yaml)
```

### 1. Priority routing

```bash
# High-priority: specific path → 404
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' \
  -d '{
    "priority": 10,
    "httpRequest": { "method": "GET", "path": "/api/orders/999" },
    "httpResponse": {
      "statusCode": 404,
      "headers": { "Content-Type": ["application/json"] },
      "body": "{\"error\": \"Order not found\"}"
    }
  }'

# Low-priority fallback: OpenAPI-matched → 200
jq -n --arg spec "$SPEC" '{
  "priority": 0,
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "getOrderById" },
  "httpResponse": {
    "statusCode": 200,
    "headers": { "Content-Type": ["application/json"] },
    "body": "{\"id\": 1, \"userId\": 42, \"product\": \"Widget\", \"status\": \"SHIPPED\"}"
  }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-
```

### 2. Times-based routing

```bash
# Fires once → 503
jq -n --arg spec "$SPEC" '{
  "times": { "remainingTimes": 1, "unlimited": false },
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "listOrders" },
  "httpResponse": {
    "statusCode": 503,
    "headers": { "Content-Type": ["application/json"] },
    "body": "{\"error\": \"Service temporarily unavailable\"}"
  }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-

# Unlimited fallback → 200
jq -n --arg spec "$SPEC" '{
  "times": { "unlimited": true },
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "listOrders" },
  "httpResponse": {
    "statusCode": 200,
    "headers": { "Content-Type": ["application/json"] },
    "body": "[{\"id\": 1, \"userId\": 42, \"product\": \"Widget\", \"total\": 29.99}]"
  }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-
```

### 3. Mustache response template

The template goes in `httpResponseTemplate.template` and must include `statusCode`. Single-quoted keys avoid escaping conflicts with the double-quoted JSON body string.

```bash
TEMPLATE=$(cat <<'EOF'
{
  'statusCode': 200,
  'headers': { 'Content-Type': ['application/json'] },
  'body': '{"requestedPath": "{{request.path}}", "userId": 42, "product": "Widget", "status": "SHIPPED"}'
}
EOF
)

jq -n --arg spec "$SPEC" --arg tmpl "$TEMPLATE" '{
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "getOrderById" },
  "httpResponseTemplate": { "templateType": "MUSTACHE", "template": $tmpl }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-
```

### 4. Body-based routing

```bash
# High-priority: body contains vip:true → discounted response
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' \
  -d '{
    "priority": 10,
    "httpRequest": {
      "method": "POST",
      "path": "/api/orders",
      "body": { "type": "JSON", "json": "{\"vip\": true}", "matchType": "ONLY_MATCHING_FIELDS" }
    },
    "httpResponse": {
      "statusCode": 201,
      "headers": { "Content-Type": ["application/json"] },
      "body": "{\"id\": 200, \"status\": \"CREATED\", \"discount\": 20}"
    }
  }'

# Low-priority fallback: OpenAPI-validated createOrder → standard response
jq -n --arg spec "$SPEC" '{
  "priority": 0,
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "createOrder" },
  "httpResponse": {
    "statusCode": 201,
    "headers": { "Content-Type": ["application/json"] },
    "body": "{\"id\": 100, \"status\": \"CREATED\", \"discount\": 0}"
  }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-
```

### 5. Velocity response template

The `id` field mirrors the orderId from the URL using `$request.path.replaceAll("^.*/", "")`. Other fields are randomized on every render via built-in helpers.

```bash
TEMPLATE=$(cat <<'EOF'
{
  'statusCode': 200,
  'headers': { 'Content-Type': ['application/json'] },
  'body': '{"id": $request.path.replaceAll("^.*/", ""), "userId": $rand_int_10, "product": "Widget-$rand_int_10", "total": $rand_int_100, "status": "SHIPPED", "correlationId": "$uuid"}'
}
EOF
)

jq -n --arg spec "$SPEC" --arg tmpl "$TEMPLATE" '{
  "httpRequest": { "specUrlOrPayload": $spec, "operationId": "getOrderById" },
  "httpResponseTemplate": { "templateType": "VELOCITY", "template": $tmpl }
}' | curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' -d @-
```

### Admin shortcuts

```bash
# Inspect all active expectations
curl -s -X PUT 'http://localhost:1080/mockserver/retrieve?type=ACTIVE_EXPECTATIONS' | jq .

# Clear all expectations and recorded requests
curl -s -X PUT http://localhost:1080/mockserver/reset
```

### Java ↔ REST API mapping

| Java client | REST API field |
|---|---|
| `openAPI(spec, "opId")` | `"httpRequest": { "specUrlOrPayload": $spec, "operationId": "opId" }` |
| `respond(template(MUSTACHE, ...))` | `"httpResponseTemplate": { "templateType": "MUSTACHE", "template": "..." }` |
| `respond(template(VELOCITY, ...))` | `"httpResponseTemplate": { "templateType": "VELOCITY", "template": "..." }` |
| `Times.once()` | `"times": { "remainingTimes": 1, "unlimited": false }` |
| `Times.unlimited()` | `"times": { "unlimited": true }` |
| `json(..., ONLY_MATCHING_FIELDS)` | `"body": { "type": "JSON", "json": "...", "matchType": "ONLY_MATCHING_FIELDS" }` |
| `when(..., priority)` | `"priority": <integer>` |
