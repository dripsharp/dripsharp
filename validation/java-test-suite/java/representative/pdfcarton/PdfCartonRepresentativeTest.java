// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package representative.pdfcarton;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

interface Clock {
  int tick();
}

class RealClock implements Clock {
  @Override
  public int tick() {
    return 5;
  }
}

/**
 * Minimal Java fixture retaining the Jupiter and Mockito operations selected
 * from the pinned PDFBox test suite.
 */
public class PdfCartonRepresentativeTest {
  @Test
  void mockitoBehavior() {
    Clock clock = Mockito.mock(Clock.class);
    given(clock.tick()).willReturn(7);
    Assertions.assertEquals(7, clock.tick(), "stubbed PDFBox value");
    verify(clock, times(1)).tick();
    then(clock).should().tick();

    Clock realClock = spy(new RealClock());
    Assertions.assertEquals(5, realClock.tick(), "PDFBox spy value");
  }
}
