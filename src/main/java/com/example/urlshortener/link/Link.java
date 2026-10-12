package com.example.urlshortener.link;

import java.time.Instant;
import java.util.UUID;

public record Link(
    UUID id, String code, String url, Instant createdAt, Instant disabledAt, Instant expiresAt) {}
