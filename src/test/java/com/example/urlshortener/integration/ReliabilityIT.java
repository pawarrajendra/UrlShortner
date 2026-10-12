package com.example.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.link.LinkService;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

class ReliabilityIT {
  static final PostgreSQLContainer PG =
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
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.2.1-alpine").withExposedPorts(6379);
  static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  static ConfigurableApplicationContext cached, strict;

  @BeforeAll
  static void start() {
    PG.start();
    REDIS.start();
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), "urlshortener_migrator", "test-migration")
        .load()
        .migrate();
    cached = app(true, false, false);
    strict = app(false, false, false);
  }

  static ConfigurableApplicationContext app(boolean cache, boolean cleanup, boolean measure) {
    return new SpringApplication(UrlShortenerApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + PG.getJdbcUrl(),
            "--spring.datasource.username=urlshortener_app",
            "--spring.datasource.password=test-runtime",
            "--app.cache.enabled=" + cache,
            "--app.cache.redis-host=" + REDIS.getHost(),
            "--app.cache.redis-port=" + REDIS.getMappedPort(6379),
            "--app.analytics.cleanup-enabled=" + cleanup,
            "--app.analytics.cleanup-interval=1s",
            "--app.analytics.cleanup-batch-size=2",
            "--spring.profiles.active=" + (measure ? "measure" : "default"),
            "--management.server.port=" + (measure ? "0" : ""),
            "--management.endpoints.web.exposure.include="
                + (measure ? "health,measurement" : "health"));
  }

  @AfterAll
  static void stop() {
    if (cached != null) {
      cached.close();
    }
    if (strict != null) {
      strict.close();
    }
    REDIS.stop();
    PG.stop();
  }

  @Test
  void exhaustedRealPoolReturnsBoundedErrorAndRecovers() throws Exception {
    var link = strict.getBean(LinkService.class).create("https://example.com/pool", null);
    var held = new ArrayList<Connection>();
    try {
      var pool = strict.getBean(HikariDataSource.class);
      for (int i = 0; i < 8; i++) {
        held.add(pool.getConnection());
      }
      long began = System.nanoTime();
      assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(503);
      assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(4));
    } finally {
      for (Connection connection : held) {
        connection.close();
      }
    }
    assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
  }

  @Test
  void heldPostgresRowMakesDisableFailWithinLockBudgetWithoutChangingState() throws Exception {
    var link = strict.getBean(LinkService.class).create("https://example.com/lock", null);
    try (Connection connection = admin()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("SELECT id FROM links WHERE code='" + link.code() + "' FOR UPDATE");
        long began = System.nanoTime();
        var response = send(strict, "DELETE", "/api/v1/links/" + link.code());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body())
            .contains("DEPENDENCY_UNAVAILABLE")
            .doesNotContain("SQLException", "test-runtime");
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(4));
      } finally {
        connection.rollback();
      }
    }
    assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
  }

  @Test
  void actualSlowQueryIsCancelledAndConnectionRemainsUsable() {
    var jdbc = strict.getBean(JdbcTemplate.class);
    long began = System.nanoTime();
    assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(5)", String.class))
        .isInstanceOf(DataAccessException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(4));
    assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  @Test
  void pausedDatabaseNetworkSeparatesFreshCachedReadsFromPrimaryFailures() throws Exception {
    var link = cached.getBean(LinkService.class).create("https://example.com/network", null);
    poll(
        () -> {
          try {
            send(cached, "HEAD", "/r/" + link.code());
            return "1"
                .equals(
                    REDIS
                        .execInContainer("redis-cli", "EXISTS", "redirect:v1:" + link.code())
                        .getStdout()
                        .trim());
          } catch (Exception error) {
            return false;
          }
        });
    DockerClientFactory.instance().client().pauseContainerCmd(PG.getContainerId()).exec();
    try {
      assertThat(send(cached, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
      long began = System.nanoTime();
      assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(503);
      assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(12));
      assertThat(send(cached, "GET", "/health/ready").statusCode()).isEqualTo(503);
      assertThat(send(cached, "GET", "/health/live").statusCode()).isEqualTo(200);
    } finally {
      DockerClientFactory.instance().client().unpauseContainerCmd(PG.getContainerId()).exec();
    }
    poll(
        () -> {
          try {
            return send(strict, "HEAD", "/r/" + link.code()).statusCode() == 302;
          } catch (Exception error) {
            return false;
          }
        });
  }

  @Test
  void cleanupOwnerReturnsAndDrainsBoundedBacklogWhileObserverEnforcesRetention() throws Exception {
    var previousOwner = app(false, true, false);
    previousOwner.close();
    var link = strict.getBean(LinkService.class).create("https://example.com/cleanup-owner", null);
    try (var connection = admin();
        var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO redirect_events SELECT gen_random_uuid(),'"
              + strict
                  .getBean(JdbcTemplate.class)
                  .queryForObject("SELECT id FROM links WHERE code=?", UUID.class, link.code())
              + "',CURRENT_TIMESTAMP-INTERVAL '31 days' FROM generate_series(1,5)");
    }
    assertThat(
            strict
                .getBean(JdbcTemplate.class)
                .queryForObject(
                    "SELECT count(*) FROM redirect_events WHERE occurred_at<CURRENT_TIMESTAMP-INTERVAL '30 days'",
                    Long.class))
        .isEqualTo(5);
    var stats = send(strict, "GET", "/api/v1/links/" + link.code() + "/stats");
    assertThat(
            JsonMapper.builder().build().readTree(stats.body()).get("observedRedirects").asLong())
        .isZero();
    try (var owner = app(false, true, false)) {
      poll(
          () ->
              strict
                      .getBean(JdbcTemplate.class)
                      .queryForObject(
                          "SELECT count(*) FROM redirect_events WHERE occurred_at<CURRENT_TIMESTAMP-INTERVAL '30 days'",
                          Long.class)
                  == 0);
      assertThat(send(owner, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    }
  }

  @Test
  void measurementProfileUsesSeparatePortAndExportsServerPercentileWithoutSecrets()
      throws Exception {
    try (var context = app(true, false, true)) {
      var link = context.getBean(LinkService.class).create("https://example.com/measurement", null);
      assertThat(send(context, "GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
      var serverTimer =
          context
              .getBean(io.micrometer.core.instrument.MeterRegistry.class)
              .get("redirect.server.duration")
              .timer();
      assertThat(serverTimer.count()).isEqualTo(1);
      assertThat(send(context, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
      assertThat(send(context, "GET", "/r/not-a-code").statusCode()).isEqualTo(404);
      assertThat(serverTimer.count()).isEqualTo(2);
      assertThat(send(context, "GET", "/actuator/measurement").statusCode()).isEqualTo(404);
      int port = context.getEnvironment().getProperty("local.management.port", Integer.class);
      var response =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/actuator/measurement"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body())
          .contains("p95Seconds", "http.server.requests", "redirect.server.duration")
          .doesNotContain("test-runtime", "test-bootstrap", "jdbc:postgresql");
    }
    assertThat(send(strict, "GET", "/actuator/measurement").statusCode()).isEqualTo(404);
  }

  static Connection admin() throws Exception {
    return DriverManager.getConnection(PG.getJdbcUrl(), "bootstrap", "test-bootstrap");
  }

  static HttpResponse<String> send(
      ConfigurableApplicationContext context, String method, String path) throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static void poll(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).isLessThan(deadline);
      Thread.sleep(50);
    }
  }
}
