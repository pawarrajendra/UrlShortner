package com.example.urlshortener.cache;

import java.util.Optional;

public interface RedirectCache {
  Optional<CachedRedirect> get(String code);

  void put(CachedRedirect value);

  void invalidate(String code);
}
