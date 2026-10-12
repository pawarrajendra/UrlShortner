package com.example.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FoundationConfigurationTest {
  @Test
  void validatesRequiredWebSettings() {
    WebSettings settings = new WebSettings("http://localhost:8080", "app-1");
    assertThat(settings.publicBaseUrl()).isEqualTo("http://localhost:8080");
    assertThatThrownBy(() -> new WebSettings("https://secret@example.com", "app-1"))
        .hasMessage("Required application settings are missing or invalid.");
    assertThatThrownBy(() -> new WebSettings("http://localhost:8080", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingDatabaseOrNonPostgresqlConfigurationFailsSafely() {
    FoundationConfiguration configuration = new FoundationConfiguration();
    assertThatThrownBy(
            () -> configuration.databaseSettingsGuard(new MockEnvironment()).afterPropertiesSet())
        .hasMessage("Required database settings are missing or invalid.");
    MockEnvironment invalid =
        new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:h2:mem:test")
            .withProperty("spring.datasource.username", "app")
            .withProperty("spring.datasource.password", "sensitive-test-password");
    assertThatThrownBy(() -> configuration.databaseSettingsGuard(invalid).afterPropertiesSet())
        .hasMessage("Required database settings are missing or invalid.");
  }

  @Test
  void validDatabaseSettingsAndUtcClock() throws Exception {
    FoundationConfiguration configuration = new FoundationConfiguration();
    MockEnvironment valid =
        new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/test")
            .withProperty("spring.datasource.username", "app")
            .withProperty("spring.datasource.password", "test-only");
    configuration.databaseSettingsGuard(valid).afterPropertiesSet();
    assertThat(configuration.clock().getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
