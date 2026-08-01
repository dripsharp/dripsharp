package fixture;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.Parameterized;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Probe {
  static int state;
}

abstract class BaseSpec {
  @BeforeEach
  void baseBeforeEach() {
    if (Probe.state != 0) throw new IllegalStateException("base before-each order");
    Probe.state = 1;
  }

  @BeforeEach
  void overriddenBeforeEach() {
    throw new IllegalStateException("overridden lifecycle method executed");
  }

  @AfterEach
  void baseAfterEach() {
    if (Probe.state != 3) throw new IllegalStateException("base after-each order");
    Probe.state = 4;
  }

  @Test
  void inheritedCase() {
    if (Probe.state != 2) throw new IllegalStateException("inherited case state");
    Probe.state = 3;
  }
}

@Execution(ExecutionMode.CONCURRENT)
class SuiteFixture extends BaseSpec {
  @BeforeAll
  static void beforeAll() {
    Probe.state = 0;
  }

  @BeforeEach
  void beforeEach() {
    if (Probe.state != 1) throw new IllegalStateException("derived before-each order");
    Probe.state = 2;
  }

  @Override
  void overriddenBeforeEach() {}

  @BeforeEach
  void nestedCollision() {}

  @AfterEach
  void afterEach() {
    if (Probe.state != 2) throw new IllegalStateException("derived after-each order");
    Probe.state = 3;
  }

  @AfterAll
  static void afterAll() {
    Probe.state = 5;
  }

  @Test
  @DisplayName("ordinary row")
  void ordinaryBody() {
    int value = 40 + 2;
    if (value != 42) throw new IllegalStateException("ordinary Java body");
  }

  @ParameterizedTest(name = "value {0}")
  @ValueSource(ints = {3, 4})
  void valueRows(int value) {
    if (value < 3) throw new IllegalArgumentException("value row");
  }

  @ParameterizedTest
  @CsvSource({"5, five", "6, six"})
  void csvRows(int value, String name) {
    if (name.length() != 4) throw new IllegalArgumentException("csv row");
  }

  @ParameterizedTest
  @MethodSource("methodRows")
  void memberRows(int value) {
    if (value < 7) throw new IllegalArgumentException("member row");
  }

  static Collection<Integer> methodRows() {
    return Arrays.asList(7, 8);
  }

  @Test
  @Disabled("upstream issue 42")
  void disabledCase() {}

  @Test
  @Disabled
  void disabledWithoutReason() {}

  @Test
  @Timeout(value = 10, unit = TimeUnit.MILLISECONDS)
  void timedCase() {}

  @Test
  void temporaryCase(@TempDir Path directory) {
    if (directory == null) throw new IllegalArgumentException("temp directory");
  }

  @RepeatedTest(3)
  void repeatedCase() {}

  @TestFactory
  Object dynamicCases() {
    return null;
  }

  @Nested
  class Inner {
    @BeforeEach
    void nestedCollision() {}

    @Test
    void nestedCase() {}
  }
}

@Isolated
class IsolatedSpec {
  @Test
  void isolatedCase() {}
}

@ResourceLock("shared-clock")
class ResourceLockedSpec {
  @Test
  void lockedCase() {}
}

class LegacyExpectedSpec {
  @org.junit.Test(expected = IllegalArgumentException.class, timeout = 50)
  public void legacyExpected() {
    throw new IllegalArgumentException("expected");
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
}

@RunWith(Parameterized.class)
class LegacyParameterizedSpec {
  private final int value;

  LegacyParameterizedSpec(int value) {
    this.value = value;
  }

  @Parameterized.Parameters(name = "value={0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{9}, {10}});
  }

  @org.junit.Test
  public void legacyRow() {
    if (value < 9) throw new IllegalArgumentException("legacy row");
  }
}
