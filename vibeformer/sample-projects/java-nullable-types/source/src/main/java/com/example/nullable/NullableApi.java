package com.example.nullable;

import org.jspecify.annotations.Nullable;

public final class NullableApi {
  private final @Nullable String label;

  public NullableApi(@Nullable String label) {
    this.label = label;
  }

  public @Nullable String getLabel() {
    return label;
  }

  public static void demo() {
    new NullableApi(null).getLabel();
  }
}
