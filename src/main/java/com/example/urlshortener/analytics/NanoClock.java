package com.example.urlshortener.analytics;

@FunctionalInterface
public interface NanoClock {
  long nanoTime();
}
