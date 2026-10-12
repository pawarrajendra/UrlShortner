package com.example.urlshortener.link;

import java.security.SecureRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
public class RandomCodeGenerator implements CodeGenerator {
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private final SecureRandom random;

  public RandomCodeGenerator() {
    this(new SecureRandom());
  }

  public RandomCodeGenerator(SecureRandom random) {
    this.random = random;
  }

  @Override
  public String nextCode() {
    StringBuilder code = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      code.append(ALPHABET.charAt(random.nextInt(62)));
    }
    return code.toString();
  }
}
