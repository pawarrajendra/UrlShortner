package com.example.urlshortener.cache;

import com.example.urlshortener.analytics.NanoClock;
import com.example.urlshortener.link.DestinationPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
@EnableConfigurationProperties(CacheSettings.class)
public class CacheConfiguration {
  @Bean
  RedirectCache redirectCache(
      CacheSettings settings,
      DestinationPolicy destinations,
      Clock clock,
      NanoClock ticker,
      MeterRegistry metrics) {
    return settings.enabled()
        ? new RedisRedirectCache(
            settings, new CacheCodec(clock, destinations), clock, ticker, metrics)
        : new NoOpRedirectCache();
  }
}
