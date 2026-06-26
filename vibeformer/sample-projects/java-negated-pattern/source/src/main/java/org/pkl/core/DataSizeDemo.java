package org.pkl.core;

public final class DataSizeDemo {
  private DataSizeDemo() {
  }

  public static void main(String[] args) {
    DataSize first = new DataSize(12);
    DataSize second = new DataSize(12);
    DataSize bytes = DataSize.ofBytes(1);
    first.equals(second);
    first.equals("bytes");
    first.label();
    first.inWholeBytes();
    bytes.label();
  }
}
