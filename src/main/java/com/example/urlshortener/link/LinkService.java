package com.example.urlshortener.link;

import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.cache.RedirectResolver;
import com.example.urlshortener.config.WebSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!migrate")
public class LinkService {
  private final RedirectResolver resolver;
  private final LinkRepository repository;
  private final CodeGenerator generator;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final String baseUrl;
  private final MeterRegistry metrics;

  public LinkService(
      LinkRepository repository,
      RedirectResolver resolver,
      CodeGenerator generator,
      Clock clock,
      PlatformTransactionManager manager,
      WebSettings settings,
      MeterRegistry metrics) {
    this.resolver = resolver;
    this.repository = repository;
    this.generator = generator;
    this.clock = clock;
    this.metrics = metrics;
    this.baseUrl = settings.publicBaseUrl().replaceAll("/$", "");
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public LinkResponse create(String asciiUrl, String keyDigest) {
    return create(asciiUrl, null, keyDigest);
  }

  public LinkResponse create(String asciiUrl, Instant expiresAt, String keyDigest) {
    String fingerprint = Idempotency.fingerprint(asciiUrl, expiresAt);
    int collisions = 0, keyRaces = 0;
    while (collisions < 5 && keyRaces < 5) {
      try {
        Link link =
            transaction.execute(
                status -> {
                  Instant now = now();
                  if (keyDigest != null) {
                    var existing = repository.findKey(keyDigest);
                    if (existing.isPresent()) {
                      var record = existing.get();
                      if (now.isBefore(record.createdAt().plus(Duration.ofHours(24)))) {
                        if (!record.fingerprint().equals(fingerprint)) {
                          throw new ApiException(
                              409,
                              "IDEMPOTENCY_CONFLICT",
                              "The key was already used for a different request.");
                        }
                        Link original = record.link();
                        return new Link(
                            original.id(),
                            original.code(),
                            original.url(),
                            original.createdAt(),
                            null,
                            original.expiresAt());
                      }
                      if (!repository.removeExpiredKey(
                          keyDigest, record.createdAt(), now.minus(Duration.ofHours(24)))) {
                        throw new KeyChanged();
                      }
                    }
                  }
                  if (expiresAt != null && !expiresAt.isAfter(now)) {
                    throw ExpirationPolicy.invalid();
                  }
                  Link candidate =
                      new Link(
                          UUID.randomUUID(), generator.nextCode(), asciiUrl, now, null, expiresAt);
                  repository.insert(candidate);
                  if (keyDigest != null) {
                    repository.insertKey(keyDigest, fingerprint, candidate);
                  }
                  return candidate;
                });
        return response(link);
      } catch (KeyChanged retry) {
        keyRaces++;
      } catch (DataAccessException failure) {
        String constraint = uniqueConstraint(failure);
        if ("uq_links_code".equals(constraint)) {
          collisions++;
          metrics.counter("links.creation.code.collisions").increment();
        } else if (keyDigest != null && "pk_idempotency_records".equals(constraint)) {
          // TransactionTemplate has rolled the losing candidate back; start a fresh transaction.
          keyRaces++;
        } else {
          throw failure;
        }
      }
    }
    if (collisions == 5) {
      metrics.counter("links.creation.code.exhausted").increment();
    }
    throw ApiException.unavailable();
  }

  public LinkResponse inspect(String code) {
    return response(find(code));
  }

  public String resolve(String code) {
    return resolveObserved(code).url();
  }

  public Resolution resolveObserved(String code) {
    return resolver.resolve(code);
  }

  public void disable(String code) {
    validateCode(code);
    transaction.executeWithoutResult(
        status -> {
          if (!repository.disable(code, now())) {
            throw ApiException.notFound();
          }
        });
    resolver.invalidate(code);
  }

  private Link find(String code) {
    validateCode(code);
    return repository.find(code).orElseThrow(ApiException::notFound);
  }

  private static void validateCode(String code) {
    if (!code.matches("[A-Za-z0-9]{8}")) {
      throw ApiException.notFound();
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private LinkResponse response(Link link) {
    return new LinkResponse(
        link.code(),
        baseUrl + "/r/" + link.code(),
        link.url(),
        link.createdAt(),
        link.disabledAt(),
        link.expiresAt());
  }

  private static String uniqueConstraint(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException postgres
          && "23505".equals(postgres.getSQLState())
          && postgres.getServerErrorMessage() != null) {
        return postgres.getServerErrorMessage().getConstraint();
      }
    }
    return null;
  }

  public record Resolution(UUID linkId, String url, Instant observedAt) {}

  private static final class KeyChanged extends RuntimeException {}
}
