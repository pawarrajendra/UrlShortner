package com.example.urlshortener.config;

import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class FoundationConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  InitializingBean databaseSettingsGuard(Environment environment) {
    return () -> {
      String url = environment.getProperty("spring.datasource.url", "");
      String user = environment.getProperty("spring.datasource.username", "");
      String password = environment.getProperty("spring.datasource.password", "");
      int version = environment.getProperty("app.expected-schema-version", Integer.class, 3);
      if (!url.startsWith("jdbc:postgresql:")
          || user.isBlank()
          || password.isBlank()
          || version != 3) {
        throw new IllegalArgumentException("Required database settings are missing or invalid.");
      }
    };
  }
}
