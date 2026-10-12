package com.example.urlshortener.link;

import com.example.urlshortener.api.ApiException;
import com.example.urlshortener.config.WebSettings;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
public class DestinationPolicy {
  private final String publicHost;

  public DestinationPolicy(WebSettings settings) {
    publicHost = comparisonHost(URI.create(settings.publicBaseUrl()).getHost());
  }

  public String validate(String input) {
    try {
      if (input == null
          || input.isEmpty()
          || input.codePointCount(0, input.length()) > 2048
          || !input.equals(input.strip())
          || input
              .codePoints()
              .anyMatch(c -> Character.isISOControl(c) || (c >= 0xd800 && c <= 0xdfff))
          || input.matches("(?is).*%(?:0[0-9a-f]|1[0-9a-f]|7f).*")) {
        throw invalid();
      }
      URI uri = URI.create(input);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) {
        throw invalid();
      }
      String host = comparisonHost(uri.getHost());
      if (host.equals(publicHost) || !publicHost(host)) {
        throw invalid();
      }
      String ascii = uri.toASCIIString();
      if (ascii.length() > 2048) {
        throw invalid();
      }
      return ascii;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static String comparisonHost(String host) {
    String value = host.toLowerCase(Locale.ROOT);
    return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
  }

  private static boolean publicHost(String host) {
    if (host.startsWith("[")) {
      String literal = host.substring(1, host.length() - 1);
      if (literal.contains("%")) {
        return false;
      }
      try {
        // URI has already validated the bracketed literal. Colon-bearing literals cannot trigger
        // DNS.
        InetAddress address = InetAddress.getByName(literal);
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
          return publicIpv4(bytes);
        }
        return (bytes[0] & 0xfe) != 0xfc && !(bytes[0] == 0 && bytes[1] == 0);
      } catch (UnknownHostException invalidLiteral) {
        return false;
      }
    }
    if (host.matches("[0-9.]+")) {
      String[] parts = host.split("\\.", -1);
      if (parts.length != 4) {
        return false;
      }
      byte[] bytes = new byte[4];
      for (int i = 0; i < 4; i++) {
        if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
          return false;
        }
        int value = Integer.parseInt(parts[i]);
        if (value > 255) {
          return false;
        }
        bytes[i] = (byte) value;
      }
      return publicIpv4(bytes);
    }
    if (host.length() > 253
        || !host.contains(".")
        || host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")
        || host.matches(".*\\.(?:localhost|local|internal|lan|home|invalid|test)$")) {
      return false;
    }
    String[] labels = host.split("\\.", -1);
    for (String label : labels) {
      if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
        return false;
      }
    }
    return !labels[labels.length - 1].matches("[0-9]+");
  }

  private static boolean publicIpv4(byte[] bytes) {
    int a = bytes[0] & 255, b = bytes[1] & 255;
    return !(a == 0
        || a == 10
        || a == 127
        || a >= 224
        || (a == 169 && b == 254)
        || (a == 172 && b >= 16 && b <= 31)
        || (a == 192 && b == 168)
        || (a == 100 && b >= 64 && b <= 127)
        || (a == 198 && (b == 18 || b == 19)));
  }

  private static ApiException invalid() {
    return new ApiException(
        400, "INVALID_URL", "An allowed absolute HTTP or HTTPS destination is required.");
  }
}
