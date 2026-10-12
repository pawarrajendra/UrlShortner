package com.example.urlshortener.link;

import java.time.Instant;

public record LinkResponse(
    String code,
    String shortUrl,
    String url,
    Instant createdAt,
    Instant disabledAt,
    Instant expiresAt) {}
