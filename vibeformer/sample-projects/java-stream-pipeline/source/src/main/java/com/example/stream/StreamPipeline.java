package com.example.stream;

import java.util.List;
import java.util.stream.Collectors;

public final class StreamPipeline {
  public static void main(String[] args) {
  }

  public List<String> normalize(List<String> names) {
    return names.stream()
        .filter(it -> !it.isEmpty())
        .map(it -> it.trim())
        .collect(Collectors.toList());
  }
}
