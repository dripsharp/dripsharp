// Focused ICC profile parsing and color transforms for PdfCube.
// This implementation is authored for PdfCube and uses no third-party codec.
#nullable enable

using System;
using System.Buffers.Binary;
using System.Collections.Generic;

namespace DripSharp.Runtime;

internal sealed class PdfCubeIccProfileData
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
    private readonly Dictionary<uint, IccLut> luts = new();
    private readonly uint colorSpaceSignature;
    private readonly uint pcsSignature;

    internal PdfCubeIccProfileData(sbyte[] profile)
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
            tags.TryAdd(
                signature,
                new TagRecord(checked((int)offset), checked((int)size)));
        }
    }

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
        if (components.Length < NumberOfComponents)
            throw new ArgumentException(
                "The component array is shorter than the ICC color space.",
                nameof(components));

        var source = new float[NumberOfComponents];
        for (var index = 0; index < source.Length; index++)
            source[index] = Math.Clamp(components[index], 0f, 1f);

        var pcs = TryDeviceToPcs(source) ?? MatrixDeviceToXyz(source);
        var xyz = pcsSignature == PcsLab ? LabToXyz(pcs) : pcs;
        return XyzD50ToSrgb(xyz);
    }

    internal float[] FromRgb(float[] rgb)
    {
        ArgumentNullException.ThrowIfNull(rgb);
        if (rgb.Length < 3)
            throw new ArgumentException("RGB input must have three components.", nameof(rgb));
        var xyz = SrgbToXyzD50(rgb);
        var pcs = pcsSignature == PcsLab ? XyzToLab(xyz) : xyz;
        var encoded = EncodePcs(pcs);
        var tag = SelectTag(false);
        if (tag.HasValue)
            return GetLut(tag.Value).Transform(encoded);

        if (NumberOfComponents == 3 && TryMatrixInverse(xyz, out var device))
            return device;
        if (NumberOfComponents == 1)
            return new[] { Math.Clamp(rgb[0] * 0.2126f + rgb[1] * 0.7152f +
                                      rgb[2] * 0.0722f, 0f, 1f) };
        return NaiveFromRgb(rgb, NumberOfComponents);
    }

    private float[]? TryDeviceToPcs(float[] components)
    {
        var tag = SelectTag(true);
        if (!tag.HasValue) return null;
        var encoded = GetLut(tag.Value).Transform(components);
        return DecodePcs(encoded);
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

    private IccLut GetLut(uint signature)
    {
        if (luts.TryGetValue(signature, out var existing)) return existing;
        var tag = tags[signature];
        var parsed = IccLut.Parse(data, tag.Offset, tag.Size);
        if (parsed.InputChannels != (signature >> 24 == 0x41
                ? NumberOfComponents
                : 3))
        {
            throw new ArgumentException(
                $"ICC LUT `{Signature(signature)}` has an unexpected input channel count.");
        }
        luts.Add(signature, parsed);
        return parsed;
    }

    private float[] MatrixDeviceToXyz(float[] source)
    {
        if (NumberOfComponents == 1)
        {
            var curve = ReadCurve(0x6b545243); // kTRC
            var white = ReadXyz(0x77747074); // wtpt
            var luminance = curve.Evaluate(source[0]);
            return new[]
            {
                white[0] * luminance,
                white[1] * luminance,
                white[2] * luminance
            };
        }
        if (NumberOfComponents == 3)
        {
            var red = ReadXyz(0x7258595a); // rXYZ
            var green = ReadXyz(0x6758595a); // gXYZ
            var blue = ReadXyz(0x6258595a); // bXYZ
            var r = ReadCurve(0x72545243).Evaluate(source[0]); // rTRC
            var g = ReadCurve(0x67545243).Evaluate(source[1]); // gTRC
            var b = ReadCurve(0x62545243).Evaluate(source[2]); // bTRC
            return new[]
            {
                red[0] * r + green[0] * g + blue[0] * b,
                red[1] * r + green[1] * g + blue[1] * b,
                red[2] * r + green[2] * g + blue[2] * b
            };
        }
        if (NumberOfComponents == 4)
        {
            return SrgbToXyzD50(new[]
            {
                1f - Math.Min(1f, source[0] + source[3]),
                1f - Math.Min(1f, source[1] + source[3]),
                1f - Math.Min(1f, source[2] + source[3])
            });
        }
        throw new ArgumentException(
            "ICC profile has neither a usable LUT nor a matrix/TRC transform.");
    }

    private bool TryMatrixInverse(float[] xyz, out float[] result)
    {
        result = Array.Empty<float>();
        if (!tags.ContainsKey(0x7258595a) ||
            !tags.ContainsKey(0x6758595a) ||
            !tags.ContainsKey(0x6258595a))
            return false;

        var r = ReadXyz(0x7258595a);
        var g = ReadXyz(0x6758595a);
        var b = ReadXyz(0x6258595a);
        var determinant =
            r[0] * (g[1] * b[2] - b[1] * g[2]) -
            g[0] * (r[1] * b[2] - b[1] * r[2]) +
            b[0] * (r[1] * g[2] - g[1] * r[2]);
        if (Math.Abs(determinant) < 1e-12f) return false;
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
        var linear = new[]
        {
            inverse[0] * xyz[0] + inverse[1] * xyz[1] + inverse[2] * xyz[2],
            inverse[3] * xyz[0] + inverse[4] * xyz[1] + inverse[5] * xyz[2],
            inverse[6] * xyz[0] + inverse[7] * xyz[1] + inverse[8] * xyz[2]
        };
        result = new[]
        {
            ReadCurve(0x72545243).Invert(linear[0]),
            ReadCurve(0x67545243).Invert(linear[1]),
            ReadCurve(0x62545243).Invert(linear[2])
        };
        return true;
    }

    private float[] DecodePcs(float[] encoded)
    {
        if (encoded.Length < 3)
            throw new ArgumentException("ICC LUT did not produce a three-channel PCS value.");
        if (pcsSignature == PcsXyz)
            return new[] { encoded[0] * 1.9999695f, encoded[1] * 1.9999695f,
                           encoded[2] * 1.9999695f };
        var scale = MajorVersion < 4 ? 65535f / 65280f : 1f;
        return new[]
        {
            Math.Clamp(encoded[0] * scale, 0f, 1f) * 100f,
            Math.Clamp(encoded[1] * scale, 0f, 1f) * 255f - 128f,
            Math.Clamp(encoded[2] * scale, 0f, 1f) * 255f - 128f
        };
    }

    private float[] EncodePcs(float[] pcs)
    {
        if (pcsSignature == PcsXyz)
            return new[]
            {
                Math.Clamp(pcs[0] / 1.9999695f, 0f, 1f),
                Math.Clamp(pcs[1] / 1.9999695f, 0f, 1f),
                Math.Clamp(pcs[2] / 1.9999695f, 0f, 1f)
            };
        var scale = MajorVersion < 4 ? 65280f / 65535f : 1f;
        return new[]
        {
            Math.Clamp(pcs[0] / 100f, 0f, 1f) * scale,
            Math.Clamp((pcs[1] + 128f) / 255f, 0f, 1f) * scale,
            Math.Clamp((pcs[2] + 128f) / 255f, 0f, 1f) * scale
        };
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

    private static float[] LabToXyz(float[] lab)
    {
        var fy = (lab[0] + 16f) / 116f;
        var fx = fy + lab[1] / 500f;
        var fz = fy - lab[2] / 200f;
        static float Inverse(float value)
        {
            const float delta = 6f / 29f;
            return value > delta
                ? value * value * value
                : 3f * delta * delta * (value - 4f / 29f);
        }
        return new[]
        {
            0.9642f * Inverse(fx),
            Inverse(fy),
            0.8249f * Inverse(fz)
        };
    }

    private static float[] XyzToLab(float[] xyz)
    {
        static float Forward(float value)
        {
            const float delta = 6f / 29f;
            var threshold = delta * delta * delta;
            return value > threshold
                ? MathF.Cbrt(value)
                : value / (3f * delta * delta) + 4f / 29f;
        }
        var fx = Forward(xyz[0] / 0.9642f);
        var fy = Forward(xyz[1]);
        var fz = Forward(xyz[2] / 0.8249f);
        return new[] { 116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz) };
    }

    private static float[] XyzD50ToSrgb(float[] xyz)
    {
        var x = 0.9555766f * xyz[0] - 0.0230393f * xyz[1] + 0.0631636f * xyz[2];
        var y = -0.0282895f * xyz[0] + 1.0099416f * xyz[1] + 0.0210077f * xyz[2];
        var z = 0.0122982f * xyz[0] - 0.0204830f * xyz[1] + 1.3299098f * xyz[2];
        var red = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z;
        var green = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z;
        var blue = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z;
        static float Gamma(float value) =>
            Math.Clamp(value <= 0.0031308f
                ? 12.92f * value
                : 1.055f * MathF.Pow(Math.Max(0f, value), 1f / 2.4f) - 0.055f,
                0f,
                1f);
        return new[] { Gamma(red), Gamma(green), Gamma(blue) };
    }

    private static float[] SrgbToXyzD50(float[] rgb)
    {
        static float Linear(float value)
        {
            value = Math.Clamp(value, 0f, 1f);
            return value <= 0.04045f
                ? value / 12.92f
                : MathF.Pow((value + 0.055f) / 1.055f, 2.4f);
        }
        var r = Linear(rgb[0]);
        var g = Linear(rgb[1]);
        var b = Linear(rgb[2]);
        var x65 = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b;
        var y65 = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b;
        var z65 = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b;
        return new[]
        {
            1.0478112f * x65 + 0.0228866f * y65 - 0.0501270f * z65,
            0.0295424f * x65 + 0.9904844f * y65 - 0.0170491f * z65,
            -0.0092345f * x65 + 0.0150436f * y65 + 0.7521316f * z65
        };
    }

    private static float[] NaiveFromRgb(float[] rgb, int components)
    {
        if (components == 4)
        {
            var black = 1f - Math.Max(rgb[0], Math.Max(rgb[1], rgb[2]));
            if (black >= 1f) return new[] { 0f, 0f, 0f, 1f };
            var scale = 1f - black;
            return new[]
            {
                (1f - rgb[0] - black) / scale,
                (1f - rgb[1] - black) / scale,
                (1f - rgb[2] - black) / scale,
                black
            };
        }
        var result = new float[components];
        for (var index = 0; index < result.Length; index++)
            result[index] = rgb[Math.Min(index, 2)];
        return result;
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
        string.Create(
            4,
            value,
            static (characters, signature) =>
            {
                characters[0] = (char)(signature >> 24);
                characters[1] = (char)(signature >> 16);
                characters[2] = (char)(signature >> 8);
                characters[3] = (char)signature;
            });

    private readonly record struct TagRecord(int Offset, int Size);

    private sealed class IccCurve
    {
        private readonly Func<float, float> evaluate;

        private IccCurve(Func<float, float> evaluate) => this.evaluate = evaluate;

        internal float Evaluate(float value) =>
            Math.Clamp(evaluate(Math.Clamp(value, 0f, 1f)), 0f, 1f);

        internal float Invert(float value)
        {
            value = Math.Clamp(value, 0f, 1f);
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
                if (count == 0) return new IccCurve(value => value);
                if (count == 1)
                {
                    if (size < 14) throw new ArgumentException("ICC gamma curve is truncated.");
                    var gamma = BinaryPrimitives.ReadUInt16BigEndian(
                        source.AsSpan(offset + 12, 2)) / 256f;
                    return new IccCurve(value => MathF.Pow(value, gamma));
                }
                if (12L + count * 2L > size)
                    throw new ArgumentException("ICC sampled curve is truncated.");
                var samples = new float[count];
                for (var index = 0; index < count; index++)
                {
                    samples[index] = BinaryPrimitives.ReadUInt16BigEndian(
                        source.AsSpan(offset + 12 + index * 2, 2)) / 65535f;
                }
                return new IccCurve(value => Interpolate(samples, value));
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
            return new IccCurve(value => functionType switch
            {
                0 => MathF.Pow(value, p[0]),
                1 => value >= -p[2] / p[1]
                    ? MathF.Pow(p[1] * value + p[2], p[0])
                    : 0f,
                2 => value >= -p[2] / p[1]
                    ? MathF.Pow(p[1] * value + p[2], p[0]) + p[3]
                    : p[3],
                3 => value >= p[4]
                    ? MathF.Pow(p[1] * value + p[2], p[0])
                    : p[3] * value,
                4 => value >= p[4]
                    ? MathF.Pow(p[1] * value + p[2], p[0]) + p[5]
                    : p[3] * value + p[6],
                _ => value
            });
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

        internal float[] Transform(float[] input)
        {
            if (input.Length < InputChannels)
                throw new ArgumentException("ICC LUT input has too few components.");
            var mapped = new float[InputChannels];
            for (var channel = 0; channel < mapped.Length; channel++)
                mapped[channel] = Interpolate(inputTables[channel], input[channel]);
            if (InputChannels == 3)
            {
                var x = mapped[0];
                var y = mapped[1];
                var z = mapped[2];
                mapped[0] = Math.Clamp(matrix[0] * x + matrix[1] * y + matrix[2] * z, 0f, 1f);
                mapped[1] = Math.Clamp(matrix[3] * x + matrix[4] * y + matrix[5] * z, 0f, 1f);
                mapped[2] = Math.Clamp(matrix[6] * x + matrix[7] * y + matrix[8] * z, 0f, 1f);
            }

            var low = new int[InputChannels];
            var fractions = new float[InputChannels];
            for (var channel = 0; channel < InputChannels; channel++)
            {
                var position = Math.Clamp(mapped[channel], 0f, 1f) * (gridPoints - 1);
                low[channel] = Math.Min((int)position, gridPoints - 2);
                fractions[channel] = position - low[channel];
            }
            var clutResult = new float[OutputChannels];
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
            var result = new float[OutputChannels];
            for (var channel = 0; channel < result.Length; channel++)
                result[channel] = Interpolate(outputTables[channel], clutResult[channel]);
            return result;
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
        var position = Math.Clamp(value, 0f, 1f) * (table.Length - 1);
        var lower = Math.Min((int)position, table.Length - 2);
        var fraction = position - lower;
        return table[lower] + (table[lower + 1] - table[lower]) * fraction;
    }
}
