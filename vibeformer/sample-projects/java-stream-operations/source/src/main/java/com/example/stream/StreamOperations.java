package com.example.stream;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public final class StreamOperations {
  public static void main(String[] args) {
  }

  public List<String> sortedUnique(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .distinct()
        .sorted()
        .toList();
  }

  public HashSet<String> uniqueSet(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .collect(Collectors.toSet());
  }

  public String joined(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .map(it -> it.trim())
        .collect(Collectors.joining(","));
  }

  public boolean hasEmpty(List<String> names) {
    return names.stream().anyMatch(it -> it.isEmpty());
  }

  public boolean allHaveText(List<String> names) {
    return names.stream().allMatch(it -> !it.isEmpty());
  }

  public boolean noneEmpty(List<String> names) {
    return names.stream().noneMatch(it -> it.isEmpty());
  }

  public Object[] flatten(List<String> names) {
    return names.stream()
        .flatMap(it -> names.stream())
        .toArray();
  }

  public long fixedTotal(List<String> names) {
    return names.stream()
        .mapToLong(it -> 1)
        .sum();
  }

  public Object[] asArray(List<String> names) {
    return names.stream().toArray();
  }
}
