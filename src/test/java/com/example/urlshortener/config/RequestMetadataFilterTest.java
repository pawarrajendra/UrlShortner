package com.example.urlshortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestMetadataFilterTest {
  @Test
  void ignoresInboundIdsAndClearsContextEvenOnFailure() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "untrusted-client-id");
    request.addHeader("X-App-Instance", "spoofed-instance");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThatThrownBy(
            () ->
                new RequestMetadataFilter(new WebSettings("http://localhost:8080", "app-test"))
                    .doFilter(
                        request,
                        response,
                        (req, res) -> {
                          assertThat(MDC.get("requestId"))
                              .isEqualTo(response.getHeader("X-Request-Id"));
                          throw new ServletException("test-failure");
                        }))
        .isInstanceOf(ServletException.class);
    assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(MDC.get("requestId")).isNull();
    assertThat(response.getHeader("X-App-Instance")).isEqualTo("app-test");
    assertThat(MDC.get("appInstance")).isNull();
  }
}
