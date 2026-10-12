package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.api.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpirationPolicyTest {
  @Test
  void absentAndEquivalentOffsetsNormalizeWithoutRounding() {
    assertThat(ExpirationPolicy.parse(null)).isNull();
    Instant instant = Instant.parse("2026-10-11T12:00:00.123456Z");
    assertThat(ExpirationPolicy.parse("2026-10-11T07:00:00.123456-05:00")).isEqualTo(instant);
    assertThat(ExpirationPolicy.parse("2026-10-11T17:30:00.123456+05:30")).isEqualTo(instant);
    assertThat(ExpirationPolicy.parse("0001-01-01T00:00:00Z"))
        .isEqualTo(Instant.parse("0001-01-01T00:00:00Z"));
    assertThat(ExpirationPolicy.parse("9999-12-31T23:59:59.999999Z"))
        .isEqualTo(Instant.parse("9999-12-31T23:59:59.999999Z"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not-a-time",
        "2026-10-11",
        "2026-10-11T12:00:00",
        "2026-10-11T12:00:00z",
        "2026-10-11T12:00:00.1234567Z",
        "2026-10-11T12:00:00.123456000Z",
        "2026-10-11T12:00:00.Z",
        "2026-10-11T12:00:60Z",
        "2026-10-11T24:00:00Z",
        "2026-02-29T12:00:00Z",
        "2026-10-11T12:00:00+19:00",
        "2026-10-11T12:00:00+01:00:00",
        "2026-10-11T12:00:00+0100",
        "0000-01-01T00:00:00Z",
        "0001-01-01T00:00:00+00:01",
        "9999-12-31T23:59:59-00:01",
        "+10000-01-01T00:00:00Z",
        " 2026-10-11T12:00:00Z",
        "2026-10-11T12:00:00Z "
      })
  void rejectsMalformedPrecisionLeapSecondsAndUtcRange(String value) {
    assertThatThrownBy(() -> ExpirationPolicy.parse(value))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.code()).isEqualTo("INVALID_EXPIRATION");
              assertThat(error.status()).isEqualTo(400);
            });
  }

  @Test
  void legacyNullFingerprintRemainsByteCompatibleAndExpiryUsesUtcInstant() {
    String url = "https://example.com/brownfield?a=1&a=2#fragment";
    assertThat(Idempotency.fingerprint(url))
        .isEqualTo("083878d81f8e48f8db39cb24a72c907910480901bff6d9b6d53ff5adb6ec1227");
    assertThat(Idempotency.fingerprint(url, null)).isEqualTo(Idempotency.fingerprint(url));
    Instant a = ExpirationPolicy.parse("2026-10-11T07:00:00.123456-05:00");
    Instant b = ExpirationPolicy.parse("2026-10-11T12:00:00.123456Z");
    assertThat(Idempotency.fingerprint(url, a))
        .isEqualTo(Idempotency.fingerprint(url, b))
        .isNotEqualTo(Idempotency.fingerprint(url, null));
  }
}
