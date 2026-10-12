package com.example.urlshortener.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!migrate")
public class LinkRepository {
  private final JdbcTemplate jdbc;
  private static final String COLUMNS =
      "l.id,l.code,l.destination_url,l.created_at,l.disabled_at,l.expires_at";

  public LinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Link> find(String code) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM links l WHERE l.code=?", (rs, row) -> link(rs), code)
        .stream()
        .findFirst();
  }

  public Optional<PrimaryRead> findForRedirect(String code) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + ",statement_timestamp() AS primary_read_at FROM links l WHERE l.code=?",
            (rs, row) ->
                new PrimaryRead(
                    link(rs), rs.getObject("primary_read_at", OffsetDateTime.class).toInstant()),
            code)
        .stream()
        .findFirst();
  }

  public record PrimaryRead(Link link, Instant readAt) {}

  public Optional<KeyRecord> findKey(String digest) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + ",k.request_fingerprint,k.created_at AS key_created_at FROM idempotency_records k JOIN links l ON l.id=k.link_id WHERE k.key_digest=?",
            (rs, row) ->
                new KeyRecord(
                    link(rs),
                    rs.getString("request_fingerprint"),
                    rs.getObject("key_created_at", OffsetDateTime.class).toInstant()),
            digest)
        .stream()
        .findFirst();
  }

  public boolean removeExpiredKey(String digest, Instant createdAt, Instant cutoff) {
    return jdbc.update(
            "DELETE FROM idempotency_records WHERE key_digest=? AND created_at=? AND created_at<=?",
            digest,
            timestamp(createdAt),
            timestamp(cutoff))
        == 1;
  }

  public void insert(Link link) {
    jdbc.update(
        "INSERT INTO links(id,code,destination_url,created_at,expires_at) VALUES (?,?,?,?,?)",
        link.id(),
        link.code(),
        link.url(),
        timestamp(link.createdAt()),
        link.expiresAt() == null ? null : timestamp(link.expiresAt()));
  }

  public void insertKey(String digest, String fingerprint, Link link) {
    jdbc.update(
        "INSERT INTO idempotency_records(key_digest,request_fingerprint,link_id,created_at) VALUES (?,?,?,?)",
        digest,
        fingerprint,
        link.id(),
        timestamp(link.createdAt()));
  }

  public boolean disable(String code, Instant time) {
    return jdbc.update(
            "UPDATE links SET disabled_at=COALESCE(disabled_at,?) WHERE code=?",
            timestamp(time),
            code)
        == 1;
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Link link(ResultSet rs) throws SQLException {
    OffsetDateTime expiry = rs.getObject("expires_at", OffsetDateTime.class);
    OffsetDateTime disabled = rs.getObject("disabled_at", OffsetDateTime.class);
    return new Link(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("destination_url"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        disabled == null ? null : disabled.toInstant(),
        expiry == null ? null : expiry.toInstant());
  }

  public record KeyRecord(Link link, String fingerprint, Instant createdAt) {}
}
