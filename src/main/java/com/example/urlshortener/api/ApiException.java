package com.example.urlshortener.api;

public final class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String safeMessage) {
    super(safeMessage);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException invalidRequest() {
    return new ApiException(
        400,
        "INVALID_REQUEST",
        "A valid JSON object containing a string url and optional expiresAt is required.");
  }

  public static ApiException unavailable() {
    return new ApiException(
        503, "DEPENDENCY_UNAVAILABLE", "The service is temporarily unavailable.");
  }

  public static ApiException notFound() {
    return new ApiException(404, "LINK_NOT_FOUND", "The link was not found.");
  }
}
