// Focused destination compatibility for FontBox geometry over SkiaSharp.
#nullable enable

using SkiaSharp;

namespace Vibeformer.Runtime;

#pragma warning disable CS0618 // SKPath mutation is the m150 bridge for Java GeneralPath.

internal sealed class JavaPoint2D
{
    internal JavaPoint2D(float x, float y)
    {
        X = x;
        Y = y;
    }

    internal float X { get; private set; }
    internal float Y { get; private set; }

    internal void SetLocation(JavaPoint2D point)
    {
        X = point.X;
        Y = point.Y;
    }

    internal void SetLocation(double x, double y)
    {
        X = (float)x;
        Y = (float)y;
    }
}

internal static class PdfCubeFontCompat
{
    internal static SKMatrix Translation(double x, double y) =>
        SKMatrix.CreateTranslation((float)x, (float)y);

    internal static JavaPoint2D? CurrentPoint(SKPath path) =>
        path.IsEmpty ? null : new JavaPoint2D(path.LastPoint.X, path.LastPoint.Y);

    internal static SKPath PathIterator(SKPath path, object? transform)
    {
        var result = new SKPath(path);
        if (transform is SKMatrix matrix)
        {
            result.Transform(matrix);
        }
        return result;
    }

    internal static void Close(SKPath path) => path.Close();

    internal static void AddPath(SKPath path, SKPath addition) =>
        path.AddPath(addition);

    internal static void MoveTo(SKPath path, double x, double y) =>
        path.MoveTo((float)x, (float)y);

    internal static void LineTo(SKPath path, double x, double y) =>
        path.LineTo((float)x, (float)y);

    internal static void QuadTo(SKPath path, double x1, double y1, double x2, double y2) =>
        path.QuadTo((float)x1, (float)y1, (float)x2, (float)y2);

    internal static void CurveTo(
        SKPath path,
        double x1,
        double y1,
        double x2,
        double y2,
        double x3,
        double y3) =>
        path.CubicTo((float)x1, (float)y1, (float)x2, (float)y2, (float)x3, (float)y3);
}

#pragma warning restore CS0618
