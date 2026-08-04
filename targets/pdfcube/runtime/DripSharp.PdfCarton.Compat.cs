// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Java generic erasure contract for PDFBox security handlers.
#nullable disable

namespace DripSharp.Runtime;

public interface PdfBoxSecurityHandler
{
    bool IsDecryptMetadata();

    void SetCustomSecureRandom(
        global::DripSharp.Runtime.JavaRandom customSecureRandom);

    void PrepareDocumentForEncryption(
        global::DripSharp.PdfCarton.Pdmodel.PDDocument doc);

    void PrepareForDecryption(
        global::DripSharp.PdfCarton.Pdmodel.Encryption.PDEncryption encryption,
        global::DripSharp.PdfCarton.Cos.COSArray documentIDArray,
        global::DripSharp.PdfCarton.Pdmodel.Encryption.DecryptionMaterial
            decryptionMaterial);

    global::DripSharp.PdfCarton.Cos.COSBase Decrypt(
        global::DripSharp.PdfCarton.Cos.COSBase obj,
        long objNum,
        long genNum);

    void DecryptStream(
        global::DripSharp.PdfCarton.Cos.COSStream stream,
        long objNum,
        long genNum);

    void EncryptStream(
        global::DripSharp.PdfCarton.Cos.COSStream stream,
        long objNum,
        int genNum);

    void EncryptString(
        global::DripSharp.PdfCarton.Cos.COSString value,
        long objNum,
        int genNum);

    int GetKeyLength();

    void SetKeyLength(int keyLen);

    void SetCurrentAccessPermission(
        global::DripSharp.PdfCarton.Pdmodel.Encryption.AccessPermission
            currentAccessPermission);

    global::DripSharp.PdfCarton.Pdmodel.Encryption.AccessPermission
        GetCurrentAccessPermission();

    bool IsAES();

    void SetAES(bool aesValue);

    bool HasProtectionPolicy();

    sbyte[] GetEncryptionKey();

    void SetEncryptionKey(sbyte[] encryptionKey);
}

internal static class PdfBoxTextCompatibility
{
    internal static string NormalizeVisualWord(string word, bool sortByPosition)
    {
        global::System.ArgumentNullException.ThrowIfNull(word);
        var normalizedWord = new global::System.Text.StringBuilder(word.Length * 2);
        for (var index = 0; index < word.Length; index++)
        {
            var character = word[index];
            if (IsNormalizedPresentationForm(character))
            {
                if (character == '\ufdf2' && index > 0 &&
                    word[index - 1] is '\u0627' or '\ufe8d')
                {
                    normalizedWord.Append("\u0644\u0644\u0647");
                    continue;
                }

                var normalized = character.ToString()
                    .Normalize(global::System.Text.NormalizationForm.FormKC)
                    .Trim();
                if (character >= '\ufb1d' && normalized.Length > 1)
                {
                    normalized = ReverseUtf16(normalized);
                }
                normalizedWord.Append(normalized);
                continue;
            }

            normalizedWord.Append(character);
        }
        var normalizedResult = normalizedWord.ToString();
        return sortByPosition
            ? ReorderCombiningMarks(normalizedResult)
            : normalizedResult;
    }

    private static bool IsNormalizedPresentationForm(char value) =>
        value is >= '\ufb00' and <= '\ufdff' or >= '\ufe70' and <= '\ufeff';

    private static string ReverseUtf16(string value)
    {
        var characters = value.ToCharArray();
        global::System.Array.Reverse(characters);
        return new string(characters);
    }

    private static string ReorderCombiningMarks(string value)
    {
        var reordered = new global::System.Text.StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (!IsCombiningMark(value[index]))
            {
                reordered.Append(value[index]);
                continue;
            }

            var markStart = index;
            while (index + 1 < value.Length && IsCombiningMark(value[index + 1]))
            {
                index++;
            }
            if (index + 1 < value.Length && IsArabicLetter(value[index + 1]))
            {
                reordered.Append(value[index + 1]);
                reordered.Append(value, markStart, index - markStart + 1);
                index++;
            }
            else
            {
                reordered.Append(value, markStart, index - markStart + 1);
            }
        }
        return reordered.ToString();
    }

    private static bool IsCombiningMark(char value) =>
        global::System.Globalization.CharUnicodeInfo.GetUnicodeCategory(value) is
            global::System.Globalization.UnicodeCategory.NonSpacingMark or
            global::System.Globalization.UnicodeCategory.SpacingCombiningMark or
            global::System.Globalization.UnicodeCategory.EnclosingMark;

    private static bool IsArabicLetter(char value) =>
        value is >= '\u0600' and <= '\u06ff' &&
        global::System.Globalization.CharUnicodeInfo.GetUnicodeCategory(value) is
            global::System.Globalization.UnicodeCategory.UppercaseLetter or
            global::System.Globalization.UnicodeCategory.LowercaseLetter or
            global::System.Globalization.UnicodeCategory.TitlecaseLetter or
            global::System.Globalization.UnicodeCategory.ModifierLetter or
            global::System.Globalization.UnicodeCategory.OtherLetter;
}
