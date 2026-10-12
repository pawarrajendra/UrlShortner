package com.example.urlshortener.analytics;

public class RecordingFailure extends RuntimeException {
  private final String outcome;

  public RecordingFailure(String outcome, Throwable cause) {
    super("Analytics recording failed.", cause);
    this.outcome = outcome;
  }

  public String outcome() {
    return outcome;
  }
}
