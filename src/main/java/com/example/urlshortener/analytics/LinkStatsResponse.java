package com.example.urlshortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LinkStatsResponse(
    String code,
    LocalDate from,
    LocalDate to,
    long observedRedirects,
    List<Daily> daily,
    Instant availableFrom,
    Instant asOf,
    boolean partialRetention,
    String recordingMode,
    boolean eventuallyConsistent) {
  public record Daily(LocalDate date, long observedRedirects) {}
}
