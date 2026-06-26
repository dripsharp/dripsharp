package org.pkl.core;

public final class DataSizeDemo {
  private DataSizeDemo() {
  }

  public static void main(String[] args) {
    DataSize first = new DataSize(12);
    DataSize second = new DataSize(12);
    DataSize bytes = DataSize.ofBytes(1);
    Version version = new Version(1, 2, 3, "beta");
    first.equals(second);
    first.equals("bytes");
    first.label();
    first.inWholeBytes();
    first.hashCode();
    bytes.label();
    version.hashCode();
    version.smallerMajor(new Version(2, 0, 0, "release"));
  }
}
