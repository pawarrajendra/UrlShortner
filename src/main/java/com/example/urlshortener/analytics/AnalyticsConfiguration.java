package com.example.urlshortener.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
@EnableConfigurationProperties(AnalyticsSettings.class)
public class AnalyticsConfiguration {
  @Bean
  NanoClock nanoClock() {
    return System::nanoTime;
  }
}
