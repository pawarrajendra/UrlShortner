package com.example.urlshortener.controller;

import com.example.urlshortener.analytics.LinkStatsResponse;
import com.example.urlshortener.analytics.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!migrate")
public class StatsController {
  private final StatsService stats;

  public StatsController(StatsService stats) {
    this.stats = stats;
  }

  @GetMapping("/api/v1/links/{code}/stats")
  LinkStatsResponse stats(@PathVariable String code, HttpServletRequest request) {
    return stats.stats(code, request.getParameterMap());
  }
}
