package com.example.urlshortener.measurement;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Local measurement only; the load fixture puts this endpoint on an unpublished port. */
@Configuration(proxyBeanMethods = false)
@Profile("measure")
public class MeasurementConfiguration {
  @Bean
  MeterFilter measurementPercentiles() {
    return new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(
          Meter.Id id, DistributionStatisticConfig config) {
        if (id.getName().equals("http.server.requests")
            || id.getName().equals("analytics.delivery.lag")
            || id.getName().equals("analytics.queue.wait")) {
          return DistributionStatisticConfig.builder()
              .percentiles(0.95)
              .expiry(Duration.ofMinutes(2))
              .bufferLength(1)
              .build()
              .merge(config);
        }
        return config;
      }
    };
  }

  @Bean
  SnapshotEndpoint snapshotEndpoint(MeterRegistry registry) {
    return new SnapshotEndpoint(registry);
  }

  @Endpoint(id = "measurement")
  public static class SnapshotEndpoint {
    private final MeterRegistry registry;

    SnapshotEndpoint(MeterRegistry registry) {
      this.registry = registry;
    }

    @ReadOperation
    public List<Map<String, Object>> snapshot() {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Meter meter : registry.getMeters()) {
        String name = meter.getId().getName();
        if (!(name.startsWith("analytics.")
            || name.startsWith("redirect.")
            || name.startsWith("hikaricp.")
            || name.equals("http.server.requests")
            || name.equals("jvm.memory.used")
            || name.equals("jvm.memory.max")
            || name.equals("process.cpu.usage"))) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        Map<String, String> tags = new LinkedHashMap<>();
        meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
        row.put("tags", tags);
        meter.use(
            gauge -> row.put("value", finite(gauge.value())),
            counter -> row.put("count", counter.count()),
            timer -> {
              row.put("count", timer.count());
              row.put("totalSeconds", timer.totalTime(TimeUnit.SECONDS));
              row.put("maxSeconds", timer.max(TimeUnit.SECONDS));
              for (var value : timer.takeSnapshot().percentileValues()) {
                if (value.percentile() == 0.95) {
                  row.put("p95Seconds", finite(value.value(TimeUnit.SECONDS)));
                }
              }
            },
            summary -> row.put("count", summary.count()),
            task -> row.put("active", task.activeTasks()),
            timeGauge -> row.put("value", finite(timeGauge.value(TimeUnit.SECONDS))),
            functionCounter -> row.put("count", functionCounter.count()),
            functionTimer -> row.put("count", functionTimer.count()),
            other -> {});
        result.add(row);
      }
      return result;
    }

    private static Double finite(double value) {
      return Double.isFinite(value) ? value : null;
    }
  }
}
