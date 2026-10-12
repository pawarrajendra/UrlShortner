package com.example.urlshortener.controller;

import java.util.Map;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!migrate")
public class HealthController {
  private final HealthEndpoint endpoint;

  public HealthController(HealthEndpoint endpoint) {
    this.endpoint = endpoint;
  }

  @GetMapping("/health/live")
  public ResponseEntity<Map<String, String>> live() {
    return response(endpoint.healthForPath("liveness"));
  }

  @GetMapping("/health/ready")
  public ResponseEntity<Map<String, String>> ready() {
    return response(endpoint.healthForPath("readiness"));
  }

  private ResponseEntity<Map<String, String>> response(HealthDescriptor health) {
    boolean up = health != null && Status.UP.equals(health.getStatus());
    return ResponseEntity.status(up ? 200 : 503).body(Map.of("status", up ? "UP" : "DOWN"));
  }
}
