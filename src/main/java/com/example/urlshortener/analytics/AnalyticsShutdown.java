package com.example.urlshortener.analytics;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/** HTTP graceful phase runs first; all background work then shares its remaining budget. */
@Component
@Profile("!migrate")
public class AnalyticsShutdown implements SmartLifecycle, ApplicationListener<ContextClosedEvent> {
  private final ClickEventPublisher publisher;
  private final RetentionCleanup cleanup;
  private final HikariDataSource datasource;
  private volatile boolean running;
  private volatile long deadline;

  public AnalyticsShutdown(
      ClickEventPublisher publisher, RetentionCleanup cleanup, DataSource datasource) {
    this.publisher = publisher;
    this.cleanup = cleanup;
    if (!(datasource instanceof HikariDataSource hikari)) {
      throw new IllegalArgumentException("Analytics requires the configured Hikari datasource.");
    }
    this.datasource = hikari;
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
  }

  @Override
  public void start() {
    running = true;
    cleanup.start();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @SuppressWarnings(
      "removal") // Pinned Boot 4.1 phase; integration test verifies HTTP drain ordering.
  @Override
  public int getPhase() {
    return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1;
  }

  @Override
  public void stop() {
    running = false;
    long end = deadline == 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(10) : deadline;
    publisher.stopAdmission();
    cleanup.stop(end);
    publisher.drain(end);
    // Explicitly close after the drain/forced interruption, not by incidental bean destruction
    // order.
    datasource.close();
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }
}
