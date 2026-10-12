package com.example.urlshortener.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaHealthIndicatorTest {
  @Test
  void readinessRejectsMissingOrIncompatibleSchemaWithoutDetails() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SchemaHealthIndicator indicator = new SchemaHealthIndicator(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
        .thenThrow(new DataAccessResourceFailureException("private-db-detail"));
    assertThat(indicator.health().getDetails()).isEmpty();
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void expectedSchemaIsReady() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
    assertThat(new SchemaHealthIndicator(jdbc).health().getStatus()).isEqualTo(Status.UP);
  }
}
