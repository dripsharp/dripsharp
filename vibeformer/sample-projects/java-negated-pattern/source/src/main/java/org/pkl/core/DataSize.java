package org.pkl.core;

import static org.pkl.core.DataSizeUnit.*;

import java.util.Objects;

public final class DataSize {
  private final double value;
  private final DataSizeUnit unit;

  public DataSize(double value) {
    this.value = value;
    this.unit = DataSizeUnit.BYTES;
  }

  public DataSize(double value, DataSizeUnit unit) {
    this.value = value;
    this.unit = Objects.requireNonNull(unit, "unit");
  }

  public static DataSize ofBytes(double value) {
    return new DataSize(value, BYTES);
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof DataSize other)) {
      return false;
    }

    return value == other.value && Objects.equals(unit, other.unit);
  }

  public String label() {
    return value == 1 ? (long) value + ".byte." + unit : value + ".bytes." + unit;
  }
}
