package org.pkl.core;

public final class DataSizeDemo {
  private DataSizeDemo() {
  }

  public static void main(String[] args) {
    DataSize first = new DataSize(12);
    DataSize second = new DataSize(12);
    first.equals(second);
    first.equals("bytes");
  }
}
