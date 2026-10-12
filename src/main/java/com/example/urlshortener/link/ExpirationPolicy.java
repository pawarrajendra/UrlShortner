package com.example.urlshortener.link;

import com.example.urlshortener.api.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class ExpirationPolicy {
  private ExpirationPolicy() {}

  public static Instant parse(String value) {
    if (value == null) {
      return null;
    }
    if (!value.matches(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})")) {
      throw invalid();
    }
    try {
      Instant instant = OffsetDateTime.parse(value).toInstant();
      int year = instant.atOffset(ZoneOffset.UTC).getYear();
      if (year < 1 || year > 9999) {
        throw invalid();
      }
      return instant;
    } catch (DateTimeParseException invalidTime) {
      throw invalid();
    }
  }

  public static ApiException invalid() {
    return new ApiException(
        400,
        "INVALID_EXPIRATION",
        "Expiry must be a valid future offset timestamp with at most six fractional digits, within UTC years 0001 to 9999.");
  }
}
