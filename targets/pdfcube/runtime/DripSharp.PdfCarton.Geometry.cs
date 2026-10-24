// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// java.awt.geom paths as managed data. This file is compiled into the FontBox
// assembly only; the PDFBox assembly, which sees its internals, uses the same
// types, so a glyph outline made by FontBox is drawn by PDFBox as it is.
#nullable enable

using System;
using SkiaSharp;

namespace DripSharp.Runtime;

#pragma warning disable CS0618 // SKPath mutation is the m150 bridge for Java GeneralPath.

// A java.awt.geom.GeneralPath, Path2D.Float or Path2D.Double, a shape made by
// AffineTransform.createTransformedShape, or (JavaArea) an Area. As Path2D
// does, it keeps one type per segment and the segments' coordinates, rounded
// to float unless it has double precision. It holds no native memory and
// needs no disposal: the compat graphics copy its segments into a Skia path of
// their own whenever Skia draws, clips or intersects it.
//
// createTransformedShape (a glyph outline, once per glyph shown) returns a
// path that reads its source's arrays through the transform, mapped as Skia
// maps points, until it is changed itself. A source whose arrays are read so
// still appends in place (the shape reads only the segments it was made
// from), but a change that would overwrite them (reset, transform, a moveTo
// replacing a moveTo) gives the source new arrays first.
public class JavaPath2D
{
    public const int WIND_EVEN_ODD = 0;
    public const int WIND_NON_ZERO = 1;
    internal const byte MoveSegment = 0;
    internal const byte LineSegment = 1;
    internal const byte QuadSegment = 2;
    internal const byte CubicSegment = 3;
    internal const byte CloseSegment = 4;
    private const int DefaultCapacity = 20;

    [ThreadStatic] private static SKPoint[]? scratchPoints;

    private byte[] types;
    private double[] coordinates;
    private int typeCount;
    private int coordinateCount;
    private int windingRule;
    // The arrays are another path's (a transformed shape's source).
    private bool borrowed;
    // Transformed shapes read a prefix of the arrays.
    private bool lent;
    // While set, the coordinates are the source's, mapped through mapping.
    private bool mapped;
    private SKMatrix mapping;
    // The coordinate index of the current subpath's moveTo (-1: none), to
    // which closePath returns the current point.
    private int subpathStart = -1;

    public JavaPath2D()
        : this(WIND_NON_ZERO, DefaultCapacity, doublePrecision: false)
    {
    }

    public JavaPath2D(int windingRule, int initialCapacity, bool doublePrecision)
    {
        if (initialCapacity < 0) throw new ArgumentException("Initial capacity cannot be negative.");
        CheckWindingRule(windingRule);
        this.windingRule = windingRule;
        types = new byte[initialCapacity];
        coordinates = new double[initialCapacity * 2];
        IsDoublePrecision = doublePrecision;
    }

    // A copy of source's segments (new GeneralPath(shape), Path2D.Double(shape),
    // clone, new Area(shape)). With share, a copy that would hold the same
    // values reads source's arrays until either path would overwrite them
    // (copy-on-write), so new Area(path) of a large path copies nothing.
    internal JavaPath2D(JavaPath2D source, bool doublePrecision, bool share = false)
    {
        ArgumentNullException.ThrowIfNull(source);
        IsDoublePrecision = doublePrecision;
        windingRule = source.windingRule;
        typeCount = source.typeCount;
        coordinateCount = source.coordinateCount;
        subpathStart = source.subpathStart;
        if (share && !source.mapped && (doublePrecision || !source.IsDoublePrecision))
        {
            types = source.types;
            coordinates = source.coordinates;
            borrowed = true;
            source.lent = true;
            return;
        }
        types = new byte[Math.Max(typeCount, DefaultCapacity)];
        coordinates = new double[Math.Max(coordinateCount, DefaultCapacity * 2)];
        Array.Copy(source.types, types, typeCount);
        if (source.mapped)
        {
            var points = source.MappedPoints();
            for (var point = 0; point < coordinateCount / 2; point++)
            {
                coordinates[point * 2] = points[point].X;
                coordinates[point * 2 + 1] = points[point].Y;
            }
        }
        else if (doublePrecision || !source.IsDoublePrecision)
        {
            Array.Copy(source.coordinates, coordinates, coordinateCount);
        }
        else
        {
            for (var index = 0; index < coordinateCount; index++)
                coordinates[index] = (float)source.coordinates[index];
        }
    }

    // createTransformedShape: source's segments, read through transform.
    internal JavaPath2D(JavaPath2D source, SKMatrix transform)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (source.mapped) source = new JavaPath2D(source, doublePrecision: true);
        IsDoublePrecision = true;
        windingRule = source.windingRule;
        types = source.types;
        coordinates = source.coordinates;
        typeCount = source.typeCount;
        coordinateCount = source.coordinateCount;
        subpathStart = source.subpathStart;
        borrowed = true;
        mapped = true;
        mapping = transform;
        source.lent = true;
    }

    public bool IsDoublePrecision { get; }

    public int WindingRule => windingRule;

    // Area.isEmpty and SKPath.IsEmpty: no segments at all.
    public bool IsEmpty => typeCount == 0;

    internal int SegmentCount => typeCount;

    // getBounds2D: the bounds of every point, control points included, as
    // Skia bounds a path; empty when a coordinate is not finite.
    public SKRect Bounds
    {
        get
        {
            var count = coordinateCount / 2;
            if (count == 0) return SKRect.Empty;
            if (mapped) return PointBounds(MappedPoints(), count);
            float left = float.PositiveInfinity, top = float.PositiveInfinity;
            float right = float.NegativeInfinity, bottom = float.NegativeInfinity;
            var finite = true;
            for (var point = 0; point < count; point++)
            {
                var x = (float)coordinates[point * 2];
                var y = (float)coordinates[point * 2 + 1];
                finite &= !float.IsNaN(x) && !float.IsInfinity(x) && !float.IsNaN(y) && !float.IsInfinity(y);
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
            return finite ? new SKRect(left, top, right, bottom) : SKRect.Empty;
        }
    }

    public JavaPathIterator GetPathIterator(object? transform) => new(this, transform);

    // A new Skia path of these segments, which the caller owns.
    public SKPath ToSKPath()
    {
        var path = new SKPath();
        WriteTo(path);
        return path;
    }

    public void MoveTo(double x, double y)
    {
        if (typeCount > 0 && types[typeCount - 1] == MoveSegment)
        {
            // Path2D replaces a moveTo that nothing followed.
            PrepareOverwrite();
            coordinates[coordinateCount - 2] = Round(x);
            coordinates[coordinateCount - 1] = Round(y);
            return;
        }
        Append(MoveSegment, 2, allowEmpty: true);
        subpathStart = coordinateCount;
        coordinates[coordinateCount++] = Round(x);
        coordinates[coordinateCount++] = Round(y);
    }

    public void LineTo(double x, double y)
    {
        Append(LineSegment, 2, allowEmpty: false);
        coordinates[coordinateCount++] = Round(x);
        coordinates[coordinateCount++] = Round(y);
    }

    public void QuadTo(double x1, double y1, double x2, double y2)
    {
        Append(QuadSegment, 4, allowEmpty: false);
        coordinates[coordinateCount++] = Round(x1);
        coordinates[coordinateCount++] = Round(y1);
        coordinates[coordinateCount++] = Round(x2);
        coordinates[coordinateCount++] = Round(y2);
    }

    public void CurveTo(double x1, double y1, double x2, double y2, double x3, double y3)
    {
        Append(CubicSegment, 6, allowEmpty: false);
        coordinates[coordinateCount++] = Round(x1);
        coordinates[coordinateCount++] = Round(y1);
        coordinates[coordinateCount++] = Round(x2);
        coordinates[coordinateCount++] = Round(y2);
        coordinates[coordinateCount++] = Round(x3);
        coordinates[coordinateCount++] = Round(y3);
    }

    // A close right after a close adds nothing, as in Path2D.
    public void ClosePath()
    {
        if (typeCount > 0 && types[typeCount - 1] == CloseSegment) return;
        Append(CloseSegment, 0, allowEmpty: false);
    }

    public void Reset()
    {
        if (borrowed || lent)
        {
            types = new byte[lent ? types.Length : DefaultCapacity];
            coordinates = new double[lent ? coordinates.Length : DefaultCapacity * 2];
            borrowed = lent = false;
        }
        mapped = false;
        typeCount = coordinateCount = 0;
        subpathStart = -1;
    }

    public void SetWindingRule(int rule)
    {
        CheckWindingRule(rule);
        windingRule = rule;
    }

    // Path2D.transform. A float path is mapped as Skia maps points (as when it
    // was a Skia path); a double one in double precision, as Path2D.Double is.
    public void Transform(SKMatrix transform)
    {
        Detach();
        PrepareOverwrite();
        var count = coordinateCount / 2;
        if (count == 0) return;
        if (IsDoublePrecision)
        {
            for (var point = 0; point < count; point++)
            {
                var x = coordinates[point * 2];
                var y = coordinates[point * 2 + 1];
                coordinates[point * 2] = transform.ScaleX * x + transform.SkewX * y + transform.TransX;
                coordinates[point * 2 + 1] = transform.SkewY * x + transform.ScaleY * y + transform.TransY;
            }
            return;
        }
        var points = PointsAsSkia(out _);
        transform.MapPoints(points.AsSpan(0, count), points.AsSpan(0, count));
        for (var point = 0; point < count; point++)
        {
            coordinates[point * 2] = points[point].X;
            coordinates[point * 2 + 1] = points[point].Y;
        }
    }

    // Path2D.append(PathIterator, connect): a leading moveTo becomes a
    // lineTo when connect is set and the path has a current point, and is
    // dropped when it is already there.
    public void Append(JavaPathIterator iterator, bool connect)
    {
        ArgumentNullException.ThrowIfNull(iterator);
        var segment = new double[6];
        for (; !iterator.IsDone(); iterator.Next())
        {
            switch (iterator.CurrentSegment(segment))
            {
                case JavaPathIterator.SEG_MOVETO:
                    if (connect && TryGetCurrentPoint(out var x, out var y))
                    {
                        if (types[typeCount - 1] == CloseSegment || x != Round(segment[0]) || y != Round(segment[1]))
                            LineTo(segment[0], segment[1]);
                    }
                    else
                    {
                        MoveTo(segment[0], segment[1]);
                    }
                    break;
                case JavaPathIterator.SEG_LINETO:
                    LineTo(segment[0], segment[1]);
                    break;
                case JavaPathIterator.SEG_QUADTO:
                    QuadTo(segment[0], segment[1], segment[2], segment[3]);
                    break;
                case JavaPathIterator.SEG_CUBICTO:
                    CurveTo(segment[0], segment[1], segment[2], segment[3], segment[4], segment[5]);
                    break;
                default:
                    ClosePath();
                    break;
            }
            connect = false;
        }
    }

    // Path2D.getCurrentPoint: none for an empty path, else the last point,
    // or after a close the start of the closed subpath.
    internal bool TryGetCurrentPoint(out double x, out double y)
    {
        x = y = 0;
        if (typeCount == 0 || coordinateCount == 0) return false;
        var index = types[typeCount - 1] == CloseSegment && subpathStart >= 0
            ? subpathStart
            : coordinateCount - 2;
        if (mapped)
        {
            var point = Map(mapping, new SKPoint((float)coordinates[index], (float)coordinates[index + 1]));
            x = point.X;
            y = point.Y;
            return true;
        }
        x = coordinates[index];
        y = coordinates[index + 1];
        return true;
    }

    // Replaces the segments with those of a Skia path (an area operation's
    // result); a conic, which Java has not, is followed as quadratics.
    internal void ReadFrom(SKPath path)
    {
        Reset();
        windingRule = path.FillType is SKPathFillType.EvenOdd or SKPathFillType.InverseEvenOdd
            ? WIND_EVEN_ODD
            : WIND_NON_ZERO;
        Span<SKPoint> points = stackalloc SKPoint[4];
        using var iterator = path.CreateRawIterator();
        SKPathVerb verb;
        while ((verb = iterator.Next(points)) != SKPathVerb.Done)
        {
            switch (verb)
            {
                case SKPathVerb.Move:
                    MoveTo(points[0].X, points[0].Y);
                    break;
                case SKPathVerb.Line:
                    LineTo(points[1].X, points[1].Y);
                    break;
                case SKPathVerb.Quad:
                    QuadTo(points[1].X, points[1].Y, points[2].X, points[2].Y);
                    break;
                case SKPathVerb.Conic:
                    var quads = SKPath.ConvertConicToQuads(points[0], points[1], points[2], iterator.ConicWeight(), 2);
                    for (var index = 1; index + 1 < quads.Length; index += 2)
                        QuadTo(quads[index].X, quads[index].Y, quads[index + 1].X, quads[index + 1].Y);
                    break;
                case SKPathVerb.Cubic:
                    CurveTo(points[1].X, points[1].Y, points[2].X, points[2].Y, points[3].X, points[3].Y);
                    break;
                case SKPathVerb.Close:
                    ClosePath();
                    break;
            }
        }
    }

    // Refills path with these segments (rewound first, so its storage is
    // reused), as the Skia path calls a Skia-backed GeneralPath once had.
    internal void WriteTo(SKPath path)
    {
        path.Rewind();
        path.FillType = windingRule == WIND_EVEN_ODD ? SKPathFillType.EvenOdd : SKPathFillType.Winding;
        var points = PointsAsSkia(out var count);
        var point = 0;
        for (var segment = 0; segment < typeCount && point <= count; segment++)
        {
            switch (types[segment])
            {
                case MoveSegment:
                    path.MoveTo(points[point++]);
                    break;
                case LineSegment:
                    path.LineTo(points[point++]);
                    break;
                case QuadSegment:
                    path.QuadTo(points[point], points[point + 1]);
                    point += 2;
                    break;
                case CubicSegment:
                    path.CubicTo(points[point], points[point + 1], points[point + 2]);
                    point += 3;
                    break;
                default:
                    path.Close();
                    break;
            }
        }
    }

    // The segments in device space for a coverage mask: the types, and the
    // points mapped by deviceTransform after this shape's own transform,
    // each as Skia maps points (as a Skia path transformed twice).
    // The points are in a per-thread scratch array, valid until the next
    // path is read on the thread; their bounds are as Skia bounds a path.
    internal byte[] DeviceSegments(SKMatrix deviceTransform, out int segmentCount, out SKPoint[] points, out SKRect bounds)
    {
        points = PointsAsSkia(out var pointCount);
        if (pointCount > 0) deviceTransform.MapPoints(points.AsSpan(0, pointCount), points.AsSpan(0, pointCount));
        bounds = pointCount == 0 ? SKRect.Empty : PointBounds(points, pointCount);
        segmentCount = typeCount;
        return types;
    }

    internal static int PointCount(byte type) => type switch
    {
        MoveSegment or LineSegment => 1,
        QuadSegment => 2,
        CubicSegment => 3,
        _ => 0
    };

    internal byte TypeAt(int segment) => types[segment];

    // The coordinates of point (an index into the points), mapped when this
    // is a transformed shape.
    internal SKPoint PointAt(int point)
    {
        var value = new SKPoint((float)coordinates[point * 2], (float)coordinates[point * 2 + 1]);
        return mapped ? Map(mapping, value) : value;
    }

    // One point mapped as Skia maps a path's points (MapPoint may round
    // differently).
    internal static SKPoint Map(SKMatrix matrix, SKPoint point)
    {
        Span<SKPoint> points = stackalloc SKPoint[1];
        points[0] = point;
        matrix.MapPoints(points, points);
        return points[0];
    }

    internal double CoordinateAt(int index) => coordinates[index];

    internal bool IsMapped => mapped;

    internal SKMatrix Mapping => mapping;

    private static SKRect PointBounds(SKPoint[] points, int count)
    {
        float left = float.PositiveInfinity, top = float.PositiveInfinity;
        float right = float.NegativeInfinity, bottom = float.NegativeInfinity;
        var finite = true;
        for (var point = 0; point < count; point++)
        {
            var (x, y) = (points[point].X, points[point].Y);
            finite &= !float.IsNaN(x) && !float.IsInfinity(x) && !float.IsNaN(y) && !float.IsInfinity(y);
            if (x < left) left = x;
            if (x > right) right = x;
            if (y < top) top = y;
            if (y > bottom) bottom = y;
        }
        return finite ? new SKRect(left, top, right, bottom) : SKRect.Empty;
    }

    // The points as floats in a per-thread scratch array, mapped through
    // this shape's transform when it has one.
    private SKPoint[] PointsAsSkia(out int count)
    {
        count = coordinateCount / 2;
        var points = Points(count);
        for (var point = 0; point < count; point++)
            points[point] = new SKPoint((float)coordinates[point * 2], (float)coordinates[point * 2 + 1]);
        if (mapped && count > 0) mapping.MapPoints(points.AsSpan(0, count), points.AsSpan(0, count));
        return points;
    }

    private SKPoint[] MappedPoints() => PointsAsSkia(out _);

    private static SKPoint[] Points(int count)
    {
        var points = scratchPoints;
        if (points is null || points.Length < count)
        {
            points = new SKPoint[Math.Max(count, 64)];
            // A large path's scratch does not outlive it.
            if (count <= 1 << 16) scratchPoints = points;
        }
        return points;
    }

    private double Round(double value) => IsDoublePrecision ? value : (float)value;

    private static void CheckWindingRule(int rule)
    {
        if (rule != WIND_EVEN_ODD && rule != WIND_NON_ZERO)
            throw new ArgumentException("winding rule must be WIND_EVEN_ODD or WIND_NON_ZERO");
    }

    // Before a segment is added: a transformed shape takes arrays of its own,
    // and the arrays grow as Path2D's do.
    private void Append(byte type, int coordinateLength, bool allowEmpty)
    {
        if (!allowEmpty && typeCount == 0)
            throw new InvalidOperationException("missing initial moveto in path definition");
        Detach();
        if (typeCount == types.Length)
            Array.Resize(ref types, Math.Max(DefaultCapacity, types.Length + Math.Min(types.Length, 10_000)));
        if (coordinateCount + coordinateLength > coordinates.Length)
            Array.Resize(ref coordinates, Math.Max(coordinateCount + coordinateLength,
                coordinates.Length + Math.Max(coordinateLength, Math.Min(coordinates.Length, 20_000))));
        types[typeCount++] = type;
    }

    // A transformed shape about to change becomes a plain path of its own.
    private void Detach()
    {
        if (!borrowed && !mapped) return;
        var points = mapped ? MappedPoints() : null;
        var ownTypes = new byte[Math.Max(typeCount, DefaultCapacity)];
        var ownCoordinates = new double[Math.Max(coordinateCount, DefaultCapacity * 2)];
        Array.Copy(types, ownTypes, typeCount);
        if (points is null)
        {
            Array.Copy(coordinates, ownCoordinates, coordinateCount);
        }
        else
        {
            for (var point = 0; point < coordinateCount / 2; point++)
            {
                ownCoordinates[point * 2] = points[point].X;
                ownCoordinates[point * 2 + 1] = points[point].Y;
            }
        }
        types = ownTypes;
        coordinates = ownCoordinates;
        borrowed = mapped = false;
    }

    // Before segments already there are overwritten: shapes reading them
    // keep the old arrays.
    private void PrepareOverwrite()
    {
        Detach();
        if (!lent) return;
        types = (byte[])types.Clone();
        coordinates = (double[])coordinates.Clone();
        lent = false;
    }
}

// java.awt.geom.Area: a path with double precision whose intersections Skia
// computes (PdfCartonAreaIntersection) on Skia copies made for the operation.
// new Area(shape) copies the shape's segments, as Java does.
public sealed class JavaArea : JavaPath2D
{
    public JavaArea()
        : base(WIND_NON_ZERO, 20, doublePrecision: true)
    {
    }

    internal JavaArea(JavaPath2D shape)
        : base(shape, doublePrecision: true, share: true)
    {
    }

    // The result replaces this area's segments. Skia intersects small paths
    // and makes its even-odd result the nonzero one an Area has, with its
    // holes reversed; larger ones are clipped (PdfCartonAreaIntersection).
    public void Intersect(JavaArea other)
    {
        ArgumentNullException.ThrowIfNull(other);
        using var first = ToSKPath();
        using var second = ReferenceEquals(other, this) ? null : other.ToSKPath();
        using var result = new SKPath();
        if (!PdfCartonAreaIntersection.Intersect(first, second ?? first, result))
            throw new InvalidOperationException("Unable to intersect paths.");
        ReadFrom(result);
    }

    public override bool Equals(object? value)
    {
        if (ReferenceEquals(this, value)) return true;
        if (value is not JavaArea other) return false;
        using var first = ToSKPath();
        using var second = other.ToSKPath();
        using var difference = new SKPath();
        return first.Op(second, SKPathOp.Xor, difference) && difference.IsEmpty;
    }

    public override int GetHashCode() => Bounds.GetHashCode();
}

// A java.awt.geom.PathIterator over a path's segments as Path2D's iterators
// read them: in place, one at a time, each transformed as it is read (as
// Skia maps points). It holds no native memory.
public sealed class JavaPathIterator : IDisposable
{
    public const int WIND_EVEN_ODD = 0;
    public const int WIND_NON_ZERO = 1;
    public const int SEG_MOVETO = 0;
    public const int SEG_LINETO = 1;
    public const int SEG_QUADTO = 2;
    public const int SEG_CUBICTO = 3;
    public const int SEG_CLOSE = 4;

    private readonly JavaPath2D source;
    private readonly int count;
    private readonly SKMatrix transform;
    private readonly bool transformed;
    private readonly SKPoint[] points = new SKPoint[3];
    private readonly double[] values = new double[6];
    private int segment;
    private int point;

    internal JavaPathIterator(JavaPath2D path, object? transform)
    {
        ArgumentNullException.ThrowIfNull(path);
        source = path;
        count = path.SegmentCount;
        if (transform is SKMatrix matrix && !PdfCartonFontCompat.IsDefaultMatrix(matrix))
        {
            this.transform = matrix;
            transformed = true;
        }
    }

    public bool IsDone() => segment >= count;

    public void Next()
    {
        if (IsDone()) return;
        point += JavaPath2D.PointCount(source.TypeAt(segment));
        segment++;
    }

    public int CurrentSegment(double[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        var length = Load(coordinates.Length);
        Array.Copy(values, coordinates, length * 2);
        return source.TypeAt(segment);
    }

    public int CurrentSegment(float[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        var length = Load(coordinates.Length);
        for (var index = 0; index < length * 2; index++)
            coordinates[index] = (float)values[index];
        return source.TypeAt(segment);
    }

    public int GetWindingRule() => source.WindingRule;

    public void Dispose() => segment = count;

    // The current segment's points into values: in the path's own precision,
    // or as floats once mapped.
    private int Load(int capacity)
    {
        if (IsDone()) throw new InvalidOperationException("Path iterator is exhausted.");
        var length = JavaPath2D.PointCount(source.TypeAt(segment));
        if (capacity < length * 2)
            throw new IndexOutOfRangeException("Path coordinate array is too small.");
        if (!transformed && !source.IsMapped)
        {
            for (var index = 0; index < length * 2; index++)
                values[index] = source.CoordinateAt(point * 2 + index);
            return length;
        }
        for (var index = 0; index < length; index++)
            points[index] = source.PointAt(point + index);
        if (transformed) transform.MapPoints(points.AsSpan(0, length), points.AsSpan(0, length));
        for (var index = 0; index < length; index++)
        {
            values[index * 2] = points[index].X;
            values[index * 2 + 1] = points[index].Y;
        }
        return length;
    }
}

#pragma warning restore CS0618
