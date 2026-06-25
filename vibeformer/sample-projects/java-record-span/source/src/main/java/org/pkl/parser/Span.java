package org.pkl.parser;

public record Span(int charIndex, int length) {
  public Span endWith(Span end) {
    return new Span(charIndex, end.charIndex - charIndex + end.length);
  }

  public boolean adjacent(Span other) {
    return charIndex + length == other.charIndex;
  }

  public int stopIndex() {
    return charIndex + length - 1;
  }

  public int stopIndexExclusive() {
    return charIndex + length;
  }

  public Span stopSpan() {
    return new Span(charIndex + length - 1, 1);
  }

  public Span move(int amount) {
    return new Span(charIndex + amount, length);
  }

  public Span grow(int amount) {
    return new Span(charIndex, length + amount);
  }

  public boolean contains(Span other) {
    return charIndex <= other.charIndex && other.charIndex + other.length <= charIndex + length;
  }
}
