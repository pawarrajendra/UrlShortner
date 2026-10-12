package com.example.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.cache.CachedRedirect;
import com.example.urlshortener.cache.RedisRedirectCache;
import com.example.urlshortener.link.LinkRepository;
import com.example.urlshortener.link.LinkResponse;
import com.example.urlshortener.link.LinkService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

class CacheIT {
  private static final PostgreSQLContainer PG =
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
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.2.1-alpine")
          .withCommand("redis-server", "--save", "", "--appendonly", "no")
          .withExposedPorts(6379);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private static ConfigurableApplicationContext one, two, strict;
  private static PausedRepository repository;

  @BeforeAll
  static void start() throws Exception {
    try (var port = new java.net.ServerSocket(0)) {
      REDIS.setPortBindings(java.util.List.of("127.0.0.1:" + port.getLocalPort() + ":6379"));
    }
    PG.start();
    REDIS.start();
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), "urlshortener_migrator", "test-migration")
        .load()
        .migrate();
    one = app("app-1", "cached", true);
    two = app("app-2", "cached", false);
    strict = app("strict-test", "strict", false);
    repository =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
            one.getBean(PausedRepository.class));
  }

  static ConfigurableApplicationContext app(String instance, String mode, boolean controlled) {
    var application =
        controlled
            ? new SpringApplication(UrlShortenerApplication.class, TestBeans.class)
            : new SpringApplication(UrlShortenerApplication.class, ClockBeans.class);
    return application.run(
        "--server.port=0",
        "--spring.datasource.url=" + PG.getJdbcUrl(),
        "--spring.datasource.username=urlshortener_app",
        "--spring.datasource.password=test-runtime",
        "--app.instance-id=" + instance,
        "--app.analytics.cleanup-enabled=false",
        "--app.cache.enabled=true",
        "--app.cache.consistency-mode=" + mode,
        "--app.cache.redis-host=" + REDIS.getHost(),
        "--app.cache.redis-port=" + REDIS.getMappedPort(6379));
  }

  @AfterAll
  static void stop() {
    if (one != null) {
      repository.release.countDown();
      one.close();
    }
    if (two != null) {
      two.close();
    }
    if (strict != null) {
      strict.close();
    }
    REDIS.stop();
    PG.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    repository.pauseCode = null;
    repository.release = new CountDownLatch(0);
    repository.entered = new CountDownLatch(1);
    for (var context : new ConfigurableApplicationContext[] {one, two, strict}) {
      context.getBean(AdjustableClock.class).offset = Duration.ZERO;
    }
    redis("ACL", "SETUSER", "default", "+del");
    redis("FLUSHDB");
  }

  static LinkResponse create() {
    return one.getBean(LinkService.class).create("https://example.com/cache-test", null);
  }

  @Test
  void mappingAndEventuallyCommittedStatsAreSharedAcrossApps() throws Exception {
    var link = create();
    warm(link.code());
    double before = queries(two);
    assertThat(send(one, "GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(send(two, "GET", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(queries(two)).isEqualTo(before);
    assertThat(send(two, "HEAD", "/r/" + link.code()).headers().firstValue("X-App-Instance"))
        .contains("app-2");
    poll(() -> stats(one, link.code()) == 2 && stats(two, link.code()) == 2);
    long deadline = Long.parseLong(redis("PEXPIRETIME", RedisRedirectCache.key(link.code())));
    for (int i = 0; i < 3; i++) {
      assertThat(send(two, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    }
    assertThat(Long.parseLong(redis("PEXPIRETIME", RedisRedirectCache.key(link.code()))))
        .isEqualTo(deadline);
  }

  @Test
  void corruptEvictedAndRestartedRedisValuesRefillFromPrimary() throws Exception {
    var link = create();
    String key = RedisRedirectCache.key(link.code());
    warm(link.code());
    redis("SET", key, "{bad-json");
    double before = queries(two);
    assertThat(send(two, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(queries(two)).isEqualTo(before + 1);
    redis("DEL", key);
    assertThat(send(one, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    DockerClientFactory.instance().client().restartContainerCmd(REDIS.getContainerId()).exec();
    poll(
        () -> {
          try {
            return "PONG".equals(redis("PING"));
          } catch (Exception unavailable) {
            return false;
          }
        });
    assertThat(redis("DBSIZE")).isEqualTo("0");
    assertThat(send(two, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
  }

  @Test
  void strictModeIgnoresWarmCacheAndDisableCommitsBeforeInvalidation() throws Exception {
    var link = create();
    warm(link.code());
    String stale = redis("GET", RedisRedirectCache.key(link.code()));
    assertThat(send(two, "DELETE", "/api/v1/links/" + link.code()).statusCode()).isEqualTo(204);
    assertThat(redis("EXISTS", RedisRedirectCache.key(link.code()))).isEqualTo("0");
    redis("SET", RedisRedirectCache.key(link.code()), stale);
    assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(410);
    assertThat(send(one, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
  }

  @Test
  void delayedOldReadAfterDisableKeepsOriginalDeadlineAndConverges() throws Exception {
    var link = create();
    repository.pauseCode = link.code();
    repository.release = new CountDownLatch(1);
    one.getBean(AdjustableClock.class).offset = Duration.ofSeconds(58);
    two.getBean(AdjustableClock.class).offset = Duration.ofSeconds(58);
    try (var worker = Executors.newSingleThreadExecutor()) {
      var pending = worker.submit(() -> send(one, "HEAD", "/r/" + link.code()));
      assertThat(repository.entered.await(3, TimeUnit.SECONDS)).isTrue();
      var read = repository.last;
      assertThat(send(two, "DELETE", "/api/v1/links/" + link.code()).statusCode()).isEqualTo(204);
      repository.release.countDown();
      assertThat(pending.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
      long actual = Long.parseLong(redis("PEXPIRETIME", RedisRedirectCache.key(link.code())));
      assertThat(actual).isEqualTo(CachedRedirect.deadline(read.readAt(), null).toEpochMilli());
      assertThat(send(two, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
      poll(() -> status(two, link.code()) == 410);
    } finally {
      repository.release.countDown();
    }
  }

  @Test
  void failedInvalidationPreservesDisableAndOnlyOriginalFreshness() throws Exception {
    var link = create();
    warm(link.code());
    String key = RedisRedirectCache.key(link.code());
    long original = Long.parseLong(redis("PEXPIRETIME", key));
    redis("ACL", "SETUSER", "default", "-del");
    one.getBean(AdjustableClock.class).offset = Duration.ofSeconds(58);
    two.getBean(AdjustableClock.class).offset = Duration.ofSeconds(58);
    assertThat(send(one, "DELETE", "/api/v1/links/" + link.code()).statusCode()).isEqualTo(204);
    assertThat(redis("EXISTS", key)).isEqualTo("1");
    assertThat(Long.parseLong(redis("PEXPIRETIME", key))).isEqualTo(original);
    assertThat(send(two, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(send(strict, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(410);
    poll(() -> status(two, link.code()) == 410);
  }

  @Test
  void expiryCapsAbsoluteTtlAndExpiredRequestsDoNotUseCache() throws Exception {
    Instant expiry = Instant.now().plusMillis(1500);
    var link =
        one.getBean(LinkService.class).create("https://example.com/cache-expiry", expiry, null);
    assertThat(send(one, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
    assertThat(Long.parseLong(redis("PEXPIRETIME", RedisRedirectCache.key(link.code()))))
        .isEqualTo(expiry.toEpochMilli());
    poll(() -> status(two, link.code()) == 410);
  }

  @Test
  void redisPauseOpensBreakerAndBoundedDatabaseFallbackRecovers() throws Exception {
    var link = create();
    warm(link.code());
    DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      for (int i = 0; i < 4; i++) {
        assertThat(send(one, "HEAD", "/r/" + link.code()).statusCode()).isEqualTo(302);
      }
      var meter = one.getBean(MeterRegistry.class);
      assertThat(meter.get("redirect.cache.bypass").tag("reason", "breaker").counter().count())
          .isGreaterThan(0);
    } finally {
      DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
    poll(
        () -> {
          try {
            warm(link.code());
            return "1".equals(redis("EXISTS", RedisRedirectCache.key(link.code())));
          } catch (Exception failure) {
            return false;
          }
        });
  }

  @Test
  void sameKeyMissesCoalesceWhileDifferentKeyFallbackIsBounded() throws Exception {
    var link = create();
    repository.pauseCode = link.code();
    repository.release = new CountDownLatch(1);
    double before = queries(one);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> send(one, "HEAD", "/r/" + link.code()));
      assertThat(repository.entered.await(3, TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(() -> send(one, "HEAD", "/r/" + link.code()));
      poll(
          () ->
              one.getBean(MeterRegistry.class).get("redirect.coalesced.active").gauge().value()
                  == 1);
      repository.release.countDown();
      assertThat(first.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
      assertThat(second.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
      assertThat(queries(one)).isEqualTo(before + 1);
    } finally {
      repository.release.countDown();
    }
  }

  static void warm(String code) throws Exception {
    poll(
        () -> {
          try {
            send(one, "HEAD", "/r/" + code);
            return "1".equals(redis("EXISTS", RedisRedirectCache.key(code)));
          } catch (Exception failure) {
            return false;
          }
        });
  }

  static String redis(String... args) throws Exception {
    var command = new String[args.length + 1];
    command[0] = "redis-cli";
    System.arraycopy(args, 0, command, 1, args.length);
    var result = REDIS.execInContainer(command);
    assertThat(result.getExitCode()).isZero();
    return result.getStdout().trim();
  }

  static double queries(ConfigurableApplicationContext context) {
    var meter = context.getBean(MeterRegistry.class).find("redirect.mapping.queries").counter();
    return meter == null ? 0 : meter.count();
  }

  static long stats(ConfigurableApplicationContext context, String code) {
    try {
      var response = send(context, "GET", "/api/v1/links/" + code + "/stats");
      assertThat(response.statusCode()).isEqualTo(200);
      return new JsonMapper().readTree(response.body()).get("observedRedirects").asLong();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static int status(ConfigurableApplicationContext context, String code) {
    try {
      return send(context, "HEAD", "/r/" + code).statusCode();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static HttpResponse<String> send(
      ConfigurableApplicationContext context, String method, String route) throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
            .timeout(Duration.ofSeconds(4))
            .method(method, HttpRequest.BodyPublishers.noBody());
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  static void poll(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Bounded polling timed out");
      }
      Thread.sleep(10);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockBeans {
    @Bean
    @Primary
    AdjustableClock adjustableClock() {
      return new AdjustableClock();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans extends ClockBeans {
    @Bean
    @Primary
    PausedRepository pausedRepository(JdbcTemplate jdbc) {
      return new PausedRepository(jdbc);
    }
  }

  static class AdjustableClock extends Clock {
    volatile Duration offset = Duration.ZERO;

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
      return Instant.now().plus(offset);
    }
  }

  static class PausedRepository extends LinkRepository {
    volatile String pauseCode;
    volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
    volatile PrimaryRead last;

    PausedRepository(JdbcTemplate jdbc) {
      super(jdbc);
    }

    @Override
    public Optional<PrimaryRead> findForRedirect(String code) {
      var result = super.findForRedirect(code);
      if (code.equals(pauseCode)) {
        last = result.orElseThrow();
        entered.countDown();
        try {
          if (!release.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test read gate timeout");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
      return result;
    }
  }
}
