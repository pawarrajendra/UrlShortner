package com.example.urlshortener.analytics;

import java.time.Instant;
import java.util.UUID;

/** No servlet, destination or visitor identity is retained. */
public record ClickEvent(UUID id, UUID linkId, Instant occurredAt) {}
