package com.example.urlshortener.analytics;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!migrate")
public class RetentionCleanup {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AnalyticsSettings settings;
  private final MeterRegistry metrics;
  private final TransactionTemplate transaction;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "analytics-retention");
            thread.setDaemon(true);
            return thread;
          });

  public RetentionCleanup(
      JdbcTemplate jdbc,
      Clock clock,
      AnalyticsSettings settings,
      MeterRegistry metrics,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.settings = settings;
    this.metrics = metrics;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void start() {
    if (settings.cleanupEnabled()) {
      long delay = settings.cleanupInterval().toMillis();
      scheduler.scheduleWithFixedDelay(this::runOnce, delay, delay, TimeUnit.MILLISECONDS);
    }
  }

  /** Each run performs at most one bounded batch per table in separate short transactions. */
  public void runOnce() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    clean(
        "events",
        "DELETE FROM redirect_events e USING (SELECT id,occurred_at FROM redirect_events WHERE occurred_at<? ORDER BY occurred_at,id LIMIT ?) old WHERE e.id=old.id AND e.occurred_at=old.occurred_at AND e.occurred_at<?",
        now.minus(Duration.ofDays(30)));
    clean(
        "keys",
        "DELETE FROM idempotency_records k USING (SELECT key_digest,created_at FROM idempotency_records WHERE created_at<? ORDER BY created_at,key_digest LIMIT ?) old WHERE k.key_digest=old.key_digest AND k.created_at=old.created_at AND k.created_at<?",
        now.minus(Duration.ofHours(24)));
  }

  private void clean(String kind, String sql, Instant cutoff) {
    try {
      Integer removed =
          transaction.execute(
              status ->
                  jdbc.update(
                      sql,
                      cutoff.atOffset(ZoneOffset.UTC),
                      settings.cleanupBatchSize(),
                      cutoff.atOffset(ZoneOffset.UTC)));
      metrics
          .counter("analytics.cleanup.deleted", "table", kind)
          .increment(removed == null ? 0 : removed);
    } catch (RuntimeException failure) {
      metrics.counter("analytics.cleanup.failure", "table", kind).increment();
    }
  }

  public void stop(long deadlineNanos) {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(
          Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      scheduler.shutdownNow();
    }
  }
}
