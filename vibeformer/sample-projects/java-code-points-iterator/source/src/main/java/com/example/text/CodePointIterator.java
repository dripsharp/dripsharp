package com.example.text;

public final class CodePointIterator {
  public static void main(String[] args) {
  }

  public int firstCodePoint(String value) {
    var iterator = value.codePoints().iterator();
    if (iterator.hasNext()) {
      return iterator.nextInt();
    }
    return 0;
  }
}
