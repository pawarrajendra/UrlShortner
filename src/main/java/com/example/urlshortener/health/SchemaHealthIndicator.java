package com.example.urlshortener.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("schemaHealthIndicator")
@Profile("!migrate")
public class SchemaHealthIndicator implements HealthIndicator {
  private final JdbcTemplate jdbc;

  public SchemaHealthIndicator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Health health() {
    try {
      Boolean compatible =
          jdbc.queryForObject(
              "SELECT count(*) = 3 AND count(DISTINCT version) = 3 AND bool_and(success AND version IN ('1','2','3')) FROM public.flyway_schema_history",
              Boolean.class);
      return Boolean.TRUE.equals(compatible) ? Health.up().build() : Health.down().build();
    } catch (DataAccessException failure) {
      return Health.down().build();
    }
  }
}
