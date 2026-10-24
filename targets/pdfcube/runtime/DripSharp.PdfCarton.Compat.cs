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
