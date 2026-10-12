package com.example.urlshortener.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.cache")
public record CacheSettings(
    boolean enabled,
    String consistencyMode,
    String redisHost,
    int redisPort,
    Duration timeout,
    Duration breakerCooldown,
    int breakerFailures,
    int fallbackConcurrency,
    int coalescedConcurrency,
    Duration coalescedWait) {
  public CacheSettings {
    if (!("cached".equals(consistencyMode) || "strict".equals(consistencyMode))
        || redisHost == null
        || !redisHost.matches("[A-Za-z0-9.:-]{1,253}")
        || redisPort < 1
        || redisPort > 65535
        || timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofMillis(100)) > 0
        || breakerCooldown == null
        || breakerCooldown.compareTo(Duration.ofMillis(100)) < 0
        || breakerCooldown.compareTo(Duration.ofSeconds(30)) > 0
        || breakerFailures < 1
        || breakerFailures > 10
        || fallbackConcurrency < 1
        || fallbackConcurrency > 8
        || coalescedConcurrency < 1
        || coalescedConcurrency > 128
        || coalescedWait == null
        || coalescedWait.isNegative()
        || coalescedWait.isZero()
        || coalescedWait.compareTo(Duration.ofSeconds(1)) > 0) {
      throw new IllegalArgumentException("Invalid redirect cache settings.");
    }
  }
}
