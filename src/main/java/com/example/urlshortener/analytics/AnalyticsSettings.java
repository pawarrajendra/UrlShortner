package com.example.urlshortener.analytics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.analytics")
public record AnalyticsSettings(
    int workers,
    int queueCapacity,
    Duration maxQueueWait,
    boolean cleanupEnabled,
    int cleanupBatchSize,
    Duration cleanupInterval) {
  public AnalyticsSettings {
    if (workers < 1
        || workers > 8
        || queueCapacity < 1
        || queueCapacity > 100000
        || maxQueueWait == null
        || maxQueueWait.isNegative()
        || maxQueueWait.isZero()
        || maxQueueWait.compareTo(Duration.ofMinutes(1)) > 0
        || cleanupBatchSize < 1
        || cleanupBatchSize > 10000
        || cleanupInterval == null
        || cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0
        || cleanupInterval.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException("Invalid analytics settings.");
    }
  }
}
