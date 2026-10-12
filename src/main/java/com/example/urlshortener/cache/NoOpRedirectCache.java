package com.example.urlshortener.cache;

import java.util.Optional;

public class NoOpRedirectCache implements RedirectCache {
  @Override
  public Optional<CachedRedirect> get(String code) {
    return Optional.empty();
  }

  @Override
  public void put(CachedRedirect value) {}

  @Override
  public void invalidate(String code) {}
}
