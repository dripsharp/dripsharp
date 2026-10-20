// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused ICC profile parsing and color transforms for PdfCarton.
// This implementation is authored for PdfCarton and uses no third-party codec.
// Parsed transform state (LUTs, TRC curves and matrix columns) is built once per
// profile instance on first use and then shared read-only, so per-pixel
// conversions allocate nothing and concurrent renders may share one profile.
#nullable enable

using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Threading;

namespace DripSharp.Runtime;

internal sealed class PdfCartonIccProfileData
{
    private const uint ProfileSignature = 0x61637370; // acsp
    private const uint PcsXyz = 0x58595a20; // XYZ
    private const uint PcsLab = 0x4c616220; // Lab
    private const uint Lut8Type = 0x6d667431; // mft1
    private const uint Lut16Type = 0x6d667432; // mft2
    private const uint CurveType = 0x63757276; // curv
    private const uint ParametricCurveType = 0x70617261; // para
    private const uint XyzType = 0x58595a20; // XYZ

    private readonly byte[] data;
    private readonly Dictionary<uint, TagRecord> tags;
    private readonly uint colorSpaceSignature;
    private readonly uint pcsSignature;
    // Each lazy caches its result or its parse failure, so a malformed tag
    // fails every conversion consistently without being parsed again.
    private readonly Lazy<IccLut?> deviceToPcsLut;
    private readonly Lazy<IccLut?> pcsToDeviceLut;
    private readonly Lazy<MatrixTransform> matrixTransform;
    private readonly Lazy<InverseMatrixTransform?> inverseMatrixTransform;

    internal PdfCartonIccProfileData(sbyte[] profile)
    {
        ArgumentNullException.ThrowIfNull(profile);
        data = new byte[profile.Length];
        for (var index = 0; index < profile.Length; index++)
            data[index] = unchecked((byte)profile[index]);

        if (data.Length < 132)
            throw new ArgumentException("ICC profile is shorter than its header and tag count.");
        var declaredSize = ReadUInt32(data, 0);
        if (declaredSize < 132 || declaredSize > data.Length)
            throw new ArgumentException("ICC profile size is invalid or truncated.");
        if (ReadUInt32(data, 36) != ProfileSignature)
            throw new ArgumentException("ICC profile signature is missing.");

        DeviceClassSignature = ReadUInt32(data, 12);
        colorSpaceSignature = ReadUInt32(data, 16);
        pcsSignature = ReadUInt32(data, 20);
        if (pcsSignature is not PcsXyz and not PcsLab)
            throw new ArgumentException(
                $"Unsupported ICC profile connection space `{Signature(pcsSignature)}`.");

        NumberOfComponents = Components(colorSpaceSignature);
        ColorSpaceType = ColorSpaceTypeFor(colorSpaceSignature);
        MajorVersion = data[8];
        MinorVersion = data[9];
        RenderingIntent = checked((int)ReadUInt32(data, 64));
        if (RenderingIntent is < 0 or > 3)
            throw new ArgumentException("ICC rendering intent is outside the defined range.");

        var tagCount = checked((int)ReadUInt32(data, 128));
        var tableLength = checked(132L + tagCount * 12L);
        if (tableLength > declaredSize)
            throw new ArgumentException("ICC tag table is truncated.");
        tags = new Dictionary<uint, TagRecord>(tagCount);
        for (var index = 0; index < tagCount; index++)
        {
            var entry = 132 + index * 12;
            var signature = ReadUInt32(data, entry);
            var offset = ReadUInt32(data, entry + 4);
            var size = ReadUInt32(data, entry + 8);
            if (size < 8 || offset > declaredSize ||
                size > declaredSize - offset ||
                offset + size > data.Length)
            {
                throw new ArgumentException(
                    $"ICC tag `{Signature(signature)}` is outside the profile.");
            }
            if (!tags.ContainsKey(signature))
            {
                tags.Add(
                    signature,
                    new TagRecord(checked((int)offset), checked((int)size)));
            }
        }

        deviceToPcsLut = new Lazy<IccLut?>(
            () => LoadLut(true),
            LazyThreadSafetyMode.ExecutionAndPublication);
        pcsToDeviceLut = new Lazy<IccLut?>(
            () => LoadLut(false),
            LazyThreadSafetyMode.ExecutionAndPublication);
        matrixTransform = new Lazy<MatrixTransform>(
            LoadMatrixTransform,
            LazyThreadSafetyMode.ExecutionAndPublication);
        inverseMatrixTransform = new Lazy<InverseMatrixTransform?>(
            LoadInverseMatrixTransform,
            LazyThreadSafetyMode.ExecutionAndPublication);
    }

    // ICC color spaces have at most fifteen channels; span callers size
    // FromRgb output buffers with this bound.
    internal const int MaximumComponents = 15;

    internal uint DeviceClassSignature { get; }
    internal int NumberOfComponents { get; }
    internal int ColorSpaceType { get; }
    internal int MajorVersion { get; }
    internal int MinorVersion { get; }
    internal int RenderingIntent { get; }

    internal sbyte[] GetData()
    {
        var result = new sbyte[data.Length];
        for (var index = 0; index < data.Length; index++)
            result[index] = unchecked((sbyte)data[index]);
        return result;
    }

    internal sbyte[] GetHeader()
    {
        var result = new sbyte[128];
        for (var index = 0; index < result.Length; index++)
            result[index] = unchecked((sbyte)data[index]);
        return result;
    }

    internal sbyte[] GetTag(int signature)
    {
        var unsignedSignature = unchecked((uint)signature);
        if (!tags.TryGetValue(unsignedSignature, out var tag))
            throw new ArgumentException(
                $"ICC profile does not contain tag `{Signature(unsignedSignature)}`.");
        var result = new sbyte[tag.Size];
        for (var index = 0; index < result.Length; index++)
            result[index] = unchecked((sbyte)data[tag.Offset + index]);
        return result;
    }

    internal float[] ToRgb(float[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        var result = new float[3];
        ToRgb(components, result);
        return result;
    }

    internal void ToRgb(ReadOnlySpan<float> components, Span<float> rgb)
    {
        if (components.Length < NumberOfComponents)
            throw new ArgumentException(
                "The component array is shorter than the ICC color space.",
                nameof(components));

        Span<float> source = stackalloc float[NumberOfComponents];
        for (var index = 0; index < source.Length; index++)
            source[index] = JavaCompat.Clamp(components[index], 0f, 1f);

        float x;
        float y;
        float z;
        var lut = deviceToPcsLut.Value;
        if (lut is not null)
        {
            Span<float> encoded = stackalloc float[lut.OutputChannels];
            lut.Transform(source, encoded);
            DecodePcs(encoded, out x, out y, out z);
        }
        else
        {
            matrixTransform.Value.DeviceToXyz(source, out x, out y, out z);
        }
        if (pcsSignature == PcsLab) LabToXyz(ref x, ref y, ref z);
        XyzD50ToSrgb(x, y, z, rgb);
    }

    internal float[] FromRgb(float[] rgb)
    {
        ArgumentNullException.ThrowIfNull(rgb);
        Span<float> buffer = stackalloc float[MaximumComponents];
        var count = FromRgb(rgb, buffer);
        return buffer.Slice(0, count).ToArray();
    }

    // Writes the device components into output and returns how many were
    // written. Output must hold MaximumComponents values.
    internal int FromRgb(ReadOnlySpan<float> rgb, Span<float> output)
    {
        if (rgb.Length < 3)
            throw new ArgumentException("RGB input must have three components.", nameof(rgb));
        if (output.Length < MaximumComponents)
            throw new ArgumentException("The output buffer is too small.", nameof(output));
        SrgbToXyzD50(rgb, out var x, out var y, out var z);
        var pcsX = x;
        var pcsY = y;
        var pcsZ = z;
        if (pcsSignature == PcsLab) XyzToLab(ref pcsX, ref pcsY, ref pcsZ);
        Span<float> encoded = stackalloc float[3];
        EncodePcs(pcsX, pcsY, pcsZ, encoded);
        var lut = pcsToDeviceLut.Value;
        if (lut is not null)
        {
            lut.Transform(encoded, output);
            return lut.OutputChannels;
        }

        if (NumberOfComponents == 3)
        {
            var inverse = inverseMatrixTransform.Value;
            if (inverse is not null)
            {
                inverse.XyzToDevice(x, y, z, output);
                return 3;
            }
        }
        if (NumberOfComponents == 1)
        {
            output[0] = JavaCompat.Clamp(rgb[0] * 0.2126f + rgb[1] * 0.7152f +
                                         rgb[2] * 0.0722f, 0f, 1f);
            return 1;
        }
        return NaiveFromRgb(rgb, NumberOfComponents, output);
    }

    private uint? SelectTag(bool deviceToPcs)
    {
        var prefix = deviceToPcs ? (uint)0x41324230 : 0x42324130; // A2B0/B2A0
        var intentIndex = RenderingIntent switch
        {
            0 => 0,
            1 => 1,
            2 => 2,
            3 => 1,
            _ => 0
        };
        var requested = prefix + (uint)intentIndex;
        if (tags.ContainsKey(requested)) return requested;
        return tags.ContainsKey(prefix) ? prefix : null;
    }

    private IccLut? LoadLut(bool deviceToPcs)
    {
        var selected = SelectTag(deviceToPcs);
        if (!selected.HasValue) return null;
        var signature = selected.Value;
        var tag = tags[signature];
        var parsed = IccLut.Parse(data, tag.Offset, tag.Size);
        if (parsed.InputChannels != (signature >> 24 == 0x41
                ? NumberOfComponents
                : 3))
        {
            throw new ArgumentException(
                $"ICC LUT `{Signature(signature)}` has an unexpected input channel count.");
        }
        return parsed;
    }

    private MatrixTransform LoadMatrixTransform()
    {
        if (NumberOfComponents == 1)
        {
            var curve = ReadCurve(0x6b545243); // kTRC
            var white = ReadXyz(0x77747074); // wtpt
            return MatrixTransform.Gray(curve, white);
        }
        if (NumberOfComponents == 3)
        {
            var red = ReadXyz(0x7258595a); // rXYZ
            var green = ReadXyz(0x6758595a); // gXYZ
            var blue = ReadXyz(0x6258595a); // bXYZ
            var redCurve = ReadCurve(0x72545243); // rTRC
            var greenCurve = ReadCurve(0x67545243); // gTRC
            var blueCurve = ReadCurve(0x62545243); // bTRC
            return MatrixTransform.Rgb(red, green, blue, redCurve, greenCurve, blueCurve);
        }
        if (NumberOfComponents == 4) return MatrixTransform.NaiveCmyk;
        throw new ArgumentException(
            "ICC profile has neither a usable LUT nor a matrix/TRC transform.");
    }

    private InverseMatrixTransform? LoadInverseMatrixTransform()
    {
        if (!tags.ContainsKey(0x7258595a) ||
            !tags.ContainsKey(0x6758595a) ||
            !tags.ContainsKey(0x6258595a))
            return null;

        var r = ReadXyz(0x7258595a);
        var g = ReadXyz(0x6758595a);
        var b = ReadXyz(0x6258595a);
        var determinant =
            r[0] * (g[1] * b[2] - b[1] * g[2]) -
            g[0] * (r[1] * b[2] - b[1] * r[2]) +
            b[0] * (r[1] * g[2] - g[1] * r[2]);
        if (Math.Abs(determinant) < 1e-12f) return null;
        var inverse = new[]
        {
            (g[1] * b[2] - b[1] * g[2]) / determinant,
            (b[0] * g[2] - g[0] * b[2]) / determinant,
            (g[0] * b[1] - b[0] * g[1]) / determinant,
            (b[1] * r[2] - r[1] * b[2]) / determinant,
            (r[0] * b[2] - b[0] * r[2]) / determinant,
            (b[0] * r[1] - r[0] * b[1]) / determinant,
            (r[1] * g[2] - g[1] * r[2]) / determinant,
            (g[0] * r[2] - r[0] * g[2]) / determinant,
            (r[0] * g[1] - g[0] * r[1]) / determinant
        };
        return new InverseMatrixTransform(
            inverse,
            ReadCurve(0x72545243),
            ReadCurve(0x67545243),
            ReadCurve(0x62545243));
    }

    private void DecodePcs(ReadOnlySpan<float> encoded, out float x, out float y, out float z)
    {
        if (encoded.Length < 3)
            throw new ArgumentException("ICC LUT did not produce a three-channel PCS value.");
        if (pcsSignature == PcsXyz)
        {
            x = encoded[0] * 1.9999695f;
            y = encoded[1] * 1.9999695f;
            z = encoded[2] * 1.9999695f;
            return;
        }
        var scale = MajorVersion < 4 ? 65535f / 65280f : 1f;
        x = JavaCompat.Clamp(encoded[0] * scale, 0f, 1f) * 100f;
        y = JavaCompat.Clamp(encoded[1] * scale, 0f, 1f) * 255f - 128f;
        z = JavaCompat.Clamp(encoded[2] * scale, 0f, 1f) * 255f - 128f;
    }

    private void EncodePcs(float x, float y, float z, Span<float> encoded)
    {
        if (pcsSignature == PcsXyz)
        {
            encoded[0] = JavaCompat.Clamp(x / 1.9999695f, 0f, 1f);
            encoded[1] = JavaCompat.Clamp(y / 1.9999695f, 0f, 1f);
            encoded[2] = JavaCompat.Clamp(z / 1.9999695f, 0f, 1f);
            return;
        }
        var scale = MajorVersion < 4 ? 65280f / 65535f : 1f;
        encoded[0] = JavaCompat.Clamp(x / 100f, 0f, 1f) * scale;
        encoded[1] = JavaCompat.Clamp((y + 128f) / 255f, 0f, 1f) * scale;
        encoded[2] = JavaCompat.Clamp((z + 128f) / 255f, 0f, 1f) * scale;
    }

    private float[] ReadXyz(uint signature)
    {
        if (!tags.TryGetValue(signature, out var tag) ||
            tag.Size < 20 ||
            ReadUInt32(data, tag.Offset) != XyzType)
            throw new ArgumentException(
                $"ICC profile is missing XYZ tag `{Signature(signature)}`.");
        return new[]
        {
            ReadS15Fixed16(data, tag.Offset + 8),
            ReadS15Fixed16(data, tag.Offset + 12),
            ReadS15Fixed16(data, tag.Offset + 16)
        };
    }

    private IccCurve ReadCurve(uint signature)
    {
        if (!tags.TryGetValue(signature, out var tag))
            throw new ArgumentException(
                $"ICC profile is missing curve tag `{Signature(signature)}`.");
        return IccCurve.Parse(data, tag.Offset, tag.Size);
    }

    private static void LabToXyz(ref float l, ref float a, ref float b)
    {
        var fy = (l + 16f) / 116f;
        var fx = fy + a / 500f;
        var fz = fy - b / 200f;
        static float Inverse(float value)
        {
            const float delta = 6f / 29f;
            return value > delta
                ? value * value * value
                : 3f * delta * delta * (value - 4f / 29f);
        }
        l = 0.9642f * Inverse(fx);
        a = Inverse(fy);
        b = 0.8249f * Inverse(fz);
    }

    private static void XyzToLab(ref float x, ref float y, ref float z)
    {
        static float Forward(float value)
        {
            const float delta = 6f / 29f;
            var threshold = delta * delta * delta;
            return value > threshold
                ? JavaCompat.Cbrt(value)
                : value / (3f * delta * delta) + 4f / 29f;
        }
        var fx = Forward(x / 0.9642f);
        var fy = Forward(y);
        var fz = Forward(z / 0.8249f);
        x = 116f * fy - 16f;
        y = 500f * (fx - fy);
        z = 200f * (fy - fz);
    }

    internal static void XyzD50ToSrgb(float xyz0, float xyz1, float xyz2, Span<float> rgb)
    {
        var x = 0.9555766f * xyz0 - 0.0230393f * xyz1 + 0.0631636f * xyz2;
        var y = -0.0282895f * xyz0 + 1.0099416f * xyz1 + 0.0210077f * xyz2;
        var z = 0.0122982f * xyz0 - 0.0204830f * xyz1 + 1.3299098f * xyz2;
        var red = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z;
        var green = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z;
        var blue = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z;
        static float Gamma(float value) =>
            JavaCompat.Clamp(value <= 0.0031308f
                ? 12.92f * value
                : 1.055f * JavaCompat.Pow(Math.Max(0f, value), 1f / 2.4f) - 0.055f,
                0f,
                1f);
        rgb[0] = Gamma(red);
        rgb[1] = Gamma(green);
        rgb[2] = Gamma(blue);
    }

    internal static void SrgbToXyzD50(
        ReadOnlySpan<float> rgb,
        out float x,
        out float y,
        out float z)
    {
        static float Linear(float value)
        {
            value = JavaCompat.Clamp(value, 0f, 1f);
            return value <= 0.04045f
                ? value / 12.92f
                : JavaCompat.Pow((value + 0.055f) / 1.055f, 2.4f);
        }
        var r = Linear(rgb[0]);
        var g = Linear(rgb[1]);
        var b = Linear(rgb[2]);
        var x65 = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b;
        var y65 = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b;
        var z65 = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b;
        x = 1.0478112f * x65 + 0.0228866f * y65 - 0.0501270f * z65;
        y = 0.0295424f * x65 + 0.9904844f * y65 - 0.0170491f * z65;
        z = -0.0092345f * x65 + 0.0150436f * y65 + 0.7521316f * z65;
    }

    private static int NaiveFromRgb(ReadOnlySpan<float> rgb, int components, Span<float> output)
    {
        if (components == 4)
        {
            var black = 1f - Math.Max(rgb[0], Math.Max(rgb[1], rgb[2]));
            if (black >= 1f)
            {
                output[0] = 0f;
                output[1] = 0f;
                output[2] = 0f;
                output[3] = 1f;
                return 4;
            }
            var scale = 1f - black;
            output[0] = (1f - rgb[0] - black) / scale;
            output[1] = (1f - rgb[1] - black) / scale;
            output[2] = (1f - rgb[2] - black) / scale;
            output[3] = black;
            return 4;
        }
        for (var index = 0; index < components; index++)
            output[index] = rgb[Math.Min(index, 2)];
        return components;
    }

    private static int Components(uint signature) => signature switch
    {
        0x47524159 => 1, // GRAY
        0x32434c52 => 2, // 2CLR
        0x52474220 or 0x434d5920 or 0x58595a20 or 0x4c616220 or
            0x4c757620 or 0x59436272 or 0x59787920 or 0x48535620 or
            0x484c5320 => 3,
        0x434d594b or 0x34434c52 => 4,
        0x35434c52 => 5,
        0x36434c52 => 6,
        0x37434c52 => 7,
        0x38434c52 => 8,
        0x39434c52 => 9,
        0x41434c52 => 10,
        0x42434c52 => 11,
        0x43434c52 => 12,
        0x44434c52 => 13,
        0x45434c52 => 14,
        0x46434c52 => 15,
        _ => throw new ArgumentException(
            $"Unsupported ICC color-space signature `{Signature(signature)}`.")
    };

    private static int ColorSpaceTypeFor(uint signature) => signature switch
    {
        0x58595a20 => 0,
        0x4c616220 => 1,
        0x4c757620 => 2,
        0x59436272 => 3,
        0x59787920 => 4,
        0x52474220 => JavaColorSpace.TYPE_RGB,
        0x47524159 => JavaColorSpace.TYPE_GRAY,
        0x48535620 => 7,
        0x484c5320 => 8,
        0x434d594b => JavaColorSpace.TYPE_CMYK,
        0x434d5920 => 11,
        _ => Components(signature) + 10
    };

    private static uint ReadUInt32(byte[] source, int offset) =>
        BinaryPrimitives.ReadUInt32BigEndian(source.AsSpan(offset, 4));

    private static float ReadS15Fixed16(byte[] source, int offset) =>
        BinaryPrimitives.ReadInt32BigEndian(source.AsSpan(offset, 4)) / 65536f;

    private static string Signature(uint value) =>
        new(new[]
        {
            (char)(value >> 24),
            (char)(value >> 16),
            (char)(value >> 8),
            (char)value
        });

    private readonly record struct TagRecord(int Offset, int Size);

    // Device-to-PCS matrix/TRC state: the TRC curves and XYZ columns are
    // read once, and DeviceToXyz evaluates them without allocating.
    private sealed class MatrixTransform
    {
        private readonly int kind;
        private readonly IccCurve? first;
        private readonly IccCurve? second;
        private readonly IccCurve? third;
        private readonly float[] columns;

        private MatrixTransform(
            int kind,
            IccCurve? first,
            IccCurve? second,
            IccCurve? third,
            float[] columns)
        {
            this.kind = kind;
            this.first = first;
            this.second = second;
            this.third = third;
            this.columns = columns;
        }

        internal static readonly MatrixTransform NaiveCmyk =
            new(4, null, null, null, Array.Empty<float>());

        internal static MatrixTransform Gray(IccCurve curve, float[] white) =>
            new(1, curve, null, null, white);

        internal static MatrixTransform Rgb(
            float[] red,
            float[] green,
            float[] blue,
            IccCurve redCurve,
            IccCurve greenCurve,
            IccCurve blueCurve) =>
            new(
                3,
                redCurve,
                greenCurve,
                blueCurve,
                new[]
                {
                    red[0], red[1], red[2],
                    green[0], green[1], green[2],
                    blue[0], blue[1], blue[2]
                });

        internal void DeviceToXyz(
            ReadOnlySpan<float> source,
            out float x,
            out float y,
            out float z)
        {
            if (kind == 1)
            {
                var luminance = first!.Evaluate(source[0]);
                x = columns[0] * luminance;
                y = columns[1] * luminance;
                z = columns[2] * luminance;
                return;
            }
            if (kind == 3)
            {
                var r = first!.Evaluate(source[0]);
                var g = second!.Evaluate(source[1]);
                var b = third!.Evaluate(source[2]);
                x = columns[0] * r + columns[3] * g + columns[6] * b;
                y = columns[1] * r + columns[4] * g + columns[7] * b;
                z = columns[2] * r + columns[5] * g + columns[8] * b;
                return;
            }
            Span<float> rgb = stackalloc float[3];
            rgb[0] = 1f - Math.Min(1f, source[0] + source[3]);
            rgb[1] = 1f - Math.Min(1f, source[1] + source[3]);
            rgb[2] = 1f - Math.Min(1f, source[2] + source[3]);
            SrgbToXyzD50(rgb, out x, out y, out z);
        }
    }

    private sealed class InverseMatrixTransform
    {
        private readonly float[] inverse;
        private readonly IccCurve red;
        private readonly IccCurve green;
        private readonly IccCurve blue;

        internal InverseMatrixTransform(
            float[] inverse,
            IccCurve red,
            IccCurve green,
            IccCurve blue)
        {
            this.inverse = inverse;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        internal void XyzToDevice(float x, float y, float z, Span<float> output)
        {
            var linear0 = inverse[0] * x + inverse[1] * y + inverse[2] * z;
            var linear1 = inverse[3] * x + inverse[4] * y + inverse[5] * z;
            var linear2 = inverse[6] * x + inverse[7] * y + inverse[8] * z;
            output[0] = red.Invert(linear0);
            output[1] = green.Invert(linear1);
            output[2] = blue.Invert(linear2);
        }
    }

    // An immutable TRC curve. Evaluation is a switch over the parsed curve
    // kind, so it allocates no delegate, closure or sample array per call.
    private sealed class IccCurve
    {
        private const int IdentityCurve = 0;
        private const int GammaCurve = 1;
        private const int SampledCurve = 2;
        private const int ParametricCurve = 3;

        private readonly int kind;
        private readonly float gamma;
        private readonly int functionType;
        private readonly float[] values;

        private IccCurve(int kind, float gamma, int functionType, float[] values)
        {
            this.kind = kind;
            this.gamma = gamma;
            this.functionType = functionType;
            this.values = values;
        }

        internal float Evaluate(float value) =>
            JavaCompat.Clamp(EvaluateRaw(JavaCompat.Clamp(value, 0f, 1f)), 0f, 1f);

        private float EvaluateRaw(float value)
        {
            switch (kind)
            {
                case IdentityCurve:
                    return value;
                case GammaCurve:
                    return JavaCompat.Pow(value, gamma);
                case SampledCurve:
                    return Interpolate(values, value);
            }
            var p = values;
            return functionType switch
            {
                0 => JavaCompat.Pow(value, p[0]),
                1 => value >= -p[2] / p[1]
                    ? JavaCompat.Pow(p[1] * value + p[2], p[0])
                    : 0f,
                2 => value >= -p[2] / p[1]
                    ? JavaCompat.Pow(p[1] * value + p[2], p[0]) + p[3]
                    : p[3],
                3 => value >= p[4]
                    ? JavaCompat.Pow(p[1] * value + p[2], p[0])
                    : p[3] * value,
                4 => value >= p[4]
                    ? JavaCompat.Pow(p[1] * value + p[2], p[0]) + p[5]
                    : p[3] * value + p[6],
                _ => value
            };
        }

        internal float Invert(float value)
        {
            value = JavaCompat.Clamp(value, 0f, 1f);
            var low = 0f;
            var high = 1f;
            for (var iteration = 0; iteration < 24; iteration++)
            {
                var middle = (low + high) * 0.5f;
                if (Evaluate(middle) < value) low = middle;
                else high = middle;
            }
            return (low + high) * 0.5f;
        }

        internal static IccCurve Parse(byte[] source, int offset, int size)
        {
            if (size < 12)
                throw new ArgumentException("ICC curve tag is truncated.");
            var type = ReadUInt32(source, offset);
            if (type == CurveType)
            {
                var count = checked((int)ReadUInt32(source, offset + 8));
                if (count == 0)
                    return new IccCurve(IdentityCurve, 0f, 0, Array.Empty<float>());
                if (count == 1)
                {
                    if (size < 14) throw new ArgumentException("ICC gamma curve is truncated.");
                    var gamma = BinaryPrimitives.ReadUInt16BigEndian(
                        source.AsSpan(offset + 12, 2)) / 256f;
                    return new IccCurve(GammaCurve, gamma, 0, Array.Empty<float>());
                }
                if (12L + count * 2L > size)
                    throw new ArgumentException("ICC sampled curve is truncated.");
                var samples = new float[count];
                for (var index = 0; index < count; index++)
                {
                    samples[index] = BinaryPrimitives.ReadUInt16BigEndian(
                        source.AsSpan(offset + 12 + index * 2, 2)) / 65535f;
                }
                return new IccCurve(SampledCurve, 0f, 0, samples);
            }
            if (type != ParametricCurveType || size < 16)
                throw new ArgumentException("Unsupported ICC curve tag type.");
            var functionType = BinaryPrimitives.ReadUInt16BigEndian(
                source.AsSpan(offset + 8, 2));
            var parameterCount = functionType switch
            {
                0 => 1,
                1 => 3,
                2 => 4,
                3 => 5,
                4 => 7,
                _ => throw new ArgumentException("Unsupported ICC parametric curve function.")
            };
            if (12 + parameterCount * 4 > size)
                throw new ArgumentException("ICC parametric curve is truncated.");
            var p = new float[parameterCount];
            for (var index = 0; index < p.Length; index++)
                p[index] = ReadS15Fixed16(source, offset + 12 + index * 4);
            return new IccCurve(ParametricCurve, 0f, functionType, p);
        }
    }

    private sealed class IccLut
    {
        private readonly int gridPoints;
        private readonly float[][] inputTables;
        private readonly float[] clut;
        private readonly float[][] outputTables;
        private readonly float[] matrix;

        private IccLut(
            int inputChannels,
            int outputChannels,
            int gridPoints,
            float[][] inputTables,
            float[] clut,
            float[][] outputTables,
            float[] matrix)
        {
            InputChannels = inputChannels;
            OutputChannels = outputChannels;
            this.gridPoints = gridPoints;
            this.inputTables = inputTables;
            this.clut = clut;
            this.outputTables = outputTables;
            this.matrix = matrix;
        }

        internal int InputChannels { get; }
        internal int OutputChannels { get; }

        // Writes OutputChannels values into output without allocating.
        internal void Transform(ReadOnlySpan<float> input, Span<float> output)
        {
            if (input.Length < InputChannels)
                throw new ArgumentException("ICC LUT input has too few components.");
            if (output.Length < OutputChannels)
                throw new ArgumentException("ICC LUT output buffer is too small.");
            Span<float> mapped = stackalloc float[InputChannels];
            for (var channel = 0; channel < mapped.Length; channel++)
                mapped[channel] = Interpolate(inputTables[channel], input[channel]);
            if (InputChannels == 3)
            {
                var x = mapped[0];
                var y = mapped[1];
                var z = mapped[2];
                mapped[0] = JavaCompat.Clamp(matrix[0] * x + matrix[1] * y + matrix[2] * z, 0f, 1f);
                mapped[1] = JavaCompat.Clamp(matrix[3] * x + matrix[4] * y + matrix[5] * z, 0f, 1f);
                mapped[2] = JavaCompat.Clamp(matrix[6] * x + matrix[7] * y + matrix[8] * z, 0f, 1f);
            }

            Span<int> low = stackalloc int[InputChannels];
            Span<float> fractions = stackalloc float[InputChannels];
            for (var channel = 0; channel < InputChannels; channel++)
            {
                var position = JavaCompat.Clamp(mapped[channel], 0f, 1f) * (gridPoints - 1);
                low[channel] = Math.Min((int)position, gridPoints - 2);
                fractions[channel] = position - low[channel];
            }
            Span<float> clutResult = stackalloc float[OutputChannels];
            clutResult.Clear();
            var cornerCount = 1 << InputChannels;
            for (var corner = 0; corner < cornerCount; corner++)
            {
                var weight = 1f;
                var cell = 0;
                for (var channel = 0; channel < InputChannels; channel++)
                {
                    var upper = (corner & 1 << channel) != 0;
                    weight *= upper ? fractions[channel] : 1f - fractions[channel];
                    cell = checked(cell * gridPoints + low[channel] + (upper ? 1 : 0));
                }
                var offset = checked(cell * OutputChannels);
                for (var channel = 0; channel < OutputChannels; channel++)
                    clutResult[channel] += clut[offset + channel] * weight;
            }
            for (var channel = 0; channel < OutputChannels; channel++)
                output[channel] = Interpolate(outputTables[channel], clutResult[channel]);
        }

        internal static IccLut Parse(byte[] source, int offset, int size)
        {
            if (size < 52) throw new ArgumentException("ICC LUT tag is truncated.");
            var type = ReadUInt32(source, offset);
            if (type is not Lut8Type and not Lut16Type)
                throw new ArgumentException(
                    $"Unsupported ICC LUT type `{Signature(type)}`.");
            var inputChannels = source[offset + 8];
            var outputChannels = source[offset + 9];
            var gridPoints = source[offset + 10];
            if (inputChannels is < 1 or > 15 ||
                outputChannels is < 1 or > 15 ||
                gridPoints < 2)
                throw new ArgumentException("ICC LUT dimensions are invalid.");
            var matrix = new float[9];
            for (var index = 0; index < matrix.Length; index++)
                matrix[index] = ReadS15Fixed16(source, offset + 12 + index * 4);

            var inputEntries = type == Lut8Type
                ? 256
                : BinaryPrimitives.ReadUInt16BigEndian(source.AsSpan(offset + 48, 2));
            var outputEntries = type == Lut8Type
                ? 256
                : BinaryPrimitives.ReadUInt16BigEndian(source.AsSpan(offset + 50, 2));
            if (inputEntries < 2 || outputEntries < 2)
                throw new ArgumentException("ICC LUT tables must contain at least two entries.");
            var cursor = offset + (type == Lut8Type ? 48 : 52);
            var bytesPerEntry = type == Lut8Type ? 1 : 2;
            var inputTables = ReadTables(
                source, ref cursor, offset + size, inputChannels, inputEntries, bytesPerEntry);
            var cells = 1L;
            for (var index = 0; index < inputChannels; index++)
                cells = checked(cells * gridPoints);
            var clutEntries = checked((int)(cells * outputChannels));
            var clut = ReadValues(
                source, ref cursor, offset + size, clutEntries, bytesPerEntry);
            var outputTables = ReadTables(
                source, ref cursor, offset + size, outputChannels, outputEntries, bytesPerEntry);
            return new IccLut(
                inputChannels,
                outputChannels,
                gridPoints,
                inputTables,
                clut,
                outputTables,
                matrix);
        }

        private static float[][] ReadTables(
            byte[] source,
            ref int cursor,
            int end,
            int count,
            int entries,
            int bytesPerEntry)
        {
            var tables = new float[count][];
            for (var index = 0; index < count; index++)
                tables[index] = ReadValues(source, ref cursor, end, entries, bytesPerEntry);
            return tables;
        }

        private static float[] ReadValues(
            byte[] source,
            ref int cursor,
            int end,
            int count,
            int bytesPerEntry)
        {
            if ((long)cursor + (long)count * bytesPerEntry > end)
                throw new ArgumentException("ICC LUT data is truncated.");
            var values = new float[count];
            for (var index = 0; index < count; index++)
            {
                values[index] = bytesPerEntry == 1
                    ? source[cursor++] / 255f
                    : BinaryPrimitives.ReadUInt16BigEndian(
                        source.AsSpan(Advance(ref cursor, 2), 2)) / 65535f;
            }
            return values;
        }

        private static int Advance(ref int cursor, int amount)
        {
            var previous = cursor;
            cursor += amount;
            return previous;
        }
    }

    private static float Interpolate(float[] table, float value)
    {
        var position = JavaCompat.Clamp(value, 0f, 1f) * (table.Length - 1);
        var lower = Math.Min((int)position, table.Length - 2);
        var fraction = position - lower;
        return table[lower] + (table[lower + 1] - table[lower]) * fraction;
    }
}
