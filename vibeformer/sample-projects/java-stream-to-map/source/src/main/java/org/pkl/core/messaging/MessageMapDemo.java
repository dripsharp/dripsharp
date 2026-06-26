package org.pkl.core.messaging;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MessageMapDemo {
  private MessageMapDemo() {
  }

  public static Map<String, String> unpackStringMap(List<String> entries) {
    return entries.stream()
        .filter(entry -> entry != null)
        .collect(Collectors.toMap(entry -> entry, entry -> entry.trim()));
  }

  public static void main(String[] args) {
  }
}
