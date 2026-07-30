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
        global::PdfCube.PdfBox.Pdmodel.PDDocument doc);

    void PrepareForDecryption(
        global::PdfCube.PdfBox.Pdmodel.Encryption.PDEncryption encryption,
        global::PdfCube.PdfBox.Cos.COSArray documentIDArray,
        global::PdfCube.PdfBox.Pdmodel.Encryption.DecryptionMaterial
            decryptionMaterial);

    global::PdfCube.PdfBox.Cos.COSBase Decrypt(
        global::PdfCube.PdfBox.Cos.COSBase obj,
        long objNum,
        long genNum);

    void DecryptStream(
        global::PdfCube.PdfBox.Cos.COSStream stream,
        long objNum,
        long genNum);

    void EncryptStream(
        global::PdfCube.PdfBox.Cos.COSStream stream,
        long objNum,
        int genNum);

    void EncryptString(
        global::PdfCube.PdfBox.Cos.COSString value,
        long objNum,
        int genNum);

    int GetKeyLength();

    void SetKeyLength(int keyLen);

    void SetCurrentAccessPermission(
        global::PdfCube.PdfBox.Pdmodel.Encryption.AccessPermission
            currentAccessPermission);

    global::PdfCube.PdfBox.Pdmodel.Encryption.AccessPermission
        GetCurrentAccessPermission();

    bool IsAES();

    void SetAES(bool aesValue);

    bool HasProtectionPolicy();

    sbyte[] GetEncryptionKey();

    void SetEncryptionKey(sbyte[] encryptionKey);
}
