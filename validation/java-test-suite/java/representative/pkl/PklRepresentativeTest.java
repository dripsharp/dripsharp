// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package representative.pkl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Minimal Java fixture retaining the lifecycle, Jupiter, and AssertJ surfaces
 * selected from the pinned Pkl Java tests.
 */
public class PklRepresentativeTest {
  @BeforeEach
  void setUp() {
    Assertions.assertTrue(true, "Pkl before-each");
  }

  @Test
  void assertjBehavior() {
    int value = 2;
    assertThat(value).as("positive value").isEqualTo(2);
    Assertions.assertTrue(value > 0, "Pkl assertion");
  }
}
