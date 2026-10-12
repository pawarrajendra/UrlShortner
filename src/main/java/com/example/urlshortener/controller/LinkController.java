package com.example.urlshortener.controller;

import com.example.urlshortener.analytics.ClickEventPublisher;
import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.link.DestinationPolicy;
import com.example.urlshortener.link.ExpirationPolicy;
import com.example.urlshortener.link.Idempotency;
import com.example.urlshortener.link.LinkResponse;
import com.example.urlshortener.link.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("!migrate")
public class LinkController {
  private static final int MAX_BODY_BYTES = 16 * 1024;
  private final JsonMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final ClickEventPublisher events;
  private final LinkService links;
  private final DestinationPolicy destinations;

  public LinkController(
      LinkService links, DestinationPolicy destinations, ClickEventPublisher events) {
    this.events = events;
    this.links = links;
    this.destinations = destinations;
  }

  @PostMapping("/api/v1/links")
  ResponseEntity<LinkResponse> create(HttpServletRequest request) throws IOException {
    String key = Idempotency.keyDigest(Collections.list(request.getHeaders("Idempotency-Key")));
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw tooLarge();
    }
    try {
      MediaType type =
          MediaType.parseMediaType(
              request.getContentType() == null ? "" : request.getContentType());
      if (!"application".equalsIgnoreCase(type.getType())
          || !"json".equalsIgnoreCase(type.getSubtype())
          || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
        throw unsupported();
      }
    } catch (IllegalArgumentException invalidType) {
      throw unsupported();
    }
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw tooLarge();
    }
    String url;
    Instant expiresAt;
    try {
      String body =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var tree = json.readTree(body);
      if (tree == null
          || !tree.isObject()
          || tree.size() < 1
          || tree.size() > 2
          || (tree.size() == 2 && !tree.has("expiresAt"))
          || !tree.has("url")
          || !tree.get("url").isString()) {
        throw ApiException.invalidRequest();
      }
      url = tree.get("url").asString();
      var expiry = tree.get("expiresAt");
      if (expiry != null && !expiry.isNull() && !expiry.isString()) {
        throw ExpirationPolicy.invalid();
      }
      expiresAt =
          ExpirationPolicy.parse(expiry == null || expiry.isNull() ? null : expiry.asString());
    } catch (JacksonException | CharacterCodingException malformed) {
      throw ApiException.invalidRequest();
    }
    return ResponseEntity.status(201)
        .body(links.create(destinations.validate(url), expiresAt, key));
  }

  @GetMapping("/api/v1/links/{code}")
  LinkResponse inspect(@PathVariable String code) {
    return links.inspect(code);
  }

  @DeleteMapping("/api/v1/links/{code}")
  ResponseEntity<Void> disable(@PathVariable String code) {
    links.disable(code);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/r/{code}")
  ResponseEntity<Void> get(@PathVariable String code) {
    return redirect(code, true);
  }

  @RequestMapping(path = "/r/{code}", method = RequestMethod.HEAD)
  ResponseEntity<Void> head(@PathVariable String code) {
    return redirect(code, false);
  }

  @RequestMapping(
      path = "/r/{code}",
      method = {
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.DELETE,
        RequestMethod.PATCH,
        RequestMethod.OPTIONS,
        RequestMethod.TRACE
      })
  ResponseEntity<Void> unsupportedRedirect() {
    throw new ApiException(405, "METHOD_NOT_ALLOWED", "Only GET and HEAD are supported.");
  }

  private ResponseEntity<Void> redirect(String code, boolean record) {
    var resolution = links.resolveObserved(code);
    if (record) {
      events.publish(resolution.linkId(), resolution.observedAt());
    }
    return ResponseEntity.status(302).header(HttpHeaders.LOCATION, resolution.url()).build();
  }

  private static ApiException tooLarge() {
    return new ApiException(413, "PAYLOAD_TOO_LARGE", "The request body exceeds 16 KiB.");
  }

  private static ApiException unsupported() {
    return new ApiException(
        415, "UNSUPPORTED_MEDIA_TYPE", "A UTF-8 application/json body is required.");
  }
}
