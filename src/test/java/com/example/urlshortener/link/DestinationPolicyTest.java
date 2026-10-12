package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.config.WebSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DestinationPolicyTest {
  private final DestinationPolicy policy =
      new DestinationPolicy(new WebSettings("https://short.example.com", "unit"));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com/p?a=1&a=2#frag",
        "HTTP://EXAMPLE.COM:80/path",
        "https://xn--bcher-kva.example/path",
        "https://8.8.8.8/",
        "https://[2001:4860:4860::8888]/"
      })
  void preservesAcceptedAsciiDestination(String value) {
    assertThat(policy.validate(value)).isEqualTo(value);
  }

  @Test
  void serializesUnicodePathWithoutReorderingOrDecoding() {
    assertThat(policy.validate("https://example.com/café?q=你好&x=%2F#é"))
        .isEqualTo("https://example.com/caf%C3%A9?q=%E4%BD%A0%E5%A5%BD&x=%2F#%C3%A9");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " /relative",
        "/relative",
        "https:///missing",
        "ftp://example.com",
        "https://user:secret@example.com/",
        "https://example.com/%0aInjected",
        "https://example.com/%7F",
        "https://example.com/\n",
        " https://example.com",
        "https://example.com ",
        "https://bücher.example",
        "https://localhost/",
        "https://localhost./",
        "https://service.local/",
        "https://intranet/",
        "https://127.0.0.1/",
        "https://10.0.0.1/",
        "https://169.254.1.2/",
        "https://172.16.0.1/",
        "https://192.168.1.1/",
        "https://100.64.0.1/",
        "https://[::1]/",
        "https://[fc00::1]/",
        "https://[fe80::1]/",
        "https://[::ffff:127.0.0.1]/",
        "https://[fe80::1%25eth0]/",
        "https://127.1/",
        "https://2130706433/",
        "https://0177.0.0.1/",
        "https://0x7f000001/",
        "https://256.1.1.1/",
        "https://SHORT.EXAMPLE.COM:444/",
        "http://short.example.com./",
        "https://example.com:0/",
        "https://example.com:65536/",
        "https://example.com/%notescape"
      })
  void rejectsUnsafeAndMalformedDestinations(String value) {
    assertThatThrownBy(() -> policy.validate(value))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_URL");
              assertThat(error.getMessage()).doesNotContain(value.isEmpty() ? "secret" : value);
            });
  }

  @Test
  void enforcesBothCodePointAndSerializedBoundaries() {
    String prefix = "https://example.com/";
    assertThat(policy.validate(prefix + "x".repeat(2048 - prefix.length()))).hasSize(2048);
    assertThatThrownBy(() -> policy.validate(prefix + "x".repeat(2049 - prefix.length())))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> policy.validate(prefix + "é".repeat(500)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> policy.validate(null)).isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsUnpairedSurrogateRatherThanSerializingReplacementCharacters() {
    assertThatThrownBy(() -> policy.validate("https://example.com/" + (char) 0xd800))
        .isInstanceOf(ApiException.class);
  }
}
