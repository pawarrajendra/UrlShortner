package com.example.urlshortener.cache;

import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.link.Link;
import com.example.urlshortener.link.LinkRepository;
import com.example.urlshortener.link.LinkService.Resolution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
public class RedirectResolver {
  private final LinkRepository repository;
  private final RedirectCache cache;
  private final CacheSettings settings;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final Semaphore fallback, followers;
  private final ConcurrentHashMap<String, CompletableFuture<CachedRedirect>> flights =
      new ConcurrentHashMap<>();

  public RedirectResolver(
      LinkRepository repository,
      RedirectCache cache,
      CacheSettings settings,
      Clock clock,
      MeterRegistry metrics) {
    this.repository = repository;
    this.cache = cache;
    this.settings = settings;
    this.clock = clock;
    this.metrics = metrics;
    fallback = new Semaphore(settings.fallbackConcurrency());
    followers = new Semaphore(settings.coalescedConcurrency());
    Gauge.builder(
            "redirect.fallback.active",
            this,
            resolver -> settings.fallbackConcurrency() - resolver.fallback.availablePermits())
        .register(metrics);
    Gauge.builder(
            "redirect.coalesced.active",
            this,
            resolver -> settings.coalescedConcurrency() - resolver.followers.availablePermits())
        .register(metrics);
  }

  public Resolution resolve(String code) {
    if (!code.matches("[A-Za-z0-9]{8}")) {
      throw ApiException.notFound();
    }
    boolean cached = settings.enabled() && "cached".equals(settings.consistencyMode());
    if (cached) {
      var hit = cache.get(code);
      if (hit.isPresent()) {
        Instant observation = clock.instant();
        if (observation.isBefore(hit.get().freshUntil())) {
          return observed(hit.get(), observation);
        }
      }
      var pending = flights.get(code);
      if (pending != null) {
        return observedPrimary(join(pending));
      }
    }
    if (!fallback.tryAcquire()) {
      metrics.counter("redirect.fallback.rejected").increment();
      throw ApiException.unavailable();
    }
    var task = new CompletableFuture<CachedRedirect>();
    boolean owner = false;
    boolean permit = true;
    try {
      if (cached) {
        var other = flights.putIfAbsent(code, task);
        if (other != null) {
          fallback.release();
          permit = false;
          return observedPrimary(join(other));
        }
        owner = true;
      }
      metrics.counter("redirect.mapping.queries").increment();
      var read = repository.findForRedirect(code).orElseThrow(ApiException::notFound);
      Link link = read.link();
      if (link.disabledAt() != null) {
        throw new ApiException(410, "LINK_INACTIVE", "The link is disabled.");
      }
      Instant now = clock.instant();
      if (link.expiresAt() != null && !now.isBefore(link.expiresAt())) {
        throw new ApiException(410, "LINK_EXPIRED", "The link has expired.");
      }
      var value =
          new CachedRedirect(
              1,
              link.code(),
              link.id(),
              link.url(),
              link.expiresAt(),
              read.readAt(),
              CachedRedirect.deadline(read.readAt(), link.expiresAt()));
      if (cached) {
        cache.put(value);
      }
      task.complete(value);
      return cached
          ? observedPrimary(value)
          : new Resolution(link.id(), link.url(), now.truncatedTo(ChronoUnit.MICROS));
    } catch (RuntimeException failure) {
      task.completeExceptionally(failure);
      throw failure;
    } finally {
      if (owner) {
        flights.remove(code, task);
      }
      // A race loser releases before joining; it never owned a map entry.
      if (permit) {
        fallback.release();
      }
    }
  }

  private CachedRedirect join(CompletableFuture<CachedRedirect> task) {
    if (!followers.tryAcquire()) {
      metrics.counter("redirect.coalesced.rejected").increment();
      throw ApiException.unavailable();
    }
    try {
      return task.get(settings.coalescedWait().toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw ApiException.unavailable();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw ApiException.unavailable();
    } catch (java.util.concurrent.TimeoutException timeout) {
      metrics.counter("redirect.coalesced.timeout").increment();
      throw ApiException.unavailable();
    } finally {
      followers.release();
    }
  }

  private Resolution observedPrimary(CachedRedirect value) {
    Instant now = clock.instant();
    if (value.expiresAt() != null && !now.isBefore(value.expiresAt())) {
      throw new ApiException(410, "LINK_EXPIRED", "The link has expired.");
    }
    // Redis milliseconds may expire a cache entry up to 1 ms early. A fresh SQL result
    // still follows the exact application expiry boundary; never extend the primary-read window.
    if (!now.isBefore(value.readAt().plusSeconds(60))) {
      throw ApiException.unavailable();
    }
    return new Resolution(value.linkId(), value.url(), now.truncatedTo(ChronoUnit.MICROS));
  }

  private Resolution observed(CachedRedirect value, Instant now) {
    if (value.expiresAt() != null && !now.isBefore(value.expiresAt())) {
      throw new ApiException(410, "LINK_EXPIRED", "The link has expired.");
    }
    if (!now.isBefore(value.freshUntil())) {
      throw ApiException.unavailable();
    }
    return new Resolution(value.linkId(), value.url(), now.truncatedTo(ChronoUnit.MICROS));
  }

  public void invalidate(String code) {
    cache.invalidate(code);
  }
}
