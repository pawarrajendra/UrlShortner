package com.example.urlshortener.measurement;

import com.example.urlshortener.config.WebSettings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** One GET timer per replica, including every status instead of merging status percentiles. */
@Component
@Profile("measure")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MeasurementRequestFilter extends OncePerRequestFilter {
  private final Timer duration;

  public MeasurementRequestFilter(MeterRegistry registry, WebSettings settings) {
    duration =
        Timer.builder("redirect.server.duration")
            .tag("instance", settings.instanceId())
            .publishPercentiles(0.95)
            .distributionStatisticExpiry(Duration.ofMinutes(2))
            .distributionStatisticBufferLength(1)
            .register(registry);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("GET") || !request.getServletPath().startsWith("/r/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long began = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      duration.record(Duration.ofNanos(Math.max(0, System.nanoTime() - began)));
    }
  }
}
