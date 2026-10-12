package com.example.urlshortener.analytics;

import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.link.LinkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("!migrate")
public class StatsService {
  private final LinkRepository links;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public StatsService(LinkRepository links, JdbcTemplate jdbc, Clock clock) {
    this.links = links;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public LinkStatsResponse stats(String code, Map<String, String[]> parameters) {
    Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
    StatsWindow window = StatsWindow.parse(parameters, asOf);
    if (!code.matches("[A-Za-z0-9]{8}")) {
      throw ApiException.notFound();
    }
    var link = links.find(code).orElseThrow(ApiException::notFound);
    Instant cutoff = asOf.minus(Duration.ofDays(30));
    Instant start = window.from().atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = window.to().atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant available = link.createdAt().isAfter(cutoff) ? link.createdAt() : cutoff;
    Instant effectiveStart = start.isAfter(cutoff) ? start : cutoff;
    Instant effectiveEnd = end.isBefore(asOf) ? end : asOf;
    Map<LocalDate, Long> counts = new HashMap<>();
    jdbc.query(
        "SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,count(*) AS total FROM redirect_events WHERE link_id=? AND occurred_at>=? AND occurred_at<? GROUP BY day ORDER BY day",
        rs -> {
          counts.put(rs.getObject("day", LocalDate.class), rs.getLong("total"));
        },
        link.id(),
        effectiveStart.atOffset(ZoneOffset.UTC),
        effectiveEnd.atOffset(ZoneOffset.UTC));
    var daily = new ArrayList<LinkStatsResponse.Daily>();
    long total = 0;
    for (LocalDate day = window.from(); day.isBefore(window.to()); day = day.plusDays(1)) {
      long count = counts.getOrDefault(day, 0L);
      daily.add(new LinkStatsResponse.Daily(day, count));
      total += count;
    }
    return new LinkStatsResponse(
        code,
        window.from(),
        window.to(),
        total,
        java.util.List.copyOf(daily),
        available,
        asOf,
        start.isBefore(available),
        "best-effort-async",
        true);
  }
}
