// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace PdfCube.Preflight.Font.Container
{
    public interface IFontContainer
    {
        void Push(global::PdfCube.Preflight.ValidationResult.ValidationError error);

        void Push(
            global::System.Collections.Generic.IList<
                global::PdfCube.Preflight.ValidationResult.ValidationError> errors);

        global::System.Collections.Generic.IList<
            global::PdfCube.Preflight.ValidationResult.ValidationError> GetAllErrors();

        bool IsValid();

        bool ErrorsAleadyMerged();

        void SetErrorsAlreadyMerged(bool errorsAlreadyMerged);

        bool IsEmbeddedFont();

        void NotEmbedded();

        void CheckGlyphWidth(int code);

        bool HasGlyph(int code);

        void MarkAsValid(int code);

        void MarkAsInvalid(
            int code,
            global::PdfCube.Preflight.Font.Util.GlyphException e);
    }
}

namespace PdfCube.Preflight.Font
{
    public interface IFontValidator
    {
        void Validate();

        global::PdfCube.Preflight.Font.Container.IFontContainer GetFontContainer();
    }
}
