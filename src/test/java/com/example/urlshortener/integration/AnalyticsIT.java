package com.example.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.analytics.ClickEvent;
import com.example.urlshortener.analytics.EventRecorder;
import com.example.urlshortener.analytics.RetentionCleanup;
import com.example.urlshortener.analytics.TransactionalEventRecorder;
import com.example.urlshortener.link.LinkService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

class AnalyticsIT {

  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
  private static final JsonMapper JSON = new JsonMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.0-alpine")
          .withDatabaseName("urlshortener")
          .withUsername("bootstrap")
          .withPassword("test-bootstrap")
          .withEnv("DATABASE_PASSWORD", "test-runtime")
          .withEnv("DATABASE_MIGRATION_PASSWORD", "test-migration")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  Path.of("infra/postgres/init/10-roles.sh").toAbsolutePath().toString(), 0755),
              "/docker-entrypoint-initdb.d/10-roles.sh");
  private static ConfigurableApplicationContext context;
  private static String base;
  private static MutableClock clock;
  private static GatedRecorder gate;
  private static LinkService links;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), "urlshortener_migrator", "test-migration")
        .load()
        .migrate();
    context =
        new SpringApplication(UrlShortenerApplication.class, TestBeans.class).run(arguments());
    base = "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    clock = context.getBean(MutableClock.class);
    gate = context.getBean(GatedRecorder.class);
    links = context.getBean(LinkService.class);
  }

  static String[] arguments() {
    return new String[] {
      "--server.port=0",
      "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
      "--spring.datasource.username=urlshortener_app",
      "--spring.datasource.password=test-runtime",
      "--app.analytics.workers=1",
      "--app.analytics.queue-capacity=1",
      "--app.analytics.cleanup-enabled=false",
      "--app.analytics.cleanup-batch-size=2"
    };
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      gate.release.countDown();
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    gate.release.countDown();
    poll(
        () ->
            context.getBean(MeterRegistry.class).get("analytics.workers.active").gauge().value()
                    == 0
                && context.getBean(MeterRegistry.class).get("analytics.queue.depth").gauge().value()
                    == 0);
    gate.entered = new CountDownLatch(1);
    gate.release = new CountDownLatch(0);
    gate.fail = false;
    clock.time = NOW;
    sql("DELETE FROM redirect_events;DELETE FROM idempotency_records;");
  }

  @Test
  void pausedWriterProvesNonblockingRedirectLagAndGetOnlyCounts() throws Exception {
    var link = links.create("https://example.com/analytics-lag", null);
    String code = link.code();
    gate.release = new CountDownLatch(1);
    assertThat(send("GET", "/r/" + code).statusCode()).isEqualTo(302);
    assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
    assertThat(send("HEAD", "/r/" + code).statusCode()).isEqualTo(302);
    assertThat(send("GET", "/r/NoSuch00").statusCode()).isEqualTo(404);
    assertThat(send("POST", "/r/" + code).statusCode()).isEqualTo(405);
    assertThat(stats(code).get("observedRedirects").asLong()).isZero();
    assertThat(send("GET", "/api/v1/links/" + code + "/stats").statusCode()).isEqualTo(200);
    clock.time = NOW.plusSeconds(1);
    gate.release.countDown();
    poll(() -> total(code) == 1);
    JsonNode result = stats(code);
    assertThat(result.get("recordingMode").asString()).isEqualTo("best-effort-async");
    assertThat(result.get("eventuallyConsistent").asBoolean()).isTrue();
    assertThat(result.get("daily").size()).isEqualTo(7);
    sql("UPDATE links SET disabled_at='2026-10-11T12:00:00Z' WHERE code='" + code + "'");
    assertThat(send("GET", "/r/" + code).statusCode()).isEqualTo(410);
    assertThat(send("HEAD", "/r/" + code).body()).isEmpty();
    assertThat(total(code)).isEqualTo(1);
  }

  @Test
  void expiryAndLookupFailuresDoNotSubmit() throws Exception {
    var link = links.create("https://example.com/expires", NOW.plusSeconds(1), null);
    clock.time = NOW.plusSeconds(1);
    double before = counter("analytics.events.accepted");
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(410);
    assertThat(send("HEAD", "/r/" + link.code()).statusCode()).isEqualTo(410);
    sql("ALTER TABLE links RENAME TO links_unavailable");
    try {
      assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(503);
    } finally {
      sql("ALTER TABLE links_unavailable RENAME TO links");
    }
    assertThat(counter("analytics.events.accepted")).isEqualTo(before);
  }

  @Test
  void saturationAndInsertFailurePreserveValidRedirects() throws Exception {
    var link = links.create("https://example.com/saturation", null);
    gate.release = new CountDownLatch(1);
    double before = loss("queue_full");
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(loss("queue_full")).isEqualTo(before + 1);
    gate.fail = true;
    gate.release.countDown();
    poll(
        () ->
            context.getBean(MeterRegistry.class).get("analytics.workers.active").gauge().value()
                == 0);
    assertThat(count("SELECT count(*) FROM redirect_events")).isZero();
    assertThat(
            context
                .getBean(MeterRegistry.class)
                .get("analytics.events.failure")
                .tag("outcome", "write_failed")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void singleUtcQueryHasHalfOpenBoundariesAndZeroFilledFutureDays() throws Exception {
    clock.time = Instant.parse("2026-09-01T00:00:00Z");
    var link = links.create("https://example.com/utc", null);
    clock.time = Instant.parse("2026-10-12T12:00:00Z");
    for (String timestamp :
        List.of(
            "2026-10-10T23:59:59.999999Z",
            "2026-10-11T00:00:00Z",
            "2026-10-12T01:00:00+01:00",
            "2026-10-12T12:00:00Z",
            "2026-10-13T00:00:00Z")) {
      insert(link.code(), timestamp);
    }
    JsonNode result =
        JSON.readTree(
            send("GET", "/api/v1/links/" + link.code() + "/stats?from=2026-10-11&to=2026-10-14")
                .body());
    assertThat(result.get("observedRedirects").asLong()).isEqualTo(2);
    assertThat(result.get("daily").get(0).get("observedRedirects").asLong()).isEqualTo(1);
    assertThat(result.get("daily").get(1).get("observedRedirects").asLong()).isEqualTo(1);
    assertThat(result.get("daily").get(2).get("observedRedirects").asLong()).isZero();
    assertThat(result.get("partialRetention").asBoolean()).isFalse();
    assertThat(result.get("asOf").asString()).isEqualTo("2026-10-12T12:00:00Z");
  }

  @Test
  void retentionQueryAndBoundedCleanupKeepExactCutoffAndLiveKeys() throws Exception {
    clock.time = Instant.parse("2026-08-01T00:00:00Z");
    var link = links.create("https://example.com/retention", null);
    clock.time = NOW.plusNanos(999); // Match stats microsecond precision at cleanup cutoffs.
    for (String timestamp :
        List.of(
            "2026-09-11T11:59:59.999999Z", "2026-09-11T12:00:00Z", "2026-09-11T12:00:00.000001Z")) {
      insert(link.code(), timestamp);
    }
    for (int i = 1; i <= 3; i++) {
      insert(link.code(), "2026-09-10T00:00:00Z");
    }
    for (int i = 1; i <= 4; i++) {
      String digest = String.format("%064d", i);
      sql(
          "INSERT INTO idempotency_records SELECT '"
              + digest
              + "',repeat('a',64),id,'"
              + (i == 4 ? "2026-10-10T12:00:00Z" : "2026-10-10T11:59:59.999999Z")
              + "' FROM links WHERE code='"
              + link.code()
              + "'");
    }
    JsonNode result =
        JSON.readTree(
            send("GET", "/api/v1/links/" + link.code() + "/stats?from=2026-09-11&to=2026-10-11")
                .body());
    assertThat(result.get("observedRedirects").asLong()).isEqualTo(2);
    assertThat(result.get("availableFrom").asString()).isEqualTo("2026-09-11T12:00:00Z");
    assertThat(result.get("partialRetention").asBoolean()).isTrue();
    context.getBean(RetentionCleanup.class).runOnce();
    assertThat(count("SELECT count(*) FROM redirect_events")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM idempotency_records")).isEqualTo(2);
    context.getBean(RetentionCleanup.class).runOnce();
    assertThat(count("SELECT count(*) FROM redirect_events")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM idempotency_records")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM idempotency_records WHERE key_digest='"
                    + String.format("%064d", 4)
                    + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM links WHERE code='" + link.code() + "'")).isEqualTo(1);
  }

  @Test
  void staleCleanupCannotRemoveConcurrentlyRecreatedKey() throws Exception {
    clock.time = NOW.minus(Duration.ofDays(2));
    var link = links.create("https://example.com/cleanup-race", null);
    String digest = "a".repeat(64);
    sql(
        "INSERT INTO idempotency_records SELECT '"
            + digest
            + "',repeat('b',64),id,'2026-10-09T12:00:00Z' FROM links WHERE code='"
            + link.code()
            + "'");
    clock.time = NOW;
    try (var lock =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-bootstrap")) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("DELETE FROM idempotency_records WHERE key_digest='" + digest + "'");
        statement.execute(
            "INSERT INTO idempotency_records SELECT '"
                + digest
                + "',repeat('c',64),id,'2026-10-11T12:00:00Z' FROM links WHERE code='"
                + link.code()
                + "'");
      }
      var done = new CountDownLatch(1);
      Thread worker =
          new Thread(
              () -> {
                context.getBean(RetentionCleanup.class).runOnce();
                done.countDown();
              });
      worker.start();
      poll(
          () ->
              count(
                      "SELECT count(*) FROM pg_stat_activity WHERE usename='urlshortener_app' AND wait_event_type='Lock'")
                  > 0);
      lock.commit();
      assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            count(
                "SELECT count(*) FROM idempotency_records WHERE key_digest='"
                    + digest
                    + "' AND request_fingerprint=repeat('c',64)"))
        .isEqualTo(1);
  }

  @Test
  void cleanupFailureIsIsolatedAndStatsValidationIsProtected() throws Exception {
    var link = links.create("https://example.com/ranges", null);
    for (String query :
        List.of(
            "?from=2026-10-01",
            "?from=2026-10-01&to=2026-11-01",
            "?from=2026-10-01&from=2026-10-02&to=2026-10-03",
            "?x=1",
            "?from=2026-10-11&to=2026-10-11",
            "?from=2026-02-30&to=2026-03-02")) {
      var response = send("GET", "/api/v1/links/" + link.code() + "/stats" + query);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.body()).contains("INVALID_DATE_RANGE");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    sql("ALTER TABLE redirect_events RENAME TO redirect_events_unavailable");
    try {
      context.getBean(RetentionCleanup.class).runOnce();
      assertThat(send("GET", "/api/v1/links/" + link.code() + "/stats").statusCode())
          .isEqualTo(503);
      assertThat(send("HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    } finally {
      sql("ALTER TABLE redirect_events_unavailable RENAME TO redirect_events");
    }
  }

  @Test
  void eventSchemaPermissionsAndDuplicateUuidAreEnforced() throws Exception {
    var link = links.create("https://example.com/event-permissions", null);
    insert(link.code(), "2026-10-11T11:00:00Z");
    try (var connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "urlshortener_app", "test-runtime");
        var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("UPDATE redirect_events SET occurred_at=now()"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () -> statement.execute("ALTER TABLE redirect_events ADD COLUMN forbidden text"))
          .hasMessageContaining("owner");
      assertThatThrownBy(
              () -> statement.execute("INSERT INTO redirect_events SELECT * FROM redirect_events"))
          .hasMessageContaining("pk_redirect_events");
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO redirect_events VALUES ('"
                          + UUID.randomUUID()
                          + "','"
                          + UUID.randomUUID()
                          + "',now())"))
          .hasMessageContaining("fk_redirect_event_link");
    }
  }

  @Test
  void committedEventsSurviveFreshApplicationContext() throws Exception {
    var link = links.create("https://example.com/persisted", null);
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    clock.time = NOW.plusSeconds(1);
    poll(() -> total(link.code()) == 1);
    try (var restarted =
        new SpringApplication(UrlShortenerApplication.class, TestBeans.class).run(arguments())) {
      restarted.getBean(MutableClock.class).time = NOW.plusSeconds(2);
      assertThat(
              restarted
                  .getBean(com.example.urlshortener.analytics.StatsService.class)
                  .stats(link.code(), java.util.Map.of())
                  .observedRedirects())
          .isEqualTo(1);
    }
  }

  @Test
  void actualHttpDrainAllowsInflightPublicationBeforeDatasourceClose() throws Exception {
    var link = links.create("https://example.com/http-shutdown", null);
    var draining =
        new SpringApplication(UrlShortenerApplication.class, TestBeans.class, DrainBeans.class)
            .run(arguments());
    var requestGate = draining.getBean(RequestGate.class);
    var dataSource = draining.getBean(com.zaxxer.hikari.HikariDataSource.class);
    var closed = new CountDownLatch(1);
    try {
      int port = ((WebServerApplicationContext) draining).getWebServer().getPort();
      var response =
          HTTP.sendAsync(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/r/" + link.code()))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(requestGate.entered.await(3, TimeUnit.SECONDS)).isTrue();
      Thread close =
          new Thread(
              () -> {
                draining.close();
                closed.countDown();
              });
      close.start();
      poll(
          () ->
              !draining
                  .getBean(
                      org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle
                          .class)
                  .isRunning());
      assertThat(dataSource.isClosed()).isFalse();
      requestGate.release.countDown();
      assertThat(response.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(dataSource.isClosed()).isTrue();
      assertThat(count("SELECT count(*) FROM redirect_events")).isEqualTo(1);
    } finally {
      requestGate.release.countDown();
      if (closed.getCount() != 0) {
        draining.close();
      }
    }
  }

  @Test
  void delayedWriterKeepsObservationOnOriginalUtcDay() throws Exception {
    clock.time = Instant.parse("2026-10-10T23:59:59.999999Z");
    var link = links.create("https://example.com/observed-day", null);
    gate.release = new CountDownLatch(1);
    assertThat(send("GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
    clock.time = Instant.parse("2026-10-11T00:00:01Z");
    sql("UPDATE links SET disabled_at='2026-10-11T00:00:00Z' WHERE code='" + link.code() + "'");
    gate.release.countDown();
    poll(() -> count("SELECT count(*) FROM redirect_events") == 1);
    var result =
        JSON.readTree(
            send("GET", "/api/v1/links/" + link.code() + "/stats?from=2026-10-10&to=2026-10-12")
                .body());
    assertThat(result.get("daily").get(0).get("observedRedirects").asLong()).isEqualTo(1);
    assertThat(result.get("daily").get(1).get("observedRedirects").asLong()).isZero();
  }

  @Test
  void hardKilledProcessLosesAcceptedMemoryEvents() throws Exception {
    var link = links.create("https://example.com/hard-stop", null);
    Path directory = Files.createTempDirectory("phase4-crash-");
    Path ready = directory.resolve("ready");
    Path entered = directory.resolve("entered");
    Path log = directory.resolve("process.log");
    Process child = null;
    try {
      var command = new java.util.ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
      command.add("-cp");
      command.add(
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
      command.add(CrashAnalyticsProcess.class.getName());
      command.add(ready.toString());
      command.add(entered.toString());
      command.addAll(List.of(arguments()));
      child =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      Process running = child;
      poll(() -> Files.exists(ready) || !running.isAlive(), Duration.ofSeconds(30));
      assertThat(child.isAlive()).as("child startup; log %s", log).isTrue();
      String childBase = "http://localhost:" + Files.readString(ready);
      assertThat(request(childBase, "GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
      poll(() -> Files.exists(entered));
      assertThat(request(childBase, "GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
      assertThat(count("SELECT count(*) FROM redirect_events")).isZero();
      child.destroyForcibly();
      assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(count("SELECT count(*) FROM redirect_events")).isZero();
      assertThat(send("HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
      assertThat(total(link.code())).isZero();
      System.out.println(
          "PASS: hard-killed child after one active and one queued accepted GET; both pending events lost, link retained.");
    } finally {
      if (child != null && child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
      for (Path file : List.of(ready, entered, log)) {
        Files.deleteIfExists(file);
      }
      Files.deleteIfExists(directory);
    }
  }

  static double counter(String name) {
    var counter = context.getBean(MeterRegistry.class).find(name).counter();
    return counter == null ? 0 : counter.count();
  }

  static double loss(String reason) {
    var counter =
        context
            .getBean(MeterRegistry.class)
            .find("analytics.events.lost")
            .tag("reason", reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  static JsonNode stats(String code) throws Exception {
    var response = send("GET", "/api/v1/links/" + code + "/stats");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  static long total(String code) {
    try {
      return stats(code).get("observedRedirects").asLong();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static HttpResponse<String> send(String method, String path) throws Exception {
    return request(base, method, path);
  }

  static HttpResponse<String> request(String origin, String method, String path) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(3))
            .method(method, HttpRequest.BodyPublishers.noBody());
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  static void insert(String code, String timestamp) throws Exception {
    sql(
        "INSERT INTO redirect_events SELECT '"
            + UUID.randomUUID()
            + "',id,'"
            + timestamp
            + "' FROM links WHERE code='"
            + code
            + "'");
  }

  static void sql(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-bootstrap");
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static long count(String sql) {
    try (var connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-bootstrap");
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static void poll(BooleanSupplier condition) throws Exception {
    poll(condition, Duration.ofSeconds(5));
  }

  static void poll(BooleanSupplier condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Bounded polling timed out");
      }
      Thread.sleep(10);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {
    @Bean
    @Primary
    MutableClock controlledClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    GatedRecorder controlledRecorder(TransactionalEventRecorder recorder) {
      return new GatedRecorder(recorder);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DrainBeans {
    @Bean
    RequestGate requestGate() {
      return new RequestGate();
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<RequestGate>
        requestGateRegistration(RequestGate gate) {
      var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(gate);
      registration.addUrlPatterns("/r/*");
      return registration;
    }
  }

  static class RequestGate implements jakarta.servlet.Filter {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request,
        jakarta.servlet.ServletResponse response,
        jakarta.servlet.FilterChain chain)
        throws java.io.IOException, jakarta.servlet.ServletException {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new jakarta.servlet.ServletException("Test request gate timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new jakarta.servlet.ServletException(interrupted);
      }
      chain.doFilter(request, response);
    }
  }

  static class MutableClock extends Clock {
    volatile Instant time = NOW;

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

  static class GatedRecorder implements EventRecorder {
    final TransactionalEventRecorder recorder;
    volatile CountDownLatch entered = new CountDownLatch(1);
    volatile CountDownLatch release = new CountDownLatch(0);
    volatile boolean fail;

    GatedRecorder(TransactionalEventRecorder recorder) {
      this.recorder = recorder;
    }

    @Override
    public void record(ClickEvent event) {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test gate timeout");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
      recorder.record(
          fail ? new ClickEvent(event.id(), UUID.randomUUID(), event.occurredAt()) : event);
    }
  }
}
