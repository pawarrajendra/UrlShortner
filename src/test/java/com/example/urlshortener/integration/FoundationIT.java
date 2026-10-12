package com.example.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.UrlShortenerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class FoundationIT {
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

  @BeforeAll
  static void startRequiredDatabase() {
    // Deliberately mandatory: absent Docker fails verification, never silently skips it.
    POSTGRES.start();
    migrate().migrate();
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  private static Flyway migrate() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), "urlshortener_migrator", "test-only-migration")
        .locations("classpath:db/migration")
        .defaultSchema("public")
        .cleanDisabled(true)
        .load();
  }

  private Connection runtime() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), "urlshortener_app", "test-only-runtime");
  }

  private String freshDatabase() throws SQLException {
    String name = "foundation_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bootstrap", "test-only-bootstrap");
        var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + name + " OWNER urlshortener_migrator");
    }
    String url = POSTGRES.getJdbcUrl().replace("/urlshortener", "/" + name);
    try (Connection admin = DriverManager.getConnection(url, "bootstrap", "test-only-bootstrap");
        var statement = admin.createStatement()) {
      statement.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
      statement.execute(
          "GRANT CONNECT ON DATABASE " + name + " TO urlshortener_app,urlshortener_migrator");
      statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
      statement.execute("GRANT USAGE ON SCHEMA public TO urlshortener_app");
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE urlshortener_migrator IN SCHEMA public GRANT SELECT ON TABLES TO urlshortener_app");
    }
    return url;
  }

  @Test
  void actualMigrationConstraintsAndUtcRoundTrip() throws Exception {
    UUID id = UUID.randomUUID();
    OffsetDateTime instant = OffsetDateTime.parse("2026-10-11T00:15:00.123456-05:00");
    try (Connection connection = runtime();
        var insert =
            connection.prepareStatement(
                "INSERT INTO links (id,code,destination_url,created_at) VALUES (?,?,'https://example.com',?)")) {
      insert.setObject(1, id);
      insert.setString(2, "Ab3dE9xQ");
      insert.setObject(3, instant);
      insert.executeUpdate();
      assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
      try (var select = connection.prepareStatement("SELECT created_at FROM links WHERE id = ?")) {
        select.setObject(1, id);
        try (var rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1, OffsetDateTime.class).toInstant())
              .isEqualTo(instant.toInstant());
        }
      }
      insert.setObject(1, UUID.randomUUID());
      insert.setString(2, "invalid!");
      assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
    }
    assertThat(migrate().migrate().migrationsExecuted).isZero();
    migrate().validate();
  }

  @Test
  void foreignKeysAndLeastPrivilegeAreEnforced() throws Exception {
    try (Connection connection = runtime();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO idempotency_records VALUES ('"
                          + "a".repeat(64)
                          + "','"
                          + "b".repeat(64)
                          + "','"
                          + UUID.randomUUID()
                          + "',CURRENT_TIMESTAMP)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute("CREATE TABLE forbidden(id int)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute("DELETE FROM links"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute("UPDATE flyway_schema_history SET success=false"))
          .isInstanceOf(SQLException.class);
      try (var rows =
          statement.executeQuery(
              "SELECT column_name FROM information_schema.columns WHERE table_name='links' AND column_name='expires_at'")) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  @Test
  void healthAliasesExposeOnlyStatusAndUnconfiguredRoutesAreNotFound() throws Exception {
    SpringApplication application = new SpringApplication(UrlShortenerApplication.class);
    try (ConfigurableApplicationContext context =
        application.run(
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=urlshortener_app",
            "--spring.datasource.password=test-only-runtime")) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      HttpClient client = HttpClient.newHttpClient();
      for (String route : new String[] {"/health/live", "/health/ready"}) {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
      }
      HttpResponse<String> denied =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/not-a-route"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(denied.statusCode()).isEqualTo(404);
    }
  }

  @Test
  void migrationProfileIsNonWebAndClosesOnSuccessfulExecution() {
    SpringApplication application = new SpringApplication(UrlShortenerApplication.class);
    try (ConfigurableApplicationContext context =
        application.run(
            "--spring.profiles.active=migrate",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=urlshortener_migrator",
            "--spring.datasource.password=test-only-migration")) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    }
  }

  @Test
  void packagedMigrationJobExitsSuccessfullyForFreshDatabaseAndRerun() throws Exception {
    String url = freshDatabase();
    assertThat(migrationProcess(url).exitValue()).isZero();
    assertThat(migrationProcess(url).exitValue()).isZero();
    try (Connection connection =
            DriverManager.getConnection(url, "urlshortener_app", "test-only-runtime");
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version='1'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void packagedMigrationJobFailsNonzeroForInvalidHistoryWithoutLeakingCredentials()
      throws Exception {
    String url = freshDatabase();
    assertThat(migrationProcess(url).exitValue()).isZero();
    try (Connection admin = DriverManager.getConnection(url, "bootstrap", "test-only-bootstrap");
        var statement = admin.createStatement()) {
      statement.execute("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
    }
    Process process = migrationProcess(url);
    assertThat(process.exitValue()).isNotZero();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(output)
        .doesNotContain("test-only-migration", "test-only-bootstrap", "test-only-runtime");
    assertThat(output).contains("Startup failed");
  }

  private Process migrationProcess(String url) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            "target/urlshortener-0.1.0-SNAPSHOT.jar");
    builder.redirectErrorStream(true);
    builder.environment().put("SPRING_PROFILES_ACTIVE", "migrate");
    builder.environment().put("DATABASE_URL", url);
    builder.environment().put("DATABASE_MIGRATION_USERNAME", "urlshortener_migrator");
    builder.environment().put("DATABASE_MIGRATION_PASSWORD", "test-only-migration");
    Process process = builder.start();
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    assertThat(finished).as("Migration subprocess must exit within timeout").isTrue();
    return process;
  }

  @Test
  void missingAndIncompatibleSchemaKeepReadinessDownWhileLivenessIsUp() throws Exception {
    String url = freshDatabase();
    SpringApplication application = new SpringApplication(UrlShortenerApplication.class);
    try (ConfigurableApplicationContext context =
        application.run(
            "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=urlshortener_app",
            "--spring.datasource.password=test-only-runtime")) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      HttpClient client = HttpClient.newHttpClient();
      assertHealth(client, port, "live", 200, "UP");
      assertHealth(client, port, "ready", 503, "DOWN");
      assertThat(migrationProcess(url).exitValue()).isZero();
      assertHealth(client, port, "ready", 200, "UP");
      try (Connection admin = DriverManager.getConnection(url, "bootstrap", "test-only-bootstrap");
          var statement = admin.createStatement()) {
        statement.execute("UPDATE flyway_schema_history SET version='4' WHERE version='3'");
      }
      assertHealth(client, port, "ready", 503, "DOWN");
      assertHealth(client, port, "live", 200, "UP");
    }
  }

  @Test
  void v1OnlyDatabaseIsUnreadyUntilV2AndRejectsInvalidExpiryAtDatabaseBoundary() throws Exception {
    String url = freshDatabase();
    Flyway.configure()
        .dataSource(url, "urlshortener_migrator", "test-only-migration")
        .locations("classpath:db/migration")
        .defaultSchema("public")
        .target("1")
        .load()
        .migrate();
    try (var app =
        new SpringApplication(UrlShortenerApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=urlshortener_app",
                "--spring.datasource.password=test-only-runtime")) {
      int port = ((WebServerApplicationContext) app).getWebServer().getPort();
      HttpClient client = HttpClient.newHttpClient();
      assertHealth(client, port, "live", 200, "UP");
      assertHealth(client, port, "ready", 503, "DOWN");
      assertThat(migrationProcess(url).exitValue()).isZero();
      assertHealth(client, port, "ready", 200, "UP");
      try (Connection connection =
              DriverManager.getConnection(url, "urlshortener_app", "test-only-runtime");
          var statement = connection.createStatement()) {
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "INSERT INTO links(id,code,destination_url,created_at,expires_at) VALUES ('"
                            + UUID.randomUUID()
                            + "','BadExp00','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  private void assertHealth(HttpClient client, int port, String route, int status, String body)
      throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health/" + route))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).isEqualTo("{\"status\":\"" + body + "\"}");
  }
}
