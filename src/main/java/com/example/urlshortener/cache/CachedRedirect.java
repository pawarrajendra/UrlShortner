package com.example.urlshortener.cache;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record CachedRedirect(
    int version,
    String code,
    UUID linkId,
    String url,
    Instant expiresAt,
    Instant readAt,
    Instant freshUntil) {
  public static Instant deadline(Instant readAt, Instant expiresAt) {
    Instant limit = readAt.plusSeconds(60);
    if (expiresAt != null && expiresAt.isBefore(limit)) {
      limit = expiresAt;
    }
    return limit.truncatedTo(ChronoUnit.MILLIS);
  }
}
