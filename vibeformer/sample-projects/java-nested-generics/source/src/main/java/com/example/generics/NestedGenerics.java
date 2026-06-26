package com.example.generics;

import java.util.List;

public final class NestedGenerics {
  public static void main(String[] args) {
  }

  public List<List<String>> echo(List<List<String>> groups) {
    return groups;
  }
}
