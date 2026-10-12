package com.example.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.link.CodeGenerator;
import com.example.urlshortener.link.Idempotency;
import com.example.urlshortener.link.LinkResponse;
import com.example.urlshortener.link.LinkService;
import com.example.urlshortener.link.RandomCodeGenerator;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CoreApiIT {

  private static final Instant START = Instant.parse("2026-10-11T12:00:00.123456789Z");
  private static final JsonMapper JSON = new JsonMapper();
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.0-alpine")
          .withDatabaseName("urlshortener")
          .withUsername("bootstrap")
          .withPassword("test-only-bootstrap")
          .withEnv("DATABASE_PASSWORD", "test-only-runtime")
          .withEnv("DATABASE_MIGRATION_PASSWORD", "test-only-migration")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  Path.of("infra/postgres/init/10-roles.sh").toAbsolutePath().toString(), 0755),
              "/docker-entrypoint-initdb.d/10-roles.sh");
  private static ConfigurableApplicationContext context;
  private static String base;
  private static LinkService service;
  private static ControlledGenerator generator;
  private static MutableClock clock;
  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @BeforeAll
  static void start() throws Exception {
    POSTGRES.start();
    var flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), "urlshortener_migrator", "test-only-migration")
            .locations("classpath:db/migration")
            .defaultSchema("public")
            .cleanDisabled(true);
    assertThat(
            flyway
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("1"))
                .load()
                .migrate()
                .migrationsExecuted)
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='links' AND column_name='expires_at'"))
        .isZero();
    sql(
        "INSERT INTO links VALUES ('00000000-0000-0000-0000-000000000100','BrownOld','https://example.com/brownfield?a=1&a=2#fragment','2026-10-11T11:00:00.123456Z',NULL), ('00000000-0000-0000-0000-000000000101','BrownOff','https://example.com/disabled-baseline','2026-10-11T11:00:00.123456Z','2026-10-11T11:15:00.123456Z')");
    // Digests generated from the saved P2 algorithm, rather than the implementation under test.
    sql(
        "INSERT INTO idempotency_records VALUES ('f01460f84c9f7cfcfea60aa3d4787f40145d99dfec1ba583c6904a9f0bad4802','083878d81f8e48f8db39cb24a72c907910480901bff6d9b6d53ff5adb6ec1227','00000000-0000-0000-0000-000000000100','2026-10-11T11:00:00.123456Z')");
    assertThat(
            flyway
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("2"))
                .load()
                .migrate()
                .migrationsExecuted)
        .isEqualTo(1);
    assertThat(
            flyway
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("3"))
                .load()
                .migrate()
                .migrationsExecuted)
        .isEqualTo(1);
    context =
        new SpringApplication(UrlShortenerApplication.class, TestBeans.class)
            .run(
                "--server.port=0",
                "--app.analytics.cleanup-enabled=false",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=urlshortener_app",
                "--spring.datasource.password=test-only-runtime",
                "--app.public-base-url=https://short.example.com");
    base = "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    service = context.getBean(LinkService.class);
    generator = context.getBean(ControlledGenerator.class);
    clock = context.getBean(MutableClock.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void resetControls() {
    generator.behavior = generator.random::nextCode;
    clock.time = START;
  }

  @Test
  void fullHttpLifecycleIsNoStoreBodylessHeadAndIrreversible() throws Exception {
    String url = "https://example.com/path?a=1&a=2#fragment";
    var created = create(url, null);
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode link = JSON.readTree(created.body());
    String code = link.get("code").asString();
    assertThat(code).matches("[A-Za-z0-9]{8}");
    assertThat(link.get("shortUrl").asString()).isEqualTo("https://short.example.com/r/" + code);
    assertThat(link.get("createdAt").asString()).isEqualTo("2026-10-11T12:00:00.123456Z");
    assertThat(link.get("disabledAt").isNull()).isTrue();
    assertThat(link.get("expiresAt").isNull()).isTrue();
    assertThat(link.has("id")).isFalse();
    assertThat(send("GET", "/api/v1/links/" + code, null).body()).isEqualTo(created.body());
    for (String method : List.of("GET", "HEAD")) {
      var redirect = send(method, "/r/" + code, null);
      assertThat(redirect.statusCode()).isEqualTo(302);
      assertThat(redirect.headers().firstValue("Location")).contains(url);
      assertThat(redirect.body()).isEmpty();
      assertResponseHeaders(redirect);
    }
    clock.time = START.plusSeconds(1);
    var disabled = send("DELETE", "/api/v1/links/" + code, null);
    assertThat(disabled.statusCode()).isEqualTo(204);
    assertThat(disabled.body()).isEmpty();
    clock.time = START.plusSeconds(5);
    assertThat(send("DELETE", "/api/v1/links/" + code, null).statusCode()).isEqualTo(204);
    var metadata = JSON.readTree(send("GET", "/api/v1/links/" + code, null).body());
    assertThat(metadata.get("disabledAt").asString()).isEqualTo("2026-10-11T12:00:01.123456Z");
    assertError(send("GET", "/r/" + code, null), 410, "LINK_INACTIVE");
    var head = send("HEAD", "/r/" + code, null);
    assertThat(head.statusCode()).isEqualTo(410);
    assertThat(head.body()).isEmpty();
    assertThat(count("SELECT count(*) FROM links WHERE code='" + code + "'")).isEqualTo(1);
  }

  @Test
  void keyedReplayReturnsOriginalResultAfterDisableAndConflictsRollBack() throws Exception {
    String key = UUID.randomUUID().toString();
    var original = create("https://example.com/keyed", key);
    String code = JSON.readTree(original.body()).get("code").asString();
    send("DELETE", "/api/v1/links/" + code, null);
    var replay = create("https://example.com/keyed", key);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(original.body());
    long before = count("SELECT count(*) FROM links");
    assertError(create("https://example.com/different", key), 409, "IDEMPOTENCY_CONFLICT");
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
    assertError(send("GET", "/r/" + code, null), 410, "LINK_INACTIVE");
  }

  @Test
  void keyExpiresExactlyAt24HoursAndRetainsOriginalMapping() throws Exception {
    String key = Idempotency.keyDigest(List.of(UUID.randomUUID().toString()));
    LinkResponse original = service.create("https://example.com/expiry-window", key);
    Instant created = original.createdAt();
    clock.time = created.plusSeconds(86400).minusNanos(1000);
    assertThat(service.create(original.url(), key)).isEqualTo(original);
    clock.time = created.plusSeconds(86400);
    LinkResponse replacement = service.create("https://example.com/replaced-key", key);
    assertThat(replacement.code()).isNotEqualTo(original.code());
    assertThat(service.inspect(original.code()).url()).isEqualTo(original.url());
    assertThat(count("SELECT count(*) FROM idempotency_records WHERE key_digest='" + key + "'"))
        .isEqualTo(1);
  }

  @Test
  void concurrentExpiredKeyReplacementCreatesOnlyOneNewLink() throws Exception {
    String rawKey = UUID.randomUUID().toString();
    String digest = Idempotency.keyDigest(List.of(rawKey));
    var original = service.create("https://example.com/old-key", digest);
    clock.time = original.createdAt().plusSeconds(86400);
    long before = count("SELECT count(*) FROM links");
    CyclicBarrier barrier = new CyclicBarrier(4);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var requests =
          java.util.stream.IntStream.range(0, 4)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            await(barrier);
                            return create("https://example.com/new-key", rawKey);
                          }))
              .toList();
      String body = requests.getFirst().get(10, TimeUnit.SECONDS).body();
      for (var request : requests) {
        var response = request.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(body);
      }
    }
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before + 1);
    assertThat(service.inspect(original.code()).url()).isEqualTo(original.url());
  }

  @Test
  void forcedConcurrentCodeCollisionRetriesInFreshTransaction() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger calls = new AtomicInteger();
    generator.behavior =
        () -> {
          if (calls.getAndIncrement() < 2) {
            await(barrier);
            return "RaceCode";
          }
          return generator.random.nextCode();
        };
    long before = count("SELECT count(*) FROM links");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.create("https://example.com/race-a", null));
      var b = pool.submit(() -> service.create("https://example.com/race-b", null));
      assertThat(a.get(10, TimeUnit.SECONDS).code())
          .isNotEqualTo(b.get(10, TimeUnit.SECONDS).code());
    }
    assertThat(calls.get()).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before + 2);
  }

  @Test
  void forcedConcurrentKeyRaceRollsBackLosingCandidateAndReplaysWinner() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    generator.behavior =
        () -> {
          await(barrier);
          return generator.random.nextCode();
        };
    String key = UUID.randomUUID().toString();
    long before = count("SELECT count(*) FROM links");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> create("https://example.com/key-race", key));
      var b = pool.submit(() -> create("https://example.com/key-race", key));
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertThat(first.statusCode()).isEqualTo(201);
      assertThat(second.statusCode()).isEqualTo(201);
      assertThat(second.body()).isEqualTo(first.body());
    }
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before + 1);
  }

  @Test
  void conflictingConcurrentKeyRequestsLeaveOneWinnerAndNoOrphan() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    generator.behavior =
        () -> {
          await(barrier);
          return generator.random.nextCode();
        };
    String key = UUID.randomUUID().toString();
    long before = count("SELECT count(*) FROM links");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> create("https://example.com/key-conflict-a", key));
      var b = pool.submit(() -> create("https://example.com/key-conflict-b", key));
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertThat(List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(201, 409);
      assertError(first.statusCode() == 409 ? first : second, 409, "IDEMPOTENCY_CONFLICT");
    }
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before + 1);
  }

  @Test
  void fiveCollisionsAreBoundedAndDisabledCodesAreNeverReused() throws Exception {
    LinkResponse existing = service.create("https://example.com/retained", null);
    service.disable(existing.code());
    AtomicInteger calls = new AtomicInteger();
    generator.behavior =
        () -> {
          calls.incrementAndGet();
          return existing.code();
        };
    long before = count("SELECT count(*) FROM links");
    assertError(create("https://example.com/colliding", null), 503, "DEPENDENCY_UNAVAILABLE");
    assertThat(calls.get()).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
    assertThat(service.inspect(existing.code()).disabledAt()).isNotNull();
  }

  @Test
  void nonCodeDatabaseFailuresDoNotRetryAndKeyFailureRollsBackLink() throws Exception {
    long before = count("SELECT count(*) FROM links");
    AtomicInteger calls = new AtomicInteger();
    generator.behavior =
        () -> {
          calls.incrementAndGet();
          return generator.random.nextCode();
        };
    sql("REVOKE INSERT ON idempotency_records FROM urlshortener_app");
    try {
      assertError(
          create("https://example.com/no-key-permission", UUID.randomUUID().toString()),
          503,
          "DEPENDENCY_UNAVAILABLE");
      assertThat(calls.get()).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
    } finally {
      sql("GRANT INSERT ON idempotency_records TO urlshortener_app");
    }
    generator.behavior =
        () -> {
          calls.incrementAndGet();
          return "invalid!";
        };
    assertError(create("https://example.com/check-failure", null), 503, "DEPENDENCY_UNAVAILABLE");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
  }

  @Test
  void managementApisWorkWithoutCredentialsAndInvalidBodiesStillFailSafely() throws Exception {
    var created = create("https://example.com/open-management", null);
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.headers().firstValue("WWW-Authenticate")).isEmpty();
    String code = JSON.readTree(created.body()).get("code").asString();
    assertThat(send("GET", "/api/v1/links/" + code, null).statusCode()).isEqualTo(200);
    assertThat(send("GET", "/api/v1/links/" + code + "/stats", null).statusCode()).isEqualTo(200);
    assertThat(send("DELETE", "/api/v1/links/" + code, null).statusCode()).isEqualTo(204);
    assertError(send("GET", "/r/" + code, null), 410, "LINK_INACTIVE");
    long before = count("SELECT count(*) FROM links");
    assertError(send("POST", "/api/v1/links", "not-json"), 400, "INVALID_REQUEST");
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
  }

  @Test
  void crossOriginPreflightRemainsUnconfigured() throws Exception {
    var cors =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(cors.statusCode()).isEqualTo(200);
    assertThat(cors.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  @Test
  void strictJsonTypesDuplicateUnknownFieldsAndUtf8AreRejectedWithoutWrites() throws Exception {
    long before = count("SELECT count(*) FROM links");
    for (String body :
        List.of(
            "",
            "{",
            "[]",
            "null",
            "{}",
            "{\"url\":1}",
            "{\"url\":null}",
            "{\"url\":\"https://example.com\",\"url\":\"https://example.org\"}",
            "{\"url\":\"https://example.com\",\"unknown\":null}",
            "{\"url\":\"https://example.com\"} {}")) {
      assertError(send("POST", "/api/v1/links", body), 400, "INVALID_REQUEST");
    }
    assertError(create("https://user:secret@example.com/", null), 400, "INVALID_URL");
    byte[] malformed = {(byte) 0xc3, (byte) 0x28};
    var invalidUtf8 =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(malformed))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertError(invalidUtf8, 400, "INVALID_REQUEST");
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
  }

  @Test
  void mediaTypeAndDeclaredOrChunkedBodyBoundsAreEnforced() throws Exception {
    var media =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertError(media, 415, "UNSUPPORTED_MEDIA_TYPE");
    String valid = "{\"url\":\"https://example.com/body-limit\"}";
    assertThat(
            send("POST", "/api/v1/links", valid + " ".repeat(16384 - valid.length())).statusCode())
        .isEqualTo(201);
    long before = count("SELECT count(*) FROM links");
    assertError(send("POST", "/api/v1/links", "x".repeat(16385)), 413, "PAYLOAD_TOO_LARGE");
    var chunked =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () ->
                            new ByteArrayInputStream(
                                "x"
                                    .repeat(16385)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertError(chunked, 413, "PAYLOAD_TOO_LARGE");
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
  }

  @Test
  void malformedRepeatedKeysAndMissingOrUnsupportedRoutesAreControlled() throws Exception {
    for (String key : List.of("", "has space", "a,b", "x".repeat(129))) {
      assertError(create("https://example.com/header", key), 400, "INVALID_IDEMPOTENCY_KEY");
    }
    var repeated =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "first")
                .header("Idempotency-Key", "second")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertError(repeated, 400, "INVALID_IDEMPOTENCY_KEY");
    for (String path : List.of("/r/short", "/r/Abcd1234", "/r/Abcd1234/", "/r/Abcd1234/extra")) {
      assertError(send("GET", path, null), 404, "LINK_NOT_FOUND");
      assertThat(send("HEAD", path, null).body()).isEmpty();
    }
    for (String method : List.of("POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
      var response = send(method, "/r/Abcd1234", null);
      assertError(response, 405, "METHOD_NOT_ALLOWED");
      assertThat(response.headers().firstValue("Allow")).contains("GET, HEAD");
    }
    assertError(send("DELETE", "/api/v1/links/Abcd1234", null), 404, "LINK_NOT_FOUND");
    assertError(send("GET", "/api/v1/links/Abcd1234/stats", null), 404, "LINK_NOT_FOUND");
  }

  @Test
  void differentKeysAndUnkeyedDuplicatesRemainIndependent() throws Exception {
    var first = create("https://example.com/duplicates", null);
    var second = create("https://example.com/duplicates", null);
    assertThat(second.body()).isNotEqualTo(first.body());
    var a = create("https://example.com/duplicates", UUID.randomUUID().toString());
    var b = create("https://example.com/duplicates", UUID.randomUUID().toString());
    assertThat(b.body()).isNotEqualTo(a.body());
  }

  @Test
  void upgradedV1LinksAndKeyAreUnchangedAndUsable() throws Exception {
    assertThat(service.inspect("BrownOld").createdAt())
        .isEqualTo(Instant.parse("2026-10-11T11:00:00.123456Z"));
    assertThat(service.inspect("BrownOld").expiresAt()).isNull();
    assertThat(service.inspect("BrownOff").disabledAt())
        .isEqualTo(Instant.parse("2026-10-11T11:15:00.123456Z"));
    assertThat(service.inspect("BrownOff").expiresAt()).isNull();
    assertThat(
            count(
                "SELECT count(*) FROM links WHERE code='BrownOld' AND id='00000000-0000-0000-0000-000000000100'"))
        .isEqualTo(1);
    var original = send("GET", "/api/v1/links/BrownOld", null);
    var replay = create("https://example.com/brownfield?a=1&a=2#fragment", "brownfield-baseline");
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(original.body());
    for (String method : List.of("GET", "HEAD")) {
      assertThat(send(method, "/r/BrownOld", null).statusCode()).isEqualTo(302);
      assertThat(send(method, "/r/BrownOff", null).statusCode()).isEqualTo(410);
    }
  }

  @Test
  void getAndHeadExpireExactlyAtBoundaryAndReplayPrecedesFutureValidation() throws Exception {
    String url = "https://example.com/expiring";
    String key = UUID.randomUUID().toString();
    Instant expires = Instant.parse("2026-10-11T12:00:01.123456Z");
    var initial = createWithExpiry(url, key, expires.toString());
    assertThat(initial.statusCode()).isEqualTo(201);
    var link = JSON.readTree(initial.body());
    String code = link.get("code").asString();
    assertThat(link.get("expiresAt").asString()).isEqualTo(expires.toString());
    clock.time = expires.minusNanos(1);
    for (String method : List.of("GET", "HEAD")) {
      assertThat(send(method, "/r/" + code, null).statusCode()).isEqualTo(302);
    }
    clock.time = expires;
    assertError(send("GET", "/r/" + code, null), 410, "LINK_EXPIRED");
    var head = send("HEAD", "/r/" + code, null);
    assertThat(head.statusCode()).isEqualTo(410);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("Location")).isEmpty();
    assertThat(send("GET", "/api/v1/links/" + code, null).statusCode()).isEqualTo(200);
    assertThat(createWithExpiry(url, key, expires.toString()).body()).isEqualTo(initial.body());
    assertError(
        createWithExpiry(url, UUID.randomUUID().toString(), expires.toString()),
        400,
        "INVALID_EXPIRATION");
    clock.time = expires.plusSeconds(5);
    assertError(
        createWithExpiry(url, key, expires.plusSeconds(1).toString()), 409, "IDEMPOTENCY_CONFLICT");
    service.disable(code);
    assertError(send("GET", "/r/" + code, null), 410, "LINK_INACTIVE");
    assertThat(createWithExpiry(url, key, expires.toString()).body()).isEqualTo(initial.body());
    assertThat(count("SELECT count(*) FROM links WHERE code='" + code + "'")).isEqualTo(1);
  }

  @Test
  void equivalentOffsetExpiryReplaysAndMetadataIsUtcMicroseconds() throws Exception {
    String key = UUID.randomUUID().toString();
    String url = "https://example.com/offset-expiry";
    var first = createWithExpiry(url, key, "2026-10-11T08:00:00.123456-05:00");
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(first.body()).get("expiresAt").asString())
        .isEqualTo("2026-10-11T13:00:00.123456Z");
    var replay = createWithExpiry(url, key, "2026-10-11T18:30:00.123456+05:30");
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
  }

  @Test
  void invalidExpiryShapesAndPastTimesAreSafeAndLeaveNoRows() throws Exception {
    long before = count("SELECT count(*) FROM links");
    for (String literal :
        List.of(
            "1",
            "true",
            "{}",
            "[]",
            "\"2026-10-11T12:00:00.123456Z\"",
            "\"2026-10-10T12:00:00Z\"",
            "\"2026-10-12T12:00:00.1234567Z\"",
            "\"2026-10-12T12:00:00\"",
            "\"secret-malformed-time\"")) {
      var response =
          send(
              "POST",
              "/api/v1/links",
              "{\"url\":\"https://example.com/invalid-expiry\",\"expiresAt\":" + literal + "}");
      assertError(response, 400, "INVALID_EXPIRATION");
      assertThat(response.body()).doesNotContain("secret-malformed-time");
    }
    assertError(
        send(
            "POST",
            "/api/v1/links",
            "{\"url\":\"https://example.com\",\"expiresAt\":null,\"expiresAt\":null}"),
        400,
        "INVALID_REQUEST");
    assertThat(count("SELECT count(*) FROM links")).isEqualTo(before);
    assertThat(
            send(
                    "POST",
                    "/api/v1/links",
                    "{\"url\":\"https://example.com/null-expiry\",\"expiresAt\":null}")
                .statusCode())
        .isEqualTo(201);
  }

  @Test
  void shortLifetimeCanExpireBeforeCreationResponseWithoutRoundingAcrossBoundary()
      throws Exception {
    Instant expiry = Instant.parse("2026-10-11T12:00:00.123457Z");
    generator.behavior =
        () -> {
          clock.time = expiry;
          return generator.random.nextCode();
        };
    var response = createWithExpiry("https://example.com/short-lifetime", null, expiry.toString());
    assertThat(response.statusCode()).isEqualTo(201);
    String code = JSON.readTree(response.body()).get("code").asString();
    assertError(send("GET", "/r/" + code, null), 410, "LINK_EXPIRED");
  }

  private static HttpResponse<String> createWithExpiry(String url, String key, String expiry)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
            .header("Content-Type", "application/json");
    if (key != null) {
      builder.header("Idempotency-Key", key);
    }
    return HTTP.send(
        builder
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(java.util.Map.of("url", url, "expiresAt", expiry))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> create(String url, String key) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", "untrusted-correlation")
            .header("X-Forwarded-Host", "evil.example");
    if (key != null) {
      builder.header("Idempotency-Key", key);
    }
    return HTTP.send(
        builder
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(java.util.Map.of("url", url))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(String method, String path, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return HTTP.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertResponseHeaders(response);
    var error = JSON.readTree(response.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo(code);
    assertThat(error.get("requestId").asString())
        .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    assertThat(response.body())
        .doesNotContain("SQLException", "INSERT INTO", "test-only-", "secret@example.com");
  }

  private static void assertResponseHeaders(HttpResponse<?> response) {
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow()))
        .isNotNull();
  }

  private static long count(String query) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-only-bootstrap");
        var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static void sql(String query) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-only-bootstrap");
        var statement = connection.createStatement()) {
      statement.execute(query);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new IllegalStateException("Test race barrier failed.", failure);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    ControlledGenerator controlledGenerator() {
      return new ControlledGenerator();
    }
  }

  static class MutableClock extends Clock {
    volatile Instant time = START;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return time;
    }
  }

  static class ControlledGenerator implements CodeGenerator {
    final RandomCodeGenerator random = new RandomCodeGenerator();
    volatile Supplier<String> behavior = random::nextCode;

    @Override
    public String nextCode() {
      return behavior.get();
    }
  }
}
