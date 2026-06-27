package com.example.statements;

import java.util.List;

public final class ControlFlow {
  public int sum(List<Integer> values) {
    int total = 0;
    try {
      for (Integer value : values) {
        total = total + value;
      }
    } catch (IllegalArgumentException ex) {
      total = 0;
    } finally {
      total = total;
    }
    return total;
  }
}
