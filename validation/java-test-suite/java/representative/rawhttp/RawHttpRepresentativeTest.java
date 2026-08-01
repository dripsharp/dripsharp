// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package representative.rawhttp;

import org.junit.Test;

/**
 * Minimal Java fixture retaining the JUnit 4 assertion and expected-exception
 * surfaces selected from the pinned RawHTTP test suite.
 */
public class RawHttpRepresentativeTest {
  @Test(expected = IllegalArgumentException.class)
  public void expectedException() {
    org.junit.Assert.assertEquals("RawHTTP assertion message", 2, 1 + 1);
    throw new IllegalArgumentException("expected RawHTTP failure");
  }
}
