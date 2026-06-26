package org.pkl.core.runtime;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public final class LayerCollections {
  private LayerCollections() {
  }

  public static LinkedList<String> linked(List<String> values) {
    return values.stream()
        .filter(value -> value != null)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  public static LinkedHashSet<String> orderedSet(List<String> values) {
    return values.stream()
        .filter(value -> value != null)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public static void main(String[] args) {
  }
}
