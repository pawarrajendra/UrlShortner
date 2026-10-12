package com.example.urlshortener.api;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

public final class ErrorResponses {
  private ErrorResponses() {}

  public static Map<String, Object> body(HttpServletResponse response, ApiException failure) {
    return Map.of(
        "error",
        Map.of(
            "code",
            failure.code(),
            "message",
            failure.getMessage(),
            "requestId",
            response.getHeader("X-Request-Id")));
  }
}
