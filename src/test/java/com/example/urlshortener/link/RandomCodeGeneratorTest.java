package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {
  @Test
  void usesEightUniformBoundedDrawsAndPreservesAlphabetBoundaries() {
    SecureRandom random = mock(SecureRandom.class);
    when(random.nextInt(62)).thenReturn(0, 9, 10, 35, 36, 61, 0, 61);
    assertThat(new RandomCodeGenerator(random).nextCode()).isEqualTo("09AZaz0z");
  }
}
