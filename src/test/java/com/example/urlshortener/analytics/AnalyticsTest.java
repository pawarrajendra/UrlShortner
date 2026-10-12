package com.example.urlshortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class AnalyticsTest {
  static AnalyticsSettings settings() {
    return new AnalyticsSettings(1, 1, Duration.ofSeconds(5), false, 2, Duration.ofHours(1));
  }

  static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @Test
  void saturationDoesNotRunOnCallerAndShutdownRejectsAdmission() {
    var metrics = new SimpleMeterRegistry();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var names = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var publisher =
        new ClickEventPublisher(
            settings(),
            event -> {
              names.add(Thread.currentThread().getName());
              entered.countDown();
              await(release);
            },
            System::nanoTime,
            metrics);
    try {
      publisher.publish(UUID.randomUUID(), Instant.now());
      await(entered);
      publisher.publish(UUID.randomUUID(), Instant.now());
      publisher.publish(UUID.randomUUID(), Instant.now());
      assertThat(metrics.get("analytics.events.lost").tag("reason", "queue_full").counter().count())
          .isEqualTo(1);
      assertThat(metrics.get("analytics.queue.depth").gauge().value()).isEqualTo(1);
      publisher.stopAdmission();
      publisher.publish(UUID.randomUUID(), Instant.now());
      assertThat(
              metrics
                  .get("analytics.events.lost")
                  .tag("reason", "admission_closed")
                  .counter()
                  .count())
          .isEqualTo(1);
    } finally {
      release.countDown();
      publisher.drain(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
    }
    assertThat(names).allMatch(name -> name.startsWith("analytics-writer-"));
    assertThat(publisher.terminated()).isTrue();
    assertThat(metrics.get("analytics.events.committed").counter().count()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(longs = {5000000000L, 5000000001L})
  void ageUsesMonotonicElapsedTimeAndExactLimitIsAccepted(long wait) {
    var metrics = new SimpleMeterRegistry();
    var ticker = new AtomicLong();
    var count = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var publisher =
        new ClickEventPublisher(
            settings(),
            event -> {
              if (count.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
              }
            },
            ticker::get,
            metrics);
    try {
      publisher.publish(UUID.randomUUID(), Instant.parse("2026-10-11T00:00:00Z"));
      await(entered);
      publisher.publish(UUID.randomUUID(), Instant.parse("1999-01-01T00:00:00Z"));
      ticker.set(wait);
    } finally {
      release.countDown();
      publisher.drain(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
    }
    assertThat(count.get()).isEqualTo(wait == 5000000000L ? 2 : 1);
    if (wait > 5000000000L) {
      assertThat(metrics.get("analytics.events.lost").tag("reason", "queue_age").counter().count())
          .isEqualTo(1);
    }
  }

  @Test
  void writerAndUnknownCommitFailuresAreMeteredWithoutRetry() {
    var metrics = new SimpleMeterRegistry();
    var count = new AtomicInteger();
    var publisher =
        new ClickEventPublisher(
            new AnalyticsSettings(1, 10, Duration.ofSeconds(5), false, 2, Duration.ofHours(1)),
            event -> {
              int attempt = count.incrementAndGet();
              throw new RecordingFailure(
                  attempt == 1 ? "write_failed" : "commit_unknown",
                  new IllegalStateException("private details"));
            },
            System::nanoTime,
            metrics);
    publisher.publish(UUID.randomUUID(), Instant.now());
    publisher.publish(UUID.randomUUID(), Instant.now());
    publisher.drain(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
    assertThat(count.get()).isEqualTo(2);
    assertThat(
            metrics
                .get("analytics.events.failure")
                .tag("outcome", "write_failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("analytics.events.failure")
                .tag("outcome", "commit_unknown")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.find("analytics.events.committed").counter()).isNull();
  }

  @Test
  void recorderOwnsTransactionAndSeesCommitException() {
    var jdbc = mock(JdbcTemplate.class);
    var manager = mock(PlatformTransactionManager.class);
    var status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    doThrow(new IllegalStateException("commit transport lost")).when(manager).commit(status);
    var recorder = new TransactionalEventRecorder(jdbc, manager);
    assertThatThrownBy(
            () ->
                recorder.record(
                    new ClickEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now())))
        .isInstanceOf(RecordingFailure.class)
        .extracting("outcome")
        .isEqualTo("commit_unknown");
    verify(manager).commit(status);
  }

  @Test
  void insertFailureRollsBackAndIsDistinctFromCommit() {
    var jdbc = mock(JdbcTemplate.class);
    var manager = mock(PlatformTransactionManager.class);
    var status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    when(jdbc.update(
            anyString(), any(UUID.class), any(UUID.class), any(java.time.OffsetDateTime.class)))
        .thenThrow(new IllegalStateException("insert failed"));
    assertThatThrownBy(
            () ->
                new TransactionalEventRecorder(jdbc, manager)
                    .record(new ClickEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now())))
        .isInstanceOf(RecordingFailure.class)
        .extracting("outcome")
        .isEqualTo("write_failed");
    verify(manager).rollback(status);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-02-30",
        "2026-2-03",
        "0000-01-01",
        "2026-10-11T00:00:00Z",
        "2026-10-11Z",
        ""
      })
  void rejectsMalformedDates(String from) {
    assertThatThrownBy(
            () ->
                StatsWindow.parse(
                    Map.of("from", new String[] {from}, "to", new String[] {"2026-10-12"}),
                    Instant.now()))
        .hasMessageContaining("UTC date window");
  }

  @Test
  void validatesShapeAndBoundsAndDefaultUtcWindow() {
    Instant now = Instant.parse("2026-10-11T00:00:00Z");
    assertThat(StatsWindow.parse(Map.of(), now))
        .isEqualTo(
            new StatsWindow(
                java.time.LocalDate.parse("2026-10-05"), java.time.LocalDate.parse("2026-10-12")));
    for (var params :
        java.util.List.of(
            Map.of("from", new String[] {"2026-10-01"}),
            Map.of("x", new String[] {"a"}),
            Map.of(
                "from",
                new String[] {"2026-10-01", "2026-10-02"},
                "to",
                new String[] {"2026-10-03"}),
            Map.of("from", new String[] {"2026-10-01"}, "to", new String[] {"2026-10-01"}),
            Map.of("from", new String[] {"2026-10-01"}, "to", new String[] {"2026-11-01"}))) {
      assertThatThrownBy(() -> StatsWindow.parse(params, now))
          .hasMessageContaining("UTC date window");
    }
    assertThat(
            StatsWindow.parse(
                    Map.of("from", new String[] {"2026-10-01"}, "to", new String[] {"2026-10-31"}),
                    now)
                .to())
        .hasToString("2026-10-31");
  }

  @Test
  void invalidSettingsFailBeforeStartingThreads() {
    assertThatThrownBy(
            () -> new AnalyticsSettings(0, 1, Duration.ofSeconds(5), false, 1, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnalyticsSettings(2, 0, Duration.ofSeconds(5), false, 1, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnalyticsSettings(2, 1, Duration.ZERO, false, 1, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @SuppressWarnings("removal")
  @Test
  void shutdownOrdersHttpThenAdmissionThenDrainThenDatasource() {
    var publisher = mock(ClickEventPublisher.class);
    var cleanup = mock(RetentionCleanup.class);
    var datasource = mock(HikariDataSource.class);
    var lifecycle = new AnalyticsShutdown(publisher, cleanup, datasource);
    assertThat(lifecycle.getPhase())
        .isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    lifecycle.start();
    lifecycle.onApplicationEvent(new ContextClosedEvent(new StaticApplicationContext()));
    lifecycle.stop();
    var order = org.mockito.Mockito.inOrder(publisher, cleanup, datasource);
    order.verify(publisher).stopAdmission();
    var maintenanceDeadline = org.mockito.ArgumentCaptor.forClass(Long.class);
    var writerDeadline = org.mockito.ArgumentCaptor.forClass(Long.class);
    order.verify(cleanup).stop(maintenanceDeadline.capture());
    order.verify(publisher).drain(writerDeadline.capture());
    assertThat(writerDeadline.getValue()).isEqualTo(maintenanceDeadline.getValue());
    assertThat(writerDeadline.getValue() - System.nanoTime())
        .isBetween(0L, TimeUnit.SECONDS.toNanos(10));
    order.verify(datasource).close();
  }

  @Test
  void forcedDrainDiscardsPendingWorkWithinRemainingBudget() {
    var metrics = new SimpleMeterRegistry();
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var publisher =
        new ClickEventPublisher(
            settings(),
            event -> {
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException stop) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RecordingFailure("commit_unknown", stop);
              }
            },
            System::nanoTime,
            metrics);
    publisher.publish(UUID.randomUUID(), Instant.now());
    await(entered);
    publisher.publish(UUID.randomUUID(), Instant.now());
    publisher.drain(System.nanoTime());
    await(interrupted);
    assertThat(
            metrics
                .get("analytics.events.lost")
                .tag("reason", "shutdown_pending")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.get("analytics.shutdown.forced").counter().count()).isEqualTo(1);
  }
}
