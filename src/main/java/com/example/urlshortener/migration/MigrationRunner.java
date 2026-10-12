package com.example.urlshortener.migration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("migrate")
public class MigrationRunner implements CommandLineRunner {
  private final DataSource datasource;

  public MigrationRunner(DataSource datasource) {
    this.datasource = datasource;
  }

  @Override
  public void run(String... args) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(datasource)
            .locations("classpath:db/migration")
            .defaultSchema("public")
            .cleanDisabled(true)
            .target(MigrationVersion.fromVersion("3"))
            .load();
    // migrate validates existing history while allowing pending migrations on a fresh DB.
    flyway.migrate();
    flyway.validate();
  }
}
