package com.example.urlshortener.cache;

import com.example.urlshortener.link.DestinationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class CacheCodec {
  private final JsonMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Clock clock;
  private final DestinationPolicy destinations;

  public CacheCodec(Clock clock, DestinationPolicy destinations) {
    this.clock = clock;
    this.destinations = destinations;
  }

  public String encode(CachedRedirect value) {
    var map = new LinkedHashMap<String, Object>();
    map.put("version", value.version());
    map.put("code", value.code());
    map.put("linkId", value.linkId().toString());
    map.put("url", value.url());
    map.put("expiresAt", value.expiresAt() == null ? null : value.expiresAt().toString());
    map.put("readAt", value.readAt().toString());
    map.put("freshUntil", value.freshUntil().toString());
    return json.writeValueAsString(map);
  }

  public CachedRedirect decode(String code, String raw) {
    if (raw.length() > 8192) {
      throw new IllegalArgumentException();
    }
    var node = json.readTree(raw);
    if (node == null
        || !node.isObject()
        || node.size() != 7
        || !node.has("version")
        || !node.get("version").isNumber()
        || !"1".equals(node.get("version").asString())) {
      throw new IllegalArgumentException();
    }
    for (String name : new String[] {"code", "linkId", "url", "readAt", "freshUntil"}) {
      if (!node.has(name) || !node.get(name).isString()) {
        throw new IllegalArgumentException();
      }
    }
    if (!node.has("expiresAt")
        || (!node.get("expiresAt").isNull() && !node.get("expiresAt").isString())) {
      throw new IllegalArgumentException();
    }
    Instant expiry =
        node.get("expiresAt").isNull() ? null : Instant.parse(node.get("expiresAt").asString());
    var value =
        new CachedRedirect(
            1,
            node.get("code").asString(),
            UUID.fromString(node.get("linkId").asString()),
            node.get("url").asString(),
            expiry,
            Instant.parse(node.get("readAt").asString()),
            Instant.parse(node.get("freshUntil").asString()));
    if (!code.equals(value.code())
        || !code.matches("[A-Za-z0-9]{8}")
        || !destinations.validate(value.url()).equals(value.url())
        || !value.freshUntil().equals(CachedRedirect.deadline(value.readAt(), expiry))
        || !value.readAt().isBefore(value.freshUntil())
        || value.readAt().isAfter(clock.instant().plusSeconds(1))) {
      throw new IllegalArgumentException();
    }
    return value;
  }
}
