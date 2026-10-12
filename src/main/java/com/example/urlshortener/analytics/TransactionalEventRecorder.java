package com.example.urlshortener.analytics;

import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!migrate")
public class TransactionalEventRecorder implements EventRecorder {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public TransactionalEventRecorder(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void record(ClickEvent event) {
    AtomicBoolean inserted = new AtomicBoolean();
    try {
      transaction.executeWithoutResult(
          status -> {
            jdbc.update(
                "INSERT INTO redirect_events(id,link_id,occurred_at) VALUES (?,?,?)",
                event.id(),
                event.linkId(),
                event.occurredAt().atOffset(ZoneOffset.UTC));
            inserted.set(true);
          });
    } catch (RuntimeException failure) {
      // A failure after INSERT can include an unknown commit outcome. Never blindly retry it.
      throw new RecordingFailure(inserted.get() ? "commit_unknown" : "write_failed", failure);
    }
  }
}
