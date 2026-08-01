// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package representative.sqltrellis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Minimal Java fixture retaining the Jupiter and Hamcrest surfaces selected
 * from the pinned JSqlParser test suite, including upstream disablement.
 */
public class SqlTrellisRepresentativeTest {
  @Test
  void hamcrestBehavior() {
    assertThat("SQL prefix", "select", startsWith("sel"));
    assertThat("SQL value type", "select", instanceOf(String.class));
    Assertions.assertEquals("SELECT", "select".toUpperCase(), "SQL normalization");
  }

  @Disabled("upstream-disabled representative row")
  @Test
  void upstreamDisabled() {
    Assertions.fail("disabled SQL test executed");
  }
}
