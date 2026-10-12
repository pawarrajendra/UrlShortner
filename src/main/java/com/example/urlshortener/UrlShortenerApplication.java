package com.example.urlshortener;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class UrlShortenerApplication {
  public static void main(String[] args) {
    try {
      ConfigurableApplicationContext context =
          SpringApplication.run(UrlShortenerApplication.class, args);
      if (Arrays.asList(context.getEnvironment().getActiveProfiles()).contains("migrate")) {
        int exitCode = SpringApplication.exit(context);
        context.close();
        System.exit(exitCode);
      }
    } catch (RuntimeException failure) {
      System.err.println(
          "Startup failed; verify configuration, database access, and migration history.");
      System.exit(1);
    }
  }
}
