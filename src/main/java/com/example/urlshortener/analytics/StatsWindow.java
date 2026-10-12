package com.example.urlshortener.analytics;

import com.example.urlshortener.api.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public record StatsWindow(LocalDate from, LocalDate to) {
  public static StatsWindow parse(Map<String, String[]> parameters, Instant asOf) {
    try {
      if (parameters.isEmpty()) {
        LocalDate today = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
        return new StatsWindow(today.minusDays(6), today.plusDays(1));
      }
      if (parameters.size() != 2
          || !parameters.containsKey("from")
          || !parameters.containsKey("to")
          || parameters.get("from").length != 1
          || parameters.get("to").length != 1) {
        throw new IllegalArgumentException();
      }
      String from = parameters.get("from")[0], to = parameters.get("to")[0];
      if (!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
          || !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
        throw new IllegalArgumentException();
      }
      LocalDate start = LocalDate.parse(from), end = LocalDate.parse(to);
      long days = ChronoUnit.DAYS.between(start, end);
      if (start.getYear() < 1 || end.getYear() < 1 || days < 1 || days > 30) {
        throw new IllegalArgumentException();
      }
      return new StatsWindow(start, end);
    } catch (RuntimeException invalid) {
      throw new ApiException(
          400,
          "INVALID_DATE_RANGE",
          "Supply one valid from/to UTC date window of at most 30 days.");
    }
  }
}
