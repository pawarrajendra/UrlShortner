package com.example.urlshortener.analytics;

@FunctionalInterface
public interface EventRecorder {
  void record(ClickEvent event);
}
