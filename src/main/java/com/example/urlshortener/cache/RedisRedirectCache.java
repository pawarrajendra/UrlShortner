package com.example.urlshortener.cache;

import com.example.urlshortener.analytics.NanoClock;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Every operation fails open; disconnected command buffering is disabled and bounded. */
public class RedisRedirectCache implements RedirectCache, AutoCloseable {
  private final RedisClient client;
  private final DefaultClientResources resources;
  private final RedisURI uri;
  private final CacheSettings settings;
  private final CacheCodec codec;
  private final Clock clock;
  private final NanoClock ticker;
  private final MeterRegistry metrics;
  private volatile StatefulRedisConnection<String, String> connection;
  private final AtomicBoolean connecting = new AtomicBoolean(), probe = new AtomicBoolean();
  private final AtomicInteger failures = new AtomicInteger();
  private final AtomicLong openUntil = new AtomicLong();

  public RedisRedirectCache(
      CacheSettings settings,
      CacheCodec codec,
      Clock clock,
      NanoClock ticker,
      MeterRegistry metrics) {
    this.settings = settings;
    this.codec = codec;
    this.clock = clock;
    this.ticker = ticker;
    this.metrics = metrics;
    uri =
        RedisURI.Builder.redis(settings.redisHost(), settings.redisPort())
            .withTimeout(settings.timeout())
            .build();
    resources =
        DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    client = RedisClient.create(resources, uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(true)
            .requestQueueSize(64)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(settings.timeout()).build())
            .build());
  }

  public static String key(String code) {
    return "redirect:v1:" + code;
  }

  private <T> T await(Future<T> future) throws Exception {
    try {
      return future.get(settings.timeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (Exception failure) {
      future.cancel(true);
      throw failure;
    }
  }

  private StatefulRedisConnection<String, String> connection() throws Exception {
    var existing = connection;
    if (existing != null && existing.isOpen()) {
      return existing;
    }
    if (!connecting.compareAndSet(false, true)) {
      throw new IllegalStateException("Redis connect already in progress.");
    }
    AtomicBoolean abandoned = new AtomicBoolean();
    var future = client.connectAsync(StringCodec.UTF8, uri);
    future.whenComplete(
        (created, failure) -> {
          if (created != null && abandoned.get()) {
            created.closeAsync();
          }
        });
    try {
      connection = await(future);
      return connection;
    } catch (Exception failure) {
      abandoned.set(true);
      future.whenComplete(
          (created, error) -> {
            if (created != null) {
              created.closeAsync();
            }
          });
      throw failure;
    } finally {
      connecting.set(false);
    }
  }

  private <T> T call(String operation, Supplier<T> action, T fallback) {
    long until = openUntil.get();
    boolean halfOpen = until != 0;
    if (halfOpen && (ticker.nanoTime() < until || !probe.compareAndSet(false, true))) {
      metrics.counter("redirect.cache.bypass", "reason", "breaker").increment();
      return fallback;
    }
    long start = ticker.nanoTime();
    try {
      T result = action.get();
      failures.set(0);
      openUntil.set(0);
      return result;
    } catch (RuntimeException failure) {
      metrics.counter("redirect.cache.failure", "operation", operation).increment();
      if (halfOpen || failures.incrementAndGet() >= settings.breakerFailures()) {
        openUntil.set(ticker.nanoTime() + settings.breakerCooldown().toNanos());
      }
      return fallback;
    } finally {
      if (halfOpen) {
        probe.set(false);
      }
      metrics
          .timer("redirect.cache.duration", "operation", operation)
          .record(Math.max(0, ticker.nanoTime() - start), TimeUnit.NANOSECONDS);
    }
  }

  @Override
  public Optional<CachedRedirect> get(String code) {
    return call(
        "get",
        () -> {
          String raw;
          try {
            raw = await(connection().async().get(key(code)));
          } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Redis read failed.", failure);
          }
          if (raw == null) {
            metrics.counter("redirect.cache.miss").increment();
            return Optional.empty();
          }
          try {
            var value = codec.decode(code, raw);
            if (!clock.instant().isBefore(value.freshUntil())) {
              metrics.counter("redirect.cache.stale").increment();
              return Optional.empty();
            }
            metrics.counter("redirect.cache.hit").increment();
            return Optional.of(value);
          } catch (RuntimeException corrupt) {
            metrics.counter("redirect.cache.corrupt").increment();
            return Optional.empty();
          }
        },
        Optional.empty());
  }

  @Override
  public void put(CachedRedirect value) {
    if (!clock.instant().isBefore(value.freshUntil())) {
      metrics.counter("redirect.cache.fill.expired").increment();
      return;
    }
    call(
        "put",
        () -> {
          try {
            await(
                connection()
                    .async()
                    .set(
                        key(value.code()),
                        codec.encode(value),
                        new SetArgs().pxAt(value.freshUntil().toEpochMilli())));
            return true;
          } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Redis write failed.", failure);
          }
        },
        false);
  }

  @Override
  public void invalidate(String code) {
    call(
        "invalidate",
        () -> {
          try {
            await(connection().async().del(key(code)));
            return true;
          } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Redis invalidation failed.", failure);
          }
        },
        false);
  }

  @Override
  public void close() {
    if (connection != null) {
      connection.close();
    }
    client.shutdown(java.time.Duration.ZERO, java.time.Duration.ofMillis(100));
    resources.shutdown(0, 100, TimeUnit.MILLISECONDS);
  }
}
