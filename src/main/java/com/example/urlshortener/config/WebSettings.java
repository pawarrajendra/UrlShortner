package com.example.urlshortener.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record WebSettings(String publicBaseUrl, String instanceId) {
  public WebSettings {
    boolean valid = instanceId != null && instanceId.matches("[A-Za-z0-9_-]{1,32}");
    try {
      URI base = URI.create(publicBaseUrl);
      valid &= ("http".equals(base.getScheme()) || "https".equals(base.getScheme()));
      valid &= base.getHost() != null && base.getUserInfo() == null;
      valid &= base.getQuery() == null && base.getFragment() == null;
      valid &= base.getPath().isEmpty() || base.getPath().equals("/");
    } catch (RuntimeException invalidUri) {
      valid = false;
    }
    if (!valid) {
      throw new IllegalArgumentException("Required application settings are missing or invalid.");
    }
  }

  @Override
  public String toString() {
    return "WebSettings[values redacted]";
  }
}
