package com.example.urlshortener.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.urlshortener.config.WebSettings;
import com.example.urlshortener.link.DestinationPolicy;
import com.example.urlshortener.link.Link;
import com.example.urlshortener.link.LinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RedirectResolverTest {
  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

  static CacheSettings settings(boolean enabled, String mode) {
    return new CacheSettings(
        enabled,
        mode,
        "localhost",
        6379,
        Duration.ofMillis(100),
        Duration.ofSeconds(2),
        3,
        1,
        1,
        Duration.ofMillis(100));
  }

  static Link link() {
    return new Link(
        UUID.randomUUID(), "TestCode", "https://example.com", NOW.minusSeconds(1), null, null);
  }

  @Test
  void freshHitsAvoidSqlAndStrictAlwaysReadsPrimary() {
    var repo = mock(LinkRepository.class);
    var cache = mock(RedirectCache.class);
    var link = link();
    when(cache.get("TestCode"))
        .thenReturn(
            Optional.of(
                new CachedRedirect(
                    1,
                    link.code(),
                    link.id(),
                    link.url(),
                    null,
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(59))));
    var resolver =
        new RedirectResolver(
            repo,
            cache,
            settings(true, "cached"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry());
    assertThat(resolver.resolve("TestCode").url()).isEqualTo(link.url());
    verifyNoInteractions(repo);
    when(repo.findForRedirect("TestCode"))
        .thenReturn(
            Optional.of(
                new LinkRepository.PrimaryRead(
                    new Link(link.id(), link.code(), link.url(), link.createdAt(), NOW, null),
                    NOW)));
    assertThatThrownBy(
            () ->
                new RedirectResolver(
                        repo,
                        cache,
                        settings(true, "strict"),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new SimpleMeterRegistry())
                    .resolve("TestCode"))
        .hasMessage("The link is disabled.");
    verify(cache, times(1)).get("TestCode");
  }

  @Test
  void cachedResolutionCapturesOneObservationAtTheFreshnessBoundary() {
    var repo = mock(LinkRepository.class);
    var cache = mock(RedirectCache.class);
    var link = link();
    when(cache.get("TestCode"))
        .thenReturn(
            Optional.of(
                new CachedRedirect(
                    1, link.code(), link.id(), link.url(), null, NOW, NOW.plusSeconds(60))));
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(NOW.plusSeconds(59), NOW.plusSeconds(61));
    var result =
        new RedirectResolver(
                repo, cache, settings(true, "cached"), clock, new SimpleMeterRegistry())
            .resolve("TestCode");
    assertThat(result.observedAt()).isEqualTo(NOW.plusSeconds(59));
    verify(clock, times(1)).instant();
    verifyNoInteractions(repo);
  }

  @Test
  void expiredHitNeverServesDuringPrimaryFailure() {
    var repo = mock(LinkRepository.class);
    var cache = mock(RedirectCache.class);
    var link = link();
    when(cache.get("TestCode"))
        .thenReturn(
            Optional.of(
                new CachedRedirect(
                    1, link.code(), link.id(), link.url(), null, NOW.minusSeconds(60), NOW)));
    when(repo.findForRedirect("TestCode"))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
    assertThatThrownBy(
            () ->
                new RedirectResolver(
                        repo,
                        cache,
                        settings(true, "cached"),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new SimpleMeterRegistry())
                    .resolve("TestCode"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void codecRejectsUnknownSchemaWrongIdentityAndBadDeadline() {
    var codec =
        new CacheCodec(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DestinationPolicy(new WebSettings("https://short.example", "unit")));
    var value =
        new CachedRedirect(
            1,
            "TestCode",
            UUID.randomUUID(),
            "https://example.com",
            null,
            NOW,
            NOW.plusSeconds(60));
    String raw = codec.encode(value);
    assertThat(codec.decode("TestCode", raw)).isEqualTo(value);
    for (String corrupt :
        List.of(
            raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("TestCode", "WrongCod"),
            raw.replace("12:01:00Z", "12:02:00Z"),
            "{",
            raw + "{}")) {
      assertThatThrownBy(() -> codec.decode("TestCode", corrupt))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void deadlinesNeverRoundPastExpiry() {
    assertThat(CachedRedirect.deadline(NOW, NOW.plusNanos(123456789)))
        .isEqualTo(NOW.plusMillis(123));
    assertThat(CachedRedirect.deadline(NOW, null)).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void freshSqlResolutionKeepsExactExpiryAfterRedisMillisecondRounding() {
    var repo = mock(LinkRepository.class);
    var link = link();
    Instant expiry = NOW.plusNanos(123456000);
    when(repo.findForRedirect("TestCode"))
        .thenReturn(
            Optional.of(
                new LinkRepository.PrimaryRead(
                    new Link(link.id(), link.code(), link.url(), link.createdAt(), null, expiry),
                    NOW)));
    var resolver =
        new RedirectResolver(
            repo,
            new NoOpRedirectCache(),
            settings(true, "cached"),
            Clock.fixed(expiry.minusNanos(1), ZoneOffset.UTC),
            new SimpleMeterRegistry());
    assertThat(resolver.resolve("TestCode").url()).isEqualTo(link.url());
    when(repo.findForRedirect("TestCode"))
        .thenReturn(Optional.of(new LinkRepository.PrimaryRead(link, NOW.minusSeconds(61))));
    assertThatThrownBy(() -> resolver.resolve("TestCode"))
        .hasMessage("The service is temporarily unavailable.");
  }

  @Test
  void fallbackAndFollowersAreBoundedAndPermitIsReleasedAfterFailure() throws Exception {
    var repo = mock(LinkRepository.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var link = link();
    when(repo.findForRedirect(anyString()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
              return Optional.of(new LinkRepository.PrimaryRead(link, NOW));
            });
    var metrics = new SimpleMeterRegistry();
    var resolver =
        new RedirectResolver(
            repo,
            new NoOpRedirectCache(),
            settings(true, "cached"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            metrics);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> resolver.resolve("TestCode"));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> resolver.resolve("OtherCod"))
          .hasMessage("The service is temporarily unavailable.");
      assertThatThrownBy(() -> resolver.resolve("TestCode"))
          .hasMessage("The service is temporarily unavailable.");
      release.countDown();
      assertThat(first.get(3, TimeUnit.SECONDS).url()).isEqualTo(link.url());
    } finally {
      release.countDown();
    }
    assertThat(metrics.get("redirect.fallback.active").gauge().value()).isZero();
    assertThat(resolver.resolve("TestCode").url()).isEqualTo(link.url());
  }
}
