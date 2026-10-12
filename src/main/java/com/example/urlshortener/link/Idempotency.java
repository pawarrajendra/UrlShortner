package com.example.urlshortener.link;

import com.example.urlshortener.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class Idempotency {
  private Idempotency() {}

  public static String keyDigest(List<String> headers) {
    if (headers.isEmpty()) {
      return null;
    }
    if (headers.size() != 1 || !headers.getFirst().matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new ApiException(
          400,
          "INVALID_IDEMPOTENCY_KEY",
          "One valid Idempotency-Key of 1 to 128 characters is required.");
    }
    // Global key namespace. Preserve the legacy prefix so stored keys remain replayable.
    return digest("8:operator" + headers.getFirst().length() + ":" + headers.getFirst());
  }

  public static String fingerprint(String asciiUrl) {
    return fingerprint(asciiUrl, null);
  }

  public static String fingerprint(String asciiUrl, Instant expiresAt) {
    // Preserve the P2 null-expiry encoding exactly, including keys already stored under V1.
    String expiry = expiresAt == null ? "null" : expiresAt.toString();
    return digest(asciiUrl.length() + ":" + asciiUrl + expiry.length() + ":" + expiry);
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required digest is unavailable.");
    }
  }
}
