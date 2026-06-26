package org.pkl.parser;

public final class SpanDemo {
  private SpanDemo() {
  }

  public static void main(String[] args) {
    Span start = new Span(1, 3);
    Span end = start.move(4).grow(2);
    start.endWith(end);
    start.adjacent(end);
    start.stopIndex();
    start.stopIndexExclusive();
    start.stopSpan();
    start.contains(end);
  }
}
