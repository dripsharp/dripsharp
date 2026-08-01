// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package representative.pdfcarton;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Minimal Java fixture retaining every ValueSource row selected from PDFBox's
 * pinned glyph-substitution test.
 */
public class PdfCartonParameterRepresentativeTest {
  @ParameterizedTest(name = "script {0}")
  @ValueSource(strings = {"DFLT", "bopo", "copt", "cyrl", "grek", "hebr", "latn"})
  void scriptRows(String scriptTag) {
    Assertions.assertTrue(scriptTag.length() == 4, "PDFBox script tag");
  }
}
