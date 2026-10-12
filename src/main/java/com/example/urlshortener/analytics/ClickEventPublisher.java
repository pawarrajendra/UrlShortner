package com.example.urlshortener.analytics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
public class ClickEventPublisher {
  private final ThreadPoolExecutor executor;
  private final EventRecorder recorder;
  private final NanoClock clock;
  private final MeterRegistry metrics;
  private final long maxWait;
  private boolean accepting = true;

  public ClickEventPublisher(
      AnalyticsSettings settings, EventRecorder recorder, NanoClock clock, MeterRegistry metrics) {
    this.recorder = recorder;
    this.clock = clock;
    this.metrics = metrics;
    maxWait = settings.maxQueueWait().toNanos();
    AtomicInteger sequence = new AtomicInteger();
    executor =
        new ThreadPoolExecutor(
            settings.workers(),
            settings.workers(),
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(settings.queueCapacity()),
            task -> {
              Thread thread = new Thread(task, "analytics-writer-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    Gauge.builder("analytics.queue.depth", executor, pool -> pool.getQueue().size())
        .register(metrics);
    Gauge.builder("analytics.workers.active", executor, ThreadPoolExecutor::getActiveCount)
        .register(metrics);
    Gauge.builder("analytics.queue.oldest.age.seconds", this, publisher -> publisher.oldestAge())
        .register(metrics);
  }

  public synchronized void publish(UUID linkId, Instant observedAt) {
    if (!accepting) {
      loss("admission_closed");
      return;
    }
    try {
      executor.execute(
          new Work(new ClickEvent(UUID.randomUUID(), linkId, observedAt), clock.nanoTime()));
      metrics.counter("analytics.events.accepted").increment();
    } catch (RejectedExecutionException rejected) {
      loss("queue_full");
    }
  }

  public synchronized void stopAdmission() {
    accepting = false;
    executor.shutdown();
  }

  public void drain(long deadlineNanos) {
    stopAdmission();
    try {
      if (!executor.awaitTermination(
          Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)) {
        int pending = executor.shutdownNow().size();
        metrics.counter("analytics.events.lost", "reason", "shutdown_pending").increment(pending);
        metrics.counter("analytics.shutdown.forced").increment();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      metrics
          .counter("analytics.events.lost", "reason", "shutdown_pending")
          .increment(executor.shutdownNow().size());
    }
  }

  public boolean terminated() {
    return executor.isTerminated();
  }

  private void loss(String reason) {
    metrics.counter("analytics.events.lost", "reason", reason).increment();
  }

  private double oldestAge() {
    Runnable first = executor.getQueue().peek();
    return first instanceof Work work ? Math.max(0, clock.nanoTime() - work.enqueuedAt) / 1e9 : 0;
  }

  private final class Work implements Runnable {
    private final ClickEvent event;
    private final long enqueuedAt;

    Work(ClickEvent event, long enqueuedAt) {
      this.event = event;
      this.enqueuedAt = enqueuedAt;
    }

    @Override
    public void run() {
      long wait = Math.max(0, clock.nanoTime() - enqueuedAt);
      metrics.timer("analytics.queue.wait").record(Duration.ofNanos(wait));
      if (wait > maxWait) {
        loss("queue_age");
        return;
      }
      try {
        recorder.record(event);
        metrics.counter("analytics.events.committed").increment();
        metrics
            .timer("analytics.delivery.lag")
            .record(Duration.ofNanos(Math.max(0, clock.nanoTime() - enqueuedAt)));
      } catch (RecordingFailure failure) {
        metrics.counter("analytics.events.failure", "outcome", failure.outcome()).increment();
      } catch (RuntimeException failure) {
        metrics.counter("analytics.events.failure", "outcome", "recorder_failed").increment();
      }
    }
  }
}
