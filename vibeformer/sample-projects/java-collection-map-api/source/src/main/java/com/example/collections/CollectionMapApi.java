package com.example.collections;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CollectionMapApi {
  public int summarize(List<String> names) {
    Map<String, Integer> counts = new HashMap<String, Integer>();
    counts.put("fallback", 1);
    for (String name : names) {
      if (counts.containsKey(name)) {
        counts.put(name, counts.get(name) + 1);
      } else {
        counts.put(name, counts.getOrDefault(name, 0) + 1);
      }
    }

    List<Integer> values = new ArrayList<Integer>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      values.add(entry.getValue());
      if (entry.getKey().isEmpty()) {
        values.add(0);
      }
    }
    for (String key : counts.keySet()) {
      values.add(counts.get(key));
    }
    for (Integer value : counts.values()) {
      values.add(value);
    }

    if (values.isEmpty()) {
      return counts.size();
    }
    if (values.contains(0)) {
      return values.get(0);
    }
    return values.size() + counts.get("fallback");
  }
}
