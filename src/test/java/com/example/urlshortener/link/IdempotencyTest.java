package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.api.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdempotencyTest {
  @Test
  void digestIsStableScopedAndDoesNotStoreRawKey() {
    assertThat(Idempotency.keyDigest(List.of())).isNull();
    String digest = Idempotency.keyDigest(List.of("retry:ABC_1"));
    assertThat(digest)
        .matches("[0-9a-f]{64}")
        .isEqualTo(Idempotency.keyDigest(List.of("retry:ABC_1")));
    assertThat(digest).isNotEqualTo(Idempotency.keyDigest(List.of("retry:abc_1")));
    assertThat(Idempotency.keyDigest(List.of("x".repeat(128)))).hasSize(64);
    assertThat(Idempotency.fingerprint("https://example.com/?a=1&b=2"))
        .isNotEqualTo(Idempotency.fingerprint("https://example.com/?b=2&a=1"));
  }

  @Test
  void rejectsInvalidAndRepeatedHeadersWithoutEchoingValues() {
    for (var headers :
        List.of(
            List.of(""),
            List.of("secret key"),
            List.of("x".repeat(129)),
            List.of("first", "second"),
            List.of("first,second"),
            List.of("é"))) {
      assertThatThrownBy(() -> Idempotency.keyDigest(headers))
          .isInstanceOfSatisfying(
              ApiException.class,
              error -> assertThat(error.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }
  }
}
