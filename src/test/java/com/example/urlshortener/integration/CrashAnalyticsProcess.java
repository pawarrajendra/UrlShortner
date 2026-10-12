package com.example.urlshortener.integration;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.analytics.EventRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only subprocess with a deliberately paused writer; no production pause hook exists. */
public class CrashAnalyticsProcess {
  private static Path entered;

  public static void main(String[] args) throws Exception {
    Path ready = Path.of(args[0]);
    entered = Path.of(args[1]);
    var context =
        new SpringApplication(UrlShortenerApplication.class, CrashBeans.class)
            .run(Arrays.copyOfRange(args, 2, args.length));
    Files.writeString(
        ready, Integer.toString(((WebServerApplicationContext) context).getWebServer().getPort()));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CrashBeans {
    @Bean
    @Primary
    EventRecorder pausedRecorder() {
      return event -> {
        try {
          Files.writeString(entered, "worker-paused");
          new CountDownLatch(1).await();
        } catch (Exception failure) {
          throw new IllegalStateException("Test subprocess stopped.", failure);
        }
      };
    }
  }
}
