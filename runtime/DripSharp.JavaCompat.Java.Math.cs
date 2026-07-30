// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Math

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
enum JavaRoundingMode
{
    Up,
    Down,
    Ceiling,
    Floor,
    HalfUp,
    HalfDown,
    HalfEven,
    Unnecessary
}

internal static class JavaStrictMath
{
    private const int ExponentSignificandBits = 0x7fff_ffff;
    private const int ExponentBits = 0x7ff0_0000;
    private const double Huge = 1.0e300;

    private static int High(double value) =>
        unchecked((int)(BitConverter.DoubleToInt64Bits(value) >> 32));

    private static int Low(double value) =>
        unchecked((int)BitConverter.DoubleToInt64Bits(value));

    private static double WithHigh(double value, int high)
    {
        var bits = BitConverter.DoubleToInt64Bits(value);
        return BitConverter.Int64BitsToDouble(
            (bits & 0x0000_0000_ffff_ffffL) | ((long)high << 32));
    }

    private static double HighLow(int high, int low) =>
        BitConverter.Int64BitsToDouble(
            ((long)high << 32) | unchecked((uint)low));

    private const double Sin1 = -1.66666666666666324348e-01;
    private const double Sin2 = 8.33333333332248946124e-03;
    private const double Sin3 = -1.98412698298579493134e-04;
    private const double Sin4 = 2.75573137070700676789e-06;
    private const double Sin5 = -2.50507602534068634195e-08;
    private const double Sin6 = 1.58969099521155010221e-10;

    private static double KernelSin(double x, double y, bool hasTail)
    {
        var ix = High(x) & ExponentSignificandBits;
        if (ix < 0x3e40_0000 && (int)x == 0) return x;
        var z = x * x;
        var v = z * x;
        var r = Sin2 + z * (Sin3 + z * (Sin4 + z * (Sin5 + z * Sin6)));
        return !hasTail
            ? x + v * (Sin1 + z * r)
            : x - ((z * (0.5 * y - v * r) - y) - v * Sin1);
    }

    private const double Cos1 = 4.16666666666666019037e-02;
    private const double Cos2 = -1.38888888888741095749e-03;
    private const double Cos3 = 2.48015872894767294178e-05;
    private const double Cos4 = -2.75573143513906633035e-07;
    private const double Cos5 = 2.08757232129817482790e-09;
    private const double Cos6 = -1.13596475577881948265e-11;

    private static double KernelCos(double x, double y)
    {
        var ix = High(x) & ExponentSignificandBits;
        if (ix < 0x3e40_0000 && (int)x == 0) return 1.0;
        var z = x * x;
        var r = z * (Cos1 + z * (Cos2 + z * (Cos3 + z * (Cos4 + z * (Cos5 + z * Cos6)))));
        if (ix < 0x3fd3_3333)
            return 1.0 - (0.5 * z - (z * r - x * y));
        var qx = ix > 0x3fe9_0000
            ? 0.28125
            : HighLow(ix - 0x0020_0000, 0);
        var hz = 0.5 * z - qx;
        var a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    private static readonly int[] PiOverTwoHighWords =
    {
        0x3ff921fb, 0x400921fb, 0x4012d97c, 0x401921fb, 0x401f6a7a, 0x4022d97c,
        0x4025fdbb, 0x402921fb, 0x402c463a, 0x402f6a7a, 0x4031475c, 0x4032d97c,
        0x40346b9c, 0x4035fdbb, 0x40378fdb, 0x403921fb, 0x403ab41b, 0x403c463a,
        0x403dd85a, 0x403f6a7a, 0x40407e4c, 0x4041475c, 0x4042106c, 0x4042d97c,
        0x4043a28c, 0x40446b9c, 0x404534ac, 0x4045fdbb, 0x4046c6cb, 0x40478fdb,
        0x404858eb, 0x404921fb
    };

    private const double InversePiOverTwo = 6.36619772367581382433e-01;
    private const double PiOverTwo1 = 1.57079632673412561417e+00;
    private const double PiOverTwo1Tail = 6.07710050650619224932e-11;
    private const double PiOverTwo2 = 6.07710050630396597660e-11;
    private const double PiOverTwo2Tail = 2.02226624879595063154e-21;
    private const double PiOverTwo3 = 2.02226624871116645580e-21;
    private const double PiOverTwo3Tail = 8.47842766036889956997e-32;

    private static int ReducePiOverTwo(double x, double[] remainder)
    {
        var hx = High(x);
        var ix = hx & ExponentSignificandBits;
        if (ix <= 0x3fe9_21fb)
        {
            remainder[0] = x;
            remainder[1] = 0;
            return 0;
        }
        if (ix < 0x4002_d97c)
        {
            if (hx > 0)
            {
                var z = x - PiOverTwo1;
                if (ix != 0x3ff9_21fb)
                {
                    remainder[0] = z - PiOverTwo1Tail;
                    remainder[1] = (z - remainder[0]) - PiOverTwo1Tail;
                }
                else
                {
                    z -= PiOverTwo2;
                    remainder[0] = z - PiOverTwo2Tail;
                    remainder[1] = (z - remainder[0]) - PiOverTwo2Tail;
                }
                return 1;
            }
            else
            {
                var z = x + PiOverTwo1;
                if (ix != 0x3ff9_21fb)
                {
                    remainder[0] = z + PiOverTwo1Tail;
                    remainder[1] = (z - remainder[0]) + PiOverTwo1Tail;
                }
                else
                {
                    z += PiOverTwo2;
                    remainder[0] = z + PiOverTwo2Tail;
                    remainder[1] = (z - remainder[0]) + PiOverTwo2Tail;
                }
                return -1;
            }
        }
        if (ix > 0x4139_21fb) return int.MinValue;

        var t = Math.Abs(x);
        var n = (int)(t * InversePiOverTwo + 0.5);
        var fn = (double)n;
        var r = t - fn * PiOverTwo1;
        var w = fn * PiOverTwo1Tail;
        if (n < 32 && ix != PiOverTwoHighWords[n - 1])
        {
            remainder[0] = r - w;
        }
        else
        {
            var j = ix >> 20;
            remainder[0] = r - w;
            var i = j - ((High(remainder[0]) >> 20) & 0x7ff);
            if (i > 16)
            {
                t = r;
                w = fn * PiOverTwo2;
                r = t - w;
                w = fn * PiOverTwo2Tail - ((t - r) - w);
                remainder[0] = r - w;
                i = j - ((High(remainder[0]) >> 20) & 0x7ff);
                if (i > 49)
                {
                    t = r;
                    w = fn * PiOverTwo3;
                    r = t - w;
                    w = fn * PiOverTwo3Tail - ((t - r) - w);
                    remainder[0] = r - w;
                }
            }
        }
        remainder[1] = (r - remainder[0]) - w;
        if (hx >= 0) return n;
        remainder[0] = -remainder[0];
        remainder[1] = -remainder[1];
        return -n;
    }

    internal static double Sin(double x)
    {
        var ix = High(x) & ExponentSignificandBits;
        if (ix <= 0x3fe9_21fb) return KernelSin(x, 0, false);
        if (ix >= ExponentBits) return x - x;
        var remainder = new double[2];
        var n = ReducePiOverTwo(x, remainder);
        if (n == int.MinValue) return Math.Sin(x);
        return (n & 3) switch
        {
            0 => KernelSin(remainder[0], remainder[1], true),
            1 => KernelCos(remainder[0], remainder[1]),
            2 => -KernelSin(remainder[0], remainder[1], true),
            _ => -KernelCos(remainder[0], remainder[1])
        };
    }

    internal static double Cos(double x)
    {
        var ix = High(x) & ExponentSignificandBits;
        if (ix <= 0x3fe9_21fb) return KernelCos(x, 0);
        if (ix >= ExponentBits) return x - x;
        var remainder = new double[2];
        var n = ReducePiOverTwo(x, remainder);
        if (n == int.MinValue) return Math.Cos(x);
        return (n & 3) switch
        {
            0 => KernelCos(remainder[0], remainder[1]),
            1 => -KernelSin(remainder[0], remainder[1], true),
            2 => -KernelCos(remainder[0], remainder[1]),
            _ => KernelSin(remainder[0], remainder[1], true)
        };
    }

    private static readonly double[] AtanHigh =
    {
        4.63647609000806093515e-01,
        7.85398163397448278999e-01,
        9.82793723247329054082e-01,
        1.57079632679489655800e+00
    };

    private static readonly double[] AtanLow =
    {
        2.26987774529616870924e-17,
        3.06161699786838301793e-17,
        1.39033110312309984516e-17,
        6.12323399573676603587e-17
    };

    private static readonly double[] AtanCoefficients =
    {
        3.33333333333329318027e-01,
        -1.99999999998764832476e-01,
        1.42857142725034663711e-01,
        -1.11111104054623557880e-01,
        9.09088713343650656196e-02,
        -7.69187620504482999495e-02,
        6.66107313738753120669e-02,
        -5.83357013379057348645e-02,
        4.97687799461593236017e-02,
        -3.65315727442169155270e-02,
        1.62858201153657823623e-02
    };

    private static double Atan(double x)
    {
        var hx = High(x);
        var ix = hx & ExponentSignificandBits;
        int id;
        if (ix >= 0x4410_0000)
        {
            if (ix > ExponentBits || (ix == ExponentBits && Low(x) != 0))
                return x + x;
            return hx > 0
                ? AtanHigh[3] + AtanLow[3]
                : -AtanHigh[3] - AtanLow[3];
        }
        if (ix < 0x3fdc_0000)
        {
            if (ix < 0x3e20_0000 && Huge + x > 1.0) return x;
            id = -1;
        }
        else
        {
            x = Math.Abs(x);
            if (ix < 0x3ff3_0000)
            {
                if (ix < 0x3fe6_0000)
                {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                }
                else
                {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            }
            else if (ix < 0x4003_8000)
            {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            }
            else
            {
                id = 3;
                x = -1.0 / x;
            }
        }

        var z = x * x;
        var w = z * z;
        var s1 = z * (AtanCoefficients[0] + w * (AtanCoefficients[2] +
            w * (AtanCoefficients[4] + w * (AtanCoefficients[6] +
            w * (AtanCoefficients[8] + w * AtanCoefficients[10])))));
        var s2 = w * (AtanCoefficients[1] + w * (AtanCoefficients[3] +
            w * (AtanCoefficients[5] + w * (AtanCoefficients[7] +
            w * AtanCoefficients[9]))));
        if (id < 0) return x - x * (s1 + s2);
        z = AtanHigh[id] - ((x * (s1 + s2) - AtanLow[id]) - x);
        return hx < 0 ? -z : z;
    }

    internal static double Atan2(double y, double x)
    {
        const double tiny = 1.0e-300;
        const double piOverFour = 7.8539816339744827900e-01;
        const double piOverTwo = 1.5707963267948965580e+00;
        const double piLow = 1.2246467991473531772e-16;

        var hx = High(x);
        var ix = hx & ExponentSignificandBits;
        var lx = Low(x);
        var hy = High(y);
        var iy = hy & ExponentSignificandBits;
        var ly = Low(y);
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;
        if (((hx - 0x3ff0_0000) | lx) == 0) return Atan(y);
        var quadrant = ((hy >> 31) & 1) | ((hx >> 30) & 2);
        if ((iy | ly) == 0)
        {
            return quadrant switch
            {
                0 or 1 => y,
                2 => Math.PI + tiny,
                _ => -Math.PI - tiny
            };
        }
        if ((ix | lx) == 0) return hy < 0 ? -piOverTwo - tiny : piOverTwo + tiny;
        if (ix == ExponentBits)
        {
            if (iy == ExponentBits)
            {
                return quadrant switch
                {
                    0 => piOverFour + tiny,
                    1 => -piOverFour - tiny,
                    2 => 3.0 * piOverFour + tiny,
                    _ => -3.0 * piOverFour - tiny
                };
            }
            return quadrant switch
            {
                0 => 0.0,
                1 => -0.0,
                2 => Math.PI + tiny,
                _ => -Math.PI - tiny
            };
        }
        if (iy == ExponentBits) return hy < 0 ? -piOverTwo - tiny : piOverTwo + tiny;

        var exponentDifference = (iy - ix) >> 20;
        var angle = exponentDifference > 60
            ? piOverTwo + 0.5 * piLow
            : hx < 0 && exponentDifference < -60
                ? 0.0
                : Atan(Math.Abs(y / x));
        return quadrant switch
        {
            0 => angle,
            1 => -angle,
            2 => Math.PI - (angle - piLow),
            _ => (angle - piLow) - Math.PI
        };
    }

    internal static double Log10(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double inverseLn10 = 4.34294481903251816668e-01;
        const double log10TwoHigh = 3.01029995663611771306e-01;
        const double log10TwoLow = 3.69423907715893078616e-13;

        var hx = High(x);
        var lx = Low(x);
        var k = 0;
        if (hx < 0x0010_0000)
        {
            if (((hx & ExponentSignificandBits) | lx) == 0)
                return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = High(x);
        }
        if (hx >= ExponentBits) return x + x;
        k += (hx >> 20) - 1023;
        var i = (int)((uint)k >> 31);
        hx = (hx & 0x000f_ffff) | ((0x3ff - i) << 20);
        var y = (double)(k + i);
        x = WithHigh(x, hx);
        var z = y * log10TwoLow + inverseLn10 * Math.Log(x);
        return z + y * log10TwoHigh;
    }

    internal static double Pow(double x, double y)
    {
        if (double.IsFinite(x) && double.IsFinite(y) &&
            x != 0.0 && y < 0.0 && Math.Truncate(y) == y)
            return 1.0 / Math.Pow(x, -y);
        return Math.Pow(x, y);
    }
}


internal static partial class JavaCompat
{
    // Port of java.lang.FdLibm.Pow from OpenJDK 21. StrictMath.pow is defined
    // in terms of this fdlibm implementation, and System.Math.Pow can differ
    // by one ulp for ordinary translated Java expressions.
    internal static double StrictPow(double x, double y)
    {
        double z, r, s, t, u, v, w;
        int i, j, k, n;

        if (y == 0.0) return 1.0;
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;

        var yAbs = Math.Abs(y);
        var xAbs = Math.Abs(x);
        if (y == 2.0) return x * x;
        if (y == 0.5)
        {
            if (x >= -double.MaxValue) return Math.Sqrt(x + 0.0);
        }
        else if (yAbs == 1.0)
        {
            return y == 1.0 ? x : 1.0 / x;
        }
        else if (double.IsPositiveInfinity(yAbs))
        {
            if (xAbs == 1.0) return y - y;
            if (xAbs > 1.0) return y >= 0 ? y : 0.0;
            return y < 0 ? -y : 0.0;
        }

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var yIsInt = 0;
        if (hx < 0)
        {
            if (yAbs >= 9007199254740992.0)
            {
                yIsInt = 2;
            }
            else if (yAbs >= 1.0)
            {
                var yAsLong = (long)yAbs;
                if ((double)yAsLong == yAbs) yIsInt = 2 - (int)(yAsLong & 1L);
            }
        }

        if (xAbs == 0.0 || double.IsPositiveInfinity(xAbs) || xAbs == 1.0)
        {
            z = xAbs;
            if (y < 0.0) z = 1.0 / z;
            if (hx < 0)
            {
                if (((ix - 0x3ff00000) | yIsInt) == 0) z = (z - z) / (z - z);
                else if (yIsInt == 1) z = -z;
            }
            return z;
        }

        n = (hx >> 31) + 1;
        if ((n | yIsInt) == 0) return (x - x) / (x - x);
        s = (n | (yIsInt - 1)) == 0 ? -1.0 : 1.0;

        double pH, pL, t1, t2;
        if (yAbs > 2147483903.9999998)
        {
            const double invLn2 = 1.44269504088896338700;
            const double invLn2H = 1.44269502162933349609;
            const double invLn2L = 1.92596299112661746887e-08;
            if (xAbs < 0.9999995231628418) return y < 0.0 ? s * double.PositiveInfinity : s * 0.0;
            if (xAbs > 1.0000009536743162) return y > 0.0 ? s * double.PositiveInfinity : s * 0.0;
            t = xAbs - 1.0;
            w = t * t * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
            u = invLn2H * t;
            v = t * invLn2L - w * invLn2;
            t1 = ClearLowWord(u + v);
            t2 = v - (t1 - u);
        }
        else
        {
            const double cp = 9.61796693925975554329e-01;
            const double cpH = 9.61796700954437255859e-01;
            const double cpL = -7.02846165095275826516e-09;
            ReadOnlySpan<double> bp = [1.0, 1.5];
            ReadOnlySpan<double> dpH = [0.0, 5.84962487220764160156e-01];
            ReadOnlySpan<double> dpL = [0.0, 1.35003920212974897128e-08];
            const double l1 = 5.99999999999994648725e-01;
            const double l2 = 4.28571428578550184252e-01;
            const double l3 = 3.33333329818377432918e-01;
            const double l4 = 2.72728123808534006489e-01;
            const double l5 = 2.30660745775561754067e-01;
            const double l6 = 2.06975017800338417784e-01;

            n = 0;
            if (ix < 0x00100000)
            {
                xAbs *= 9007199254740992.0;
                n -= 53;
                ix = HighWord(xAbs);
            }
            n += (ix >> 20) - 0x3ff;
            j = ix & 0x000fffff;
            ix = j | 0x3ff00000;
            if (j <= 0x3988E) k = 0;
            else if (j < 0xBB67A) k = 1;
            else
            {
                k = 0;
                n++;
                ix -= 0x00100000;
            }
            xAbs = WithHighWord(xAbs, ix);
            u = xAbs - bp[k];
            v = 1.0 / (xAbs + bp[k]);
            var ss = u * v;
            var sH = ClearLowWord(ss);
            var tH = WithHighWord(0.0, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
            var tL = xAbs - (tH - bp[k]);
            var sL = v * ((u - sH * tH) - sH * tL);
            var s2 = ss * ss;
            r = s2 * s2 * (l1 + s2 * (l2 + s2 * (l3 + s2 * (l4 + s2 * (l5 + s2 * l6)))));
            r += sL * (sH + ss);
            s2 = sH * sH;
            tH = ClearLowWord(3.0 + s2 + r);
            tL = r - ((tH - 3.0) - s2);
            u = sH * tH;
            v = sL * tH + tL * ss;
            pH = ClearLowWord(u + v);
            pL = v - (pH - u);
            var zH = cpH * pH;
            var zL = cpL * pH + pL * cp + dpL[k];
            t = n;
            t1 = ClearLowWord(((zH + zL) + dpH[k]) + t);
            t2 = zL - (((t1 - t) - dpH[k]) - zH);
        }

        var y1 = ClearLowWord(y);
        pL = (y - y1) * t1 + y * t2;
        pH = y1 * t1;
        z = pL + pH;
        j = HighWord(z);
        i = LowWord(z);
        if (j >= 0x40900000)
        {
            if (((j - 0x40900000) | i) != 0) return s * double.PositiveInfinity;
            const double ovt = 8.0085662595372944372e-17;
            if (pL + ovt > z - pH) return s * double.PositiveInfinity;
        }
        else if ((j & 0x7fffffff) >= 0x4090cc00)
        {
            if (((j - unchecked((int)0xc090cc00)) | i) != 0) return s * 0.0;
            if (pL <= z - pH) return s * 0.0;
        }

        const double p1 = 1.66666666666666019037e-01;
        const double p2 = -2.77777777770155933842e-03;
        const double p3 = 6.61375632143793436117e-05;
        const double p4 = -1.65339022054652515390e-06;
        const double p5 = 4.13813679705723846039e-08;
        const double lg2 = 6.93147180559945286227e-01;
        const double lg2H = 6.93147182464599609375e-01;
        const double lg2L = -1.90465429995776804525e-09;
        i = j & 0x7fffffff;
        k = (i >> 20) - 0x3ff;
        n = 0;
        if (i > 0x3fe00000)
        {
            n = j + (0x00100000 >> (k + 1));
            k = ((n & 0x7fffffff) >> 20) - 0x3ff;
            t = WithHighWord(0.0, n & ~(0x000fffff >> k));
            n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
            if (j < 0) n = -n;
            pH -= t;
        }
        t = ClearLowWord(pL + pH);
        u = t * lg2H;
        v = (pL - (t - pH)) * lg2 + t * lg2L;
        z = u + v;
        w = v - (z - u);
        t = z * z;
        t1 = z - t * (p1 + t * (p2 + t * (p3 + t * (p4 + t * p5))));
        r = z * t1 / (t1 - 2.0) - (w + z * w);
        z = 1.0 - (r - z);
        j = HighWord(z) + (n << 20);
        z = (j >> 20) <= 0 ? Math.ScaleB(z, n) : WithHighWord(z, j);
        return s * z;
    }

    // Ports of the corresponding OpenJDK fdlibm routines. StrictMath is
    // specified in terms of these algorithms; platform libm functions can
    // differ by one ulp, which is observable in rendered values.
    internal static double StrictLog(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ln2Hi = 6.93147180369123816490e-01;
        const double ln2Lo = 1.90821492927058770002e-10;
        const double lg1 = 6.666666666666735130e-01;
        const double lg2 = 3.999999999940941908e-01;
        const double lg3 = 2.857142874366239149e-01;
        const double lg4 = 2.222219843214978396e-01;
        const double lg5 = 1.818357216161805012e-01;
        const double lg6 = 1.531383769920937332e-01;
        const double lg7 = 1.479819860511658591e-01;

        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        hx &= 0x000fffff;
        var i = (hx + 0x95f64) & 0x100000;
        x = WithHighWord(x, hx | (i ^ 0x3ff00000));
        k += i >> 20;
        var f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3)
        {
            if (f == 0.0)
                return k == 0 ? 0.0 : k * ln2Hi + k * ln2Lo;
            var smallR = f * f * (0.5 - 0.33333333333333333 * f);
            return k == 0
                ? f - smallR
                : k * ln2Hi - ((smallR - k * ln2Lo) - f);
        }
        var s = f / (2.0 + f);
        var dk = (double)k;
        var z = s * s;
        i = hx - 0x6147a;
        var w = z * z;
        var j = 0x6b851 - hx;
        var t1 = w * (lg2 + w * (lg4 + w * lg6));
        var t2 = z * (lg1 + w * (lg3 + w * (lg5 + w * lg7)));
        i |= j;
        var r = t2 + t1;
        if (i > 0)
        {
            var hfsq = 0.5 * f * f;
            return k == 0
                ? f - (hfsq - s * (hfsq + r))
                : dk * ln2Hi - ((hfsq - (s * (hfsq + r) + dk * ln2Lo)) - f);
        }
        return k == 0
            ? f - s * (f - r)
            : dk * ln2Hi - ((s * (f - r) - dk * ln2Lo) - f);
    }

    internal static double StrictLog10(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ivln10 = 4.34294481903251816668e-01;
        const double log10_2hi = 3.01029995663611771306e-01;
        const double log10_2lo = 3.69423907715893078616e-13;
        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        var i = (int)((uint)k >> 31);
        hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
        var y = (double)(k + i);
        x = WithHighWord(x, hx);
        var z = y * log10_2lo + ivln10 * StrictLog(x);
        return z + y * log10_2hi;
    }

    private static int StrictRemPio2(double x, Span<double> y)
    {
        const double invpio2 = 6.36619772367581382433e-01;
        const double pio2_1 = 1.57079632673412561417e+00;
        const double pio2_1t = 6.07710050650619224932e-11;
        const double pio2_2 = 6.07710050630396597660e-11;
        const double pio2_2t = 2.02226624879595063154e-21;
        const double pio2_3 = 2.02226624871116645580e-21;
        const double pio2_3t = 8.47842766036889956997e-32;
        ReadOnlySpan<int> npio2Hw =
        [
            0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
            0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
            0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
            0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
            0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
            0x404858EB, 0x404921FB
        ];

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb)
        {
            y[0] = x;
            y[1] = 0.0;
            return 0;
        }
        if (ix < 0x4002d97c)
        {
            if (hx > 0)
            {
                var z = x - pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z - pio2_1t;
                    y[1] = (z - y[0]) - pio2_1t;
                }
                else
                {
                    z -= pio2_2;
                    y[0] = z - pio2_2t;
                    y[1] = (z - y[0]) - pio2_2t;
                }
                return 1;
            }
            else
            {
                var z = x + pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z + pio2_1t;
                    y[1] = (z - y[0]) + pio2_1t;
                }
                else
                {
                    z += pio2_2;
                    y[0] = z + pio2_2t;
                    y[1] = (z - y[0]) + pio2_2t;
                }
                return -1;
            }
        }
        var t = Math.Abs(x);
        var n = (int)(t * invpio2 + 0.5);
        var fn = (double)n;
        var r = t - fn * pio2_1;
        var w = fn * pio2_1t;
        if (n < 32 && ix != npio2Hw[n - 1])
        {
            y[0] = r - w;
        }
        else
        {
            var j = ix >> 20;
            y[0] = r - w;
            var i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
            if (i > 16)
            {
                t = r;
                w = fn * pio2_2;
                r = t - w;
                w = fn * pio2_2t - ((t - r) - w);
                y[0] = r - w;
                i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
                if (i > 49)
                {
                    t = r;
                    w = fn * pio2_3;
                    r = t - w;
                    w = fn * pio2_3t - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx >= 0) return n;
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }

    private static double StrictKernelSin(double x, double y, int iy)
    {
        const double s1 = -1.66666666666666324348e-01;
        const double s2 = 8.33333333332248946124e-03;
        const double s3 = -1.98412698298579493134e-04;
        const double s4 = 2.75573137070700676789e-06;
        const double s5 = -2.50507602534068634195e-08;
        const double s6 = 1.58969099521155010221e-10;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return x;
        var z = x * x;
        var v = z * x;
        var r = s2 + z * (s3 + z * (s4 + z * (s5 + z * s6)));
        return iy == 0
            ? x + v * (s1 + z * r)
            : x - ((z * (0.5 * y - v * r) - y) - v * s1);
    }

    private static double StrictKernelCos(double x, double y)
    {
        const double c1 = 4.16666666666666019037e-02;
        const double c2 = -1.38888888888741095749e-03;
        const double c3 = 2.48015872894767294178e-05;
        const double c4 = -2.75573143513906633035e-07;
        const double c5 = 2.08757232129817482790e-09;
        const double c6 = -1.13596475577881948265e-11;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return 1.0;
        var z = x * x;
        var r = z * (c1 + z * (c2 + z * (c3 + z * (c4 + z * (c5 + z * c6)))));
        if (ix < 0x3fd33333) return 1.0 - (0.5 * z - (z * r - x * y));
        var qx = ix > 0x3fe90000
            ? 0.28125
            : BitConverter.Int64BitsToDouble((long)(ix - 0x00200000) << 32);
        var hz = 0.5 * z - qx;
        var a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    internal static double StrictSin(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelSin(x, 0.0, 0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Sin(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelSin(y[0], y[1], 1),
            1 => StrictKernelCos(y[0], y[1]),
            2 => -StrictKernelSin(y[0], y[1], 1),
            _ => -StrictKernelCos(y[0], y[1])
        };
    }

    internal static double StrictCos(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelCos(x, 0.0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Cos(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelCos(y[0], y[1]),
            1 => -StrictKernelSin(y[0], y[1], 1),
            2 => -StrictKernelCos(y[0], y[1]),
            _ => StrictKernelSin(y[0], y[1], 1)
        };
    }

    internal static double StrictAsin(double x)
    {
        const double pio2Hi = 1.57079632679489655800e+00;
        const double pio2Lo = 6.12323399573676603587e-17;
        const double pio4Hi = 7.85398163397448278999e-01;
        const double pS0 = 1.66666666666666657415e-01;
        const double pS1 = -3.25565818622400915405e-01;
        const double pS2 = 2.01212532134862925881e-01;
        const double pS3 = -4.00555345006794114027e-02;
        const double pS4 = 7.91534994289814532176e-04;
        const double pS5 = 3.47933107596021167570e-05;
        const double qS1 = -2.40339491173441421878e+00;
        const double qS2 = 2.02094576023350569471e+00;
        const double qS3 = -6.88283971605453293030e-01;
        const double qS4 = 7.70381505559019352791e-02;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000)
        {
            if (((ix - 0x3ff00000) | LowWord(x)) == 0) return x * pio2Hi + x * pio2Lo;
            return double.NaN;
        }
        double t = 0.0;
        if (ix < 0x3fe00000)
        {
            if (ix < 0x3e400000) return x;
            t = x * x;
            var p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
            var q = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
            return x + x * (p / q);
        }
        var w = 1.0 - Math.Abs(x);
        t = w * 0.5;
        var pn = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
        var qn = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
        var s = Math.Sqrt(t);
        if (ix >= 0x3fef3333)
        {
            w = pn / qn;
            t = pio2Hi - (2.0 * (s + s * w) - pio2Lo);
        }
        else
        {
            w = ClearLowWord(s);
            var c = (t - w * w) / (s + w);
            var r = pn / qn;
            var p = 2.0 * s * r - (pio2Lo - 2.0 * c);
            var q = pio4Hi - 2.0 * w;
            t = pio4Hi - (p - q);
        }
        return hx > 0 ? t : -t;
    }

    internal static double StrictAtan(double x)
    {
        ReadOnlySpan<double> atanHi =
        [4.63647609000806093515e-01, 7.85398163397448278999e-01,
         9.82793723247329054082e-01, 1.57079632679489655800e+00];
        ReadOnlySpan<double> atanLo =
        [2.26987774529616870924e-17, 3.06161699786838301793e-17,
         1.39033110312319984516e-17, 6.12323399573676603587e-17];
        ReadOnlySpan<double> aT =
        [
            3.33333333333329318027e-01, -1.99999999998764832476e-01,
            1.42857142725034663711e-01, -1.11111104054623557880e-01,
            9.09088713343650656196e-02, -7.69187620504482999495e-02,
            6.66107313738753120669e-02, -5.83357013379057348645e-02,
            4.97687799461593236017e-02, -3.65315727442169155270e-02,
            1.62858201153657823623e-02
        ];
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        int id;
        if (ix >= 0x44100000)
        {
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && LowWord(x) != 0)) return x + x;
            return hx > 0 ? atanHi[3] + atanLo[3] : -atanHi[3] - atanLo[3];
        }
        if (ix < 0x3fdc0000)
        {
            if (ix < 0x3e200000) return x;
            id = -1;
        }
        else
        {
            x = Math.Abs(x);
            if (ix < 0x3ff30000)
            {
                if (ix < 0x3fe60000)
                {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                }
                else
                {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            }
            else if (ix < 0x40038000)
            {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            }
            else
            {
                id = 3;
                x = -1.0 / x;
            }
        }
        var z = x * x;
        var w = z * z;
        var s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
        var s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
        if (id < 0) return x - x * (s1 + s2);
        z = atanHi[id] - ((x * (s1 + s2) - atanLo[id]) - x);
        return hx < 0 ? -z : z;
    }

    internal static double StrictAtan2(double y, double x)
    {
        const double tiny = 1.0e-300;
        const double piOver4 = 7.8539816339744827900e-01;
        const double piOver2 = 1.5707963267948965580e+00;
        const double piLo = 1.2246467991473531772e-16;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var lx = LowWord(x);
        var hy = HighWord(y);
        var iy = hy & 0x7fffffff;
        var ly = LowWord(y);
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;
        if (((hx - 0x3ff00000) | lx) == 0) return StrictAtan(y);
        var m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
        if ((iy | ly) == 0)
            return m switch { 0 or 1 => y, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        if ((ix | lx) == 0) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        if (ix == 0x7ff00000)
        {
            if (iy == 0x7ff00000)
                return m switch
                {
                    0 => piOver4 + tiny,
                    1 => -piOver4 - tiny,
                    2 => 3.0 * piOver4 + tiny,
                    _ => -3.0 * piOver4 - tiny
                };
            return m switch { 0 => 0.0, 1 => -0.0, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        }
        if (iy == 0x7ff00000) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        var k = (iy - ix) >> 20;
        double z;
        if (k > 60) z = piOver2 + 0.5 * piLo;
        else if (hx < 0 && k < -60) z = 0.0;
        else z = StrictAtan(Math.Abs(y / x));
        return m switch
        {
            0 => z,
            1 => -z,
            2 => Math.PI - (z - piLo),
            _ => (z - piLo) - Math.PI
        };
    }

    private static int HighWord(double value) =>
        unchecked((int)(BitConverter.DoubleToInt64Bits(value) >> 32));

    private static int LowWord(double value) =>
        unchecked((int)BitConverter.DoubleToInt64Bits(value));

    private static double ClearLowWord(double value) =>
        BitConverter.Int64BitsToDouble(BitConverter.DoubleToInt64Bits(value) & unchecked((long)0xffffffff00000000UL));

    private static double WithHighWord(double value, int highWord) =>
        BitConverter.Int64BitsToDouble(
            (BitConverter.DoubleToInt64Bits(value) & 0x00000000ffffffffL) |
            ((long)highWord << 32));
    internal static int ToUnsignedInt(byte value) => value;
    internal static long ToUnsignedLong(sbyte value) => unchecked((byte)value);
    internal static bool IsUpperCase(int value) => Rune.IsUpper(new Rune(value));
    internal static bool IsUpperCase(char value) => char.IsUpper(value);
    internal static bool IsTitleCase(int value) => CharacterType(value) == 3;
    internal static int ToTitleCase(int value) => value is >= char.MinValue and <= char.MaxValue
        ? char.ToUpperInvariant((char)value)
        : value;
    internal static long MathRound(double value) => double.IsNaN(value) ? 0
        : value >= long.MaxValue ? long.MaxValue
        : value <= long.MinValue ? long.MinValue
        : (long)Math.Floor(value + 0.5d);
    internal static int MathRoundFloat(float value) => float.IsNaN(value) ? 0
        : value >= int.MaxValue ? int.MaxValue
        : value <= int.MinValue ? int.MinValue
        : (int)Math.Floor(value + 0.5f);
    internal static int FloorDiv(int left, int right)
    {
        if (left == int.MinValue && right == -1) return int.MinValue;
        var quotient = left / right;
        var remainder = left % right;
        return remainder != 0 && (left ^ right) < 0 ? quotient - 1 : quotient;
    }
    internal static double ToDegrees(double value) => value * (180d / Math.PI);
    internal static double ToRadians(double value) => value * (Math.PI / 180d);
    internal static long AddExact(long left, long right) => checked(left + right);
    internal static long MultiplyExact(long left, long right) => checked(left * right);
    internal static int MultiplyExactInt(int left, int right) => checked(left * right);
    internal static double SignumDouble(double value) => double.IsNaN(value) || value == 0.0
        ? value
        : value > 0.0 ? 1.0 : -1.0;
    internal static float SignumFloat(float value) => float.IsNaN(value) || value == 0.0f
        ? value
        : value > 0.0f ? 1.0f : -1.0f;
    internal static long SubtractExact(long left, long right) => checked(left - right);
    internal static long NegateExact(long value) => checked(-value);
    internal static int NegateExact(int value) => checked(-value);
    internal static long IncrementExact(long value) => checked(value + 1);
    internal static int IncrementExact(int value) => checked(value + 1);
    internal static long DecrementExact(long value) => checked(value - 1);
    internal static int DecrementExact(int value) => checked(value - 1);
    internal static int ToIntExact(long value) => checked((int)value);
    internal static int AddExactInt(int left, int right) => checked(left + right);
    internal static int GetExponent(double value) => Math.ILogB(value);
    internal static BigInteger NewBigInteger(int signum, sbyte[] magnitude) =>
        new BigInteger(magnitude.Select(value => unchecked((byte)value)).ToArray(), true, true) * Math.Sign(signum);
    internal static BigInteger NewBigInteger(int signum, byte[] magnitude) =>
        new BigInteger(magnitude, true, true) * Math.Sign(signum);
    internal static sbyte[] BigIntegerToByteArray(BigInteger value) =>
        ToSignedBytes(value.ToByteArray(isUnsigned: false, isBigEndian: true));
    internal static BigInteger BigIntegerMod(BigInteger value, BigInteger modulus)
    {
        if (modulus.Sign <= 0)
            throw new ArithmeticException("BigInteger modulus must be positive.");
        var remainder = value % modulus;
        return remainder.Sign < 0 ? remainder + modulus : remainder;
    }
    internal static BigInteger BigIntegerShiftRight(BigInteger value, int distance) =>
        distance >= 0 ? value >> distance : value << -distance;
    internal static int BigIntegerIntValue(BigInteger value) =>
        unchecked((int)(uint)(value & uint.MaxValue));

    internal static decimal BigDecimalParse(string value) =>
        decimal.Parse(
            value,
            NumberStyles.Number | NumberStyles.AllowExponent,
            CultureInfo.InvariantCulture);

    internal static decimal BigDecimalValueOf(double value) =>
        BigDecimalParse(value.ToString("R", CultureInfo.InvariantCulture));

    internal static decimal BigDecimalMultiply(decimal left, decimal right) =>
        checked(left * right);

    internal static decimal BigDecimalDivide(
        decimal left,
        decimal right,
        int scale,
        JavaRoundingMode roundingMode) =>
        BigDecimalRound(left / right, scale, roundingMode);

    internal static decimal BigDecimalSetScale(
        decimal value,
        int scale,
        JavaRoundingMode roundingMode) =>
        BigDecimalRound(value, scale, roundingMode);

    private static decimal BigDecimalRound(
        decimal value,
        int scale,
        JavaRoundingMode roundingMode)
    {
        if (scale is < 0 or > 28) throw new ArithmeticException("Scale is outside System.Decimal range.");
        return roundingMode switch
        {
            JavaRoundingMode.Down => decimal.Round(
                value,
                scale,
                MidpointRounding.ToZero),
            JavaRoundingMode.Ceiling => decimal.Round(
                value,
                scale,
                MidpointRounding.ToPositiveInfinity),
            JavaRoundingMode.Floor => decimal.Round(
                value,
                scale,
                MidpointRounding.ToNegativeInfinity),
            JavaRoundingMode.HalfUp => decimal.Round(
                value,
                scale,
                MidpointRounding.AwayFromZero),
            JavaRoundingMode.HalfEven => decimal.Round(
                value,
                scale,
                MidpointRounding.ToEven),
            JavaRoundingMode.Unnecessary when value == decimal.Round(
                value,
                scale,
                MidpointRounding.ToZero) => value,
            _ => throw new ArgumentOutOfRangeException(nameof(roundingMode))
        };
    }

    internal static int BigDecimalIntValue(decimal value) =>
        decimal.ToInt32(decimal.Truncate(value));

    internal static decimal BigDecimalStripTrailingZeros(decimal value)
    {
        if (value == 0) return decimal.Zero;
        return decimal.Parse(
            value.ToString("G29", CultureInfo.InvariantCulture),
            NumberStyles.Number,
            CultureInfo.InvariantCulture);
    }

    internal static string BigDecimalToPlainString(decimal value) =>
        value.ToString(CultureInfo.InvariantCulture);

    internal static string BigDecimalToString(decimal value) =>
        value.ToString(CultureInfo.InvariantCulture);

    internal static decimal DecimalDivide(decimal left, decimal right, int scale, object rounding)
    {
        var rounded = decimal.Round(
            left / right,
            scale,
            string.Equals(rounding.ToString(), "DOWN", StringComparison.Ordinal)
                ? MidpointRounding.ToZero
                : MidpointRounding.ToEven);
        // BigDecimal.toString() retains the requested division scale. Reparse a fixed-point
        // representation so System.Decimal carries the same scale in its value bits.
        return decimal.Parse(
            rounded.ToString("F" + scale, System.Globalization.CultureInfo.InvariantCulture),
            System.Globalization.NumberStyles.Number,
            System.Globalization.CultureInfo.InvariantCulture);
    }
}
