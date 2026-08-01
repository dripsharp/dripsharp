package fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.will;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

interface Clock {
  int tick();
}

class RealClock implements Clock {
  @Override
  public int tick() {
    return 5;
  }
}

@ExtendWith(MockitoExtension.class)
class JavaTestAdapterFixture {
  @Mock Clock annotatedClock;

  static int textLength(String value) {
    return value.length();
  }

  @Test
  void frameworkSemantics() {
    org.junit.Assert.assertEquals("legacy message", 2, 1 + 1);
    Assertions.assertEquals(3, 1 + 2, "jupiter message");
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      throw new IllegalArgumentException("boom");
    });
    int notThrown = Assertions.assertDoesNotThrow(() -> 4, "does not throw message");
    Assertions.assertEquals(4, notThrown);

    org.assertj.core.api.Assertions.assertThat(new int[] {1, 2})
        .as("ordered values")
        .containsExactly(1, 2);
    org.assertj.core.api.Assertions.assertThat(List.of("a", "b"))
        .extracting(JavaTestAdapterFixture::textLength)
        .containsExactly(1, 1);
    org.assertj.core.api.Assertions.assertThat(List.of("a", "b"))
        .allSatisfy(value -> Assertions.assertNotNull(value));
    assertThat("hamcrest reason", "abc", allOf(startsWith("a"), endsWith("c")));

    Clock clock = Mockito.mock(Clock.class);
    given(clock.tick()).willReturn(7);
    Assertions.assertEquals(7, clock.tick(), "stubbed value");
    verify(clock, times(1)).tick();
    then(clock).should().tick();

    Answer<Integer> answer = invocation -> 9;
    will(answer).given(clock).tick();
    Clock spyClock = spy(new RealClock());
    Assertions.assertEquals(5, spyClock.tick());
  }
}
