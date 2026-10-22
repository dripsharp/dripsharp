// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// java.awt.geom.Area.intersect for paths of any size.
#nullable enable

using System;
using System.Collections.Generic;
using SkiaSharp;

namespace DripSharp.Runtime;

#pragma warning disable CS0618 // SKPath mutation is the m150 bridge for Java GeneralPath.

// Skia's path ops intersect small paths well, but slow down sharply and fail
// (Op returns false) on paths of some thousands of segments, where Java's Area
// always has a result. Larger intersections are clipped here instead:
// - A path intersected with a small convex polygon (a rectangular clip, a
//   bounding box) is clipped to each of its edges in turn. Clipping a closed
//   contour to a half-plane keeps its winding number at every point inside the
//   half-plane and gives 0 outside it: the parts outside are replaced by runs
//   along the edge line, which enclose no area inside. So the clipped path,
//   filled by its own rule, is exactly the intersection, curves kept.
// - Two large paths are split into cells by recursive bisection, each clipped
//   as above. A cell that one path covers entirely (only runs along the cell's
//   boundary are left of it) takes the other path's part as it is, a cell
//   either path misses is skipped, and a cell with few segments of both left
//   is intersected by Skia. The cells share no interior, so their results
//   together are the intersection.
internal static class PdfCartonAreaIntersection
{
    // At or below this many verbs Skia intersects the paths, as before.
    private const int OpVerbLimit = 2000;
    // Cells are bisected until both paths together have at most this many.
    private const int CellVerbLimit = 600;
    private const int ConvexEdgeLimit = 64;
    private const int CellDepthLimit = 48;
    // Off the middle, so split lines rarely meet grid-aligned edges.
    private const double SplitRatio = 0.4973;

    // first ∩ second into result, which may be either operand: both are read
    // before it is written. Returns false only when Skia cannot intersect a
    // path with an inverse fill (which Java paths do not have).
    internal static bool Intersect(SKPath first, SKPath second, SKPath result)
    {
        if (first.VerbCount + second.VerbCount <= OpVerbLimit && TryOp(first, second, result))
            return true;
        if (IsInverse(first.FillType) || IsInverse(second.FillType))
            return TryOp(first, second, result);
        var a = Shape.Read(first);
        var b = Shape.Read(second);
        if (b.TryConvexPlanes(out var planes))
        {
            Write(ClipAll(a, planes), result);
            return true;
        }
        if (a.TryConvexPlanes(out planes))
        {
            Write(ClipAll(b, planes), result);
            return true;
        }
        var cell = Rect.Intersection(a.Bounds(), b.Bounds());
        result.Reset();
        result.FillType = a.Fill;
        if (cell.IsEmpty) return true;
        var magnitude = Math.Max(
            Math.Max(Math.Abs(cell.X0), Math.Abs(cell.X1)),
            Math.Max(Math.Abs(cell.Y0), Math.Abs(cell.Y1)));
        // About eight float steps at the paths' magnitude.
        var solver = new CellSolver(result, a.Fill, Math.Max(1e-9, magnitude / (1 << 21)));
        var bRegion = cell.Outset(cell.LongSide / 64 + solver.MinCell);
        solver.Solve(ClipAll(a, cell.Planes()), ClipAll(b, bRegion.Planes()), cell, bRegion, 0);
        result.FillType = a.Fill;
        return true;
    }

    private static bool IsInverse(SKPathFillType fill) =>
        fill == SKPathFillType.InverseWinding || fill == SKPathFillType.InverseEvenOdd;

    // Skia's intersection, made nonzero. Op writes its result while it runs,
    // so it never writes into an operand here: a failed Op would leave it
    // part written.
    private static bool TryOp(SKPath first, SKPath second, SKPath result)
    {
        var aliased = ReferenceEquals(result, first) || ReferenceEquals(result, second);
        var target = aliased ? new SKPath() : result;
        try
        {
            if (!first.Op(second, SKPathOp.Intersect, target)) return false;
            target.ToWinding(target);
            if (aliased)
            {
                result.Reset();
                result.AddPath(target);
                result.FillType = target.FillType;
            }
            return true;
        }
        finally
        {
            if (aliased) target.Dispose();
        }
    }

    private static void Write(Shape shape, SKPath result)
    {
        result.Reset();
        shape.AppendTo(result);
        result.FillType = shape.Fill;
    }

    private static Shape ClipAll(Shape shape, HalfPlane[] planes)
    {
        foreach (var plane in planes)
        {
            if (shape.Contours.Count == 0) break;
            shape = shape.Clip(plane);
        }
        return shape;
    }

    private sealed class CellSolver(SKPath output, SKPathFillType fill, double minCell)
    {
        internal double MinCell => minCell;

        // a is clipped to cell exactly; b to bRegion, which holds cell with a
        // margin, so b's runs along its region's boundary never lie on a's.
        internal void Solve(Shape a, Shape b, Rect cell, Rect bRegion, int depth)
        {
            if (a.Contours.Count == 0 || b.Contours.Count == 0) return;
            if (b.TryUniformCoverage(bRegion.Area, out var bCovers))
            {
                if (bCovers) a.AppendTo(output);
                return;
            }
            if (a.TryUniformCoverage(cell.Area, out var aCovers))
            {
                if (!aCovers) return;
                // Else b's part of the cell is the result, if it fills by the
                // result's rule; Skia makes it nonzero otherwise.
                if (b.Fill == fill)
                {
                    ClipAll(b, cell.Planes()).AppendTo(output);
                    return;
                }
            }
            var small = cell.LongSide <= minCell || depth >= CellDepthLimit;
            var verbs = a.VerbCount + b.VerbCount;
            if (verbs <= CellVerbLimit || small)
            {
                if (verbs <= 8 * CellVerbLimit && OpInto(a, b)) return;
                if (small)
                {
                    FillIfCentreInBoth(a, b, cell);
                    return;
                }
            }

            var vertical = cell.X1 - cell.X0 >= cell.Y1 - cell.Y0;
            var lo = vertical ? cell.X0 : cell.Y0;
            var hi = vertical ? cell.X1 : cell.Y1;
            var split = lo + (hi - lo) * SplitRatio;
            var lower = vertical ? cell with { X1 = split } : cell with { Y1 = split };
            var upper = vertical ? cell with { X0 = split } : cell with { Y0 = split };
            foreach (var (child, sign) in new[] { (lower, 1.0), (upper, -1.0) })
            {
                var margin = child.LongSide / 64 + minCell / 4;
                var edge = split + sign * margin;
                var aPlane = vertical ? new HalfPlane(sign, 0, sign * split) : new HalfPlane(0, sign, sign * split);
                var bPlane = vertical ? new HalfPlane(sign, 0, sign * edge) : new HalfPlane(0, sign, sign * edge);
                var region = sign > 0
                    ? (vertical ? bRegion with { X1 = Math.Min(bRegion.X1, edge) } : bRegion with { Y1 = Math.Min(bRegion.Y1, edge) })
                    : (vertical ? bRegion with { X0 = Math.Max(bRegion.X0, edge) } : bRegion with { Y0 = Math.Max(bRegion.Y0, edge) });
                Solve(a.Clip(aPlane), b.Clip(bPlane), child, region, depth + 1);
            }
        }

        // A cell a few float steps wide that Skia still cannot intersect is
        // inside both paths or not, as its centre is.
        private void FillIfCentreInBoth(Shape a, Shape b, Rect cell)
        {
            float x = (float)((cell.X0 + cell.X1) / 2), y = (float)((cell.Y0 + cell.Y1) / 2);
            using var first = a.ToPath();
            using var second = b.ToPath();
            if (first.Contains(x, y) && second.Contains(x, y))
                output.AddRect(new SKRect((float)cell.X0, (float)cell.Y0, (float)cell.X1, (float)cell.Y1));
        }

        // Skia's intersection of the cell's parts, made nonzero so that it
        // fills alike by either rule.
        private bool OpInto(Shape a, Shape b)
        {
            using var first = a.ToPath();
            using var second = b.ToPath();
            using var piece = new SKPath();
            if (!first.Op(second, SKPathOp.Intersect, piece) || !piece.ToWinding(piece)) return false;
            output.AddPath(piece);
            return true;
        }
    }

    private readonly record struct Rect(double X0, double Y0, double X1, double Y1)
    {
        internal bool IsEmpty => !(X1 > X0 && Y1 > Y0);
        internal double Area => (X1 - X0) * (Y1 - Y0);
        internal double LongSide => Math.Max(X1 - X0, Y1 - Y0);

        internal static Rect Intersection(Rect a, Rect b) =>
            new(Math.Max(a.X0, b.X0), Math.Max(a.Y0, b.Y0), Math.Min(a.X1, b.X1), Math.Min(a.Y1, b.Y1));

        internal Rect Outset(double by) => new(X0 - by, Y0 - by, X1 + by, Y1 + by);

        internal HalfPlane[] Planes() =>
        [
            new(-1, 0, -X0), new(1, 0, X1), new(0, -1, -Y0), new(0, 1, Y1),
        ];
    }

    // The points with A·x + B·y <= C.
    private readonly record struct HalfPlane(double A, double B, double C)
    {
        internal double Distance(double x, double y) => A * x + B * y - C;

        internal (double X, double Y) Crossing(double sx, double sy, double ex, double ey, double ds, double de)
        {
            var t = ds / (ds - de);
            var x = sx + (ex - sx) * t;
            var y = sy + (ey - sy) * t;
            // On an axis-aligned line the crossing's coordinate is exact.
            return Snap(x, y);
        }

        // On an axis-aligned line a point on it has that coordinate exactly.
        internal (double X, double Y) Snap(double x, double y)
        {
            if (B == 0) x = C / A;
            else if (A == 0) y = C / B;
            return (x, y);
        }
    }

    private enum SegmentKind : byte { Line, Quad, Cubic }

    // A segment from the previous segment's end (contours are cyclic).
    // OnBoundary marks runs added along a clipping line.
    private readonly record struct Segment(
        SegmentKind Kind, bool OnBoundary,
        double X1, double Y1, double X2, double Y2, double X, double Y)
    {
        internal static Segment Line(double x, double y, bool onBoundary) =>
            new(SegmentKind.Line, onBoundary, 0, 0, 0, 0, x, y);
    }

    private sealed class Contour(List<Segment> segments, bool onlyBoundary, double area)
    {
        internal List<Segment> Segments { get; } = segments;
        internal bool OnlyBoundary { get; } = onlyBoundary;
        internal double Area { get; } = area;

        internal static Contour? Make(List<Segment> segments)
        {
            // Drop zero-length lines.
            var kept = new List<Segment>(segments.Count);
            if (segments.Count == 0) return null;
            var last = segments[^1];
            double px = last.X, py = last.Y;
            foreach (var segment in segments)
            {
                if (segment.Kind == SegmentKind.Line && segment.X == px && segment.Y == py) continue;
                kept.Add(segment);
                (px, py) = (segment.X, segment.Y);
            }
            var onlyBoundary = true;
            var onlyLines = true;
            foreach (var segment in kept)
            {
                onlyBoundary &= segment.OnBoundary;
                onlyLines &= segment.Kind == SegmentKind.Line;
            }
            if (kept.Count == 0 || (onlyLines && kept.Count <= 2)) return null;
            if (!onlyLines) return new Contour(kept, false, 0);
            double area = 0, minX = double.MaxValue, minY = double.MaxValue, maxX = double.MinValue, maxY = double.MinValue;
            (px, py) = (kept[^1].X, kept[^1].Y);
            foreach (var segment in kept)
            {
                area += px * segment.Y - segment.X * py;
                (px, py) = (segment.X, segment.Y);
                minX = Math.Min(minX, px); maxX = Math.Max(maxX, px);
                minY = Math.Min(minY, py); maxY = Math.Max(maxY, py);
            }
            area /= 2;
            // Runs along clipping lines alone enclose nothing unless they go
            // around the clipped region.
            var size = maxX - minX + (maxY - minY);
            if (onlyBoundary && Math.Abs(area) <= 1e-9 * size * size) return null;
            return new Contour(kept, onlyBoundary, area);
        }

        internal Contour? Clip(HalfPlane plane)
        {
            var last = Segments[^1];
            double min = plane.Distance(last.X, last.Y), max = min;
            foreach (var s in Segments)
            {
                var d = plane.Distance(s.X, s.Y);
                min = Math.Min(min, d); max = Math.Max(max, d);
                if (s.Kind == SegmentKind.Line) continue;
                d = plane.Distance(s.X1, s.Y1);
                min = Math.Min(min, d); max = Math.Max(max, d);
                if (s.Kind != SegmentKind.Cubic) continue;
                d = plane.Distance(s.X2, s.Y2);
                min = Math.Min(min, d); max = Math.Max(max, d);
            }
            if (max <= 0) return this;
            if (min >= 0) return null;
            var output = new List<Segment>(Segments.Count + 4);
            double sx = last.X, sy = last.Y;
            foreach (var s in Segments)
            {
                if (s.Kind == SegmentKind.Line) ClipLine(plane, sx, sy, s.X, s.Y, s.OnBoundary, output);
                else ClipCurve(plane, sx, sy, s, output);
                (sx, sy) = (s.X, s.Y);
            }
            return Make(output);
        }

        private static void ClipLine(
            HalfPlane plane, double sx, double sy, double ex, double ey, bool onBoundary, List<Segment> output)
        {
            var ds = plane.Distance(sx, sy);
            var de = plane.Distance(ex, ey);
            if (ds <= 0)
            {
                if (de <= 0)
                {
                    // A line on the clipping line runs along the boundary too.
                    output.Add(Segment.Line(ex, ey, onBoundary || (ds == 0 && de == 0)));
                }
                else
                {
                    var (x, y) = plane.Crossing(sx, sy, ex, ey, ds, de);
                    output.Add(Segment.Line(x, y, onBoundary));
                }
            }
            else if (de <= 0)
            {
                var (x, y) = plane.Crossing(sx, sy, ex, ey, ds, de);
                // From where the contour left the half-plane, along its line.
                output.Add(Segment.Line(x, y, true));
                output.Add(Segment.Line(ex, ey, onBoundary));
            }
        }

        // Exact: the curve is split where it crosses the line, so each piece
        // lies on one side, and pieces outside are left out as lines are.
        // Like ClipLine, it leaves the output at the curve's end if that is
        // inside, else where the curve last left the half-plane.
        private static void ClipCurve(HalfPlane plane, double sx, double sy, Segment s, List<Segment> output)
        {
            var d0 = plane.Distance(sx, sy);
            var d1 = plane.Distance(s.X1, s.Y1);
            var d2 = s.Kind == SegmentKind.Cubic ? plane.Distance(s.X2, s.Y2) : d1;
            var d3 = plane.Distance(s.X, s.Y);
            if (d0 <= 0 && d1 <= 0 && d2 <= 0 && d3 <= 0)
            {
                output.Add(s);
                return;
            }
            // Outside (its control polygon holds it): what it encloses with
            // its chord lies outside too.
            if (d0 >= 0 && d1 >= 0 && d2 >= 0 && d3 >= 0)
            {
                ClipLine(plane, sx, sy, s.X, s.Y, true, output);
                return;
            }
            double Distance(double t)
            {
                var u = 1 - t;
                return s.Kind == SegmentKind.Quad
                    ? u * u * d0 + 2 * u * t * d1 + t * t * d3
                    : u * u * u * d0 + 3 * u * u * t * d1 + 3 * u * t * t * d2 + t * t * t * d3;
            }
            var stops = Crossings(s.Kind, d0, d1, d2, d3, Distance);
            stops.Add(1);
            double from = 0, px = sx, py = sy;
            var rest = s;
            var lastInside = false;
            foreach (var to in stops)
            {
                var piece = rest;
                if (to < 1)
                {
                    (piece, rest) = SplitAt(px, py, rest, (to - from) / (1 - from));
                    var (x, y) = plane.Snap(piece.X, piece.Y);
                    piece = piece with { X = x, Y = y };
                }
                lastInside = Distance((from + to) / 2) <= 0;
                if (lastInside)
                {
                    // Along the line from where the contour left it.
                    if (from > 0 || d0 > 0) output.Add(Segment.Line(px, py, true));
                    output.Add(piece);
                }
                (from, px, py) = (to, piece.X, piece.Y);
            }
            if (!lastInside && d3 <= 0) output.Add(Segment.Line(s.X, s.Y, true));
        }

        // The parameters in (0, 1) where the curve's distance changes sign:
        // its monotone stretches, split at the derivative's roots, are bisected.
        private static List<double> Crossings(
            SegmentKind kind, double d0, double d1, double d2, double d3, Func<double, double> distance)
        {
            var stops = new List<double>(4) { 0 };
            void Stop(double t)
            {
                if (t > 0 && t < 1) stops.Add(t);
            }
            if (kind == SegmentKind.Quad)
            {
                var denominator = d0 - 2 * d1 + d3;
                if (denominator != 0) Stop((d0 - d1) / denominator);
            }
            else
            {
                double e0 = d1 - d0, e1 = d2 - d1, e2 = d3 - d2;
                double a = e0 - 2 * e1 + e2, b = 2 * (e1 - e0), c = e0;
                if (Math.Abs(a) <= 1e-12 * (Math.Abs(b) + Math.Abs(c)))
                {
                    if (b != 0) Stop(-c / b);
                }
                else
                {
                    var discriminant = b * b - 4 * a * c;
                    if (discriminant >= 0)
                    {
                        var q = -0.5 * (b + (b < 0 ? -1 : 1) * Math.Sqrt(discriminant));
                        Stop(q / a);
                        if (q != 0) Stop(c / q);
                    }
                }
            }
            stops.Add(1);
            stops.Sort();
            var crossings = new List<double>(3);
            for (var index = 1; index < stops.Count; index++)
            {
                double lo = stops[index - 1], hi = stops[index];
                double fLo = distance(lo), fHi = distance(hi);
                // A stretch that ends on the line at a turning point.
                if (fLo == 0 && index > 1) crossings.Add(lo);
                if (fLo * fHi >= 0) continue;
                for (var step = 0; step < 64 && hi - lo > 1e-15; step++)
                {
                    var middle = (lo + hi) / 2;
                    var f = distance(middle);
                    if ((f < 0) == (fLo < 0)) (lo, fLo) = (middle, f);
                    else hi = middle;
                }
                crossings.Add((lo + hi) / 2);
            }
            return crossings;
        }

        // De Casteljau at t.
        private static (Segment, Segment) SplitAt(double sx, double sy, Segment s, double t)
        {
            static double Mix(double a, double b, double t) => a + (b - a) * t;
            if (s.Kind == SegmentKind.Quad)
            {
                double ax = Mix(sx, s.X1, t), ay = Mix(sy, s.Y1, t);
                double bx = Mix(s.X1, s.X, t), by = Mix(s.Y1, s.Y, t);
                double mx = Mix(ax, bx, t), my = Mix(ay, by, t);
                return (new Segment(SegmentKind.Quad, s.OnBoundary, ax, ay, 0, 0, mx, my),
                        new Segment(SegmentKind.Quad, s.OnBoundary, bx, by, 0, 0, s.X, s.Y));
            }
            double p01x = Mix(sx, s.X1, t), p01y = Mix(sy, s.Y1, t);
            double p12x = Mix(s.X1, s.X2, t), p12y = Mix(s.Y1, s.Y2, t);
            double p23x = Mix(s.X2, s.X, t), p23y = Mix(s.Y2, s.Y, t);
            double q0x = Mix(p01x, p12x, t), q0y = Mix(p01y, p12y, t);
            double q1x = Mix(p12x, p23x, t), q1y = Mix(p12y, p23y, t);
            double cx = Mix(q0x, q1x, t), cy = Mix(q0y, q1y, t);
            return (new Segment(SegmentKind.Cubic, s.OnBoundary, p01x, p01y, q0x, q0y, cx, cy),
                    new Segment(SegmentKind.Cubic, s.OnBoundary, q1x, q1y, p23x, p23y, s.X, s.Y));
        }
    }

    private sealed class Shape(List<Contour> contours, SKPathFillType fill)
    {
        internal List<Contour> Contours { get; } = contours;
        internal SKPathFillType Fill { get; } = fill;

        internal int VerbCount
        {
            get
            {
                var count = 0;
                foreach (var contour in Contours) count += contour.Segments.Count + 2;
                return count;
            }
        }

        internal static Shape Read(SKPath path)
        {
            var contours = new List<Contour>();
            List<Segment>? current = null;
            double startX = 0, startY = 0, x = 0, y = 0;
            void Finish()
            {
                if (current is { Count: > 0 })
                {
                    if (x != startX || y != startY) current.Add(Segment.Line(startX, startY, false));
                    if (Contour.Make(current) is { } contour) contours.Add(contour);
                }
                current = null;
                (x, y) = (startX, startY);
            }
            List<Segment> Current() => current ??= [];

            using var iterator = path.CreateRawIterator();
            var points = new SKPoint[4];
            SKPathVerb verb;
            while ((verb = iterator.Next(points)) != SKPathVerb.Done)
            {
                switch (verb)
                {
                    case SKPathVerb.Move:
                        Finish();
                        (startX, startY) = (x, y) = (points[0].X, points[0].Y);
                        break;
                    case SKPathVerb.Line:
                        (x, y) = (points[1].X, points[1].Y);
                        Current().Add(Segment.Line(x, y, false));
                        break;
                    case SKPathVerb.Quad:
                        (x, y) = (points[2].X, points[2].Y);
                        Current().Add(new Segment(SegmentKind.Quad, false, points[1].X, points[1].Y, 0, 0, x, y));
                        break;
                    case SKPathVerb.Conic:
                        var quads = SKPath.ConvertConicToQuads(points[0], points[1], points[2], iterator.ConicWeight(), 3);
                        for (var index = 1; index + 1 < quads.Length; index += 2)
                        {
                            (x, y) = (quads[index + 1].X, quads[index + 1].Y);
                            Current().Add(new Segment(
                                SegmentKind.Quad, false, quads[index].X, quads[index].Y, 0, 0, x, y));
                        }
                        break;
                    case SKPathVerb.Cubic:
                        (x, y) = (points[3].X, points[3].Y);
                        Current().Add(new Segment(
                            SegmentKind.Cubic, false, points[1].X, points[1].Y, points[2].X, points[2].Y, x, y));
                        break;
                    case SKPathVerb.Close:
                        Finish();
                        break;
                }
            }
            Finish();
            return new Shape(contours, path.FillType == SKPathFillType.EvenOdd
                ? SKPathFillType.EvenOdd : SKPathFillType.Winding);
        }

        internal Shape Clip(HalfPlane plane)
        {
            var clipped = new List<Contour>(Contours.Count);
            foreach (var contour in Contours)
            {
                if (contour.Clip(plane) is { } kept) clipped.Add(kept);
            }
            return new Shape(clipped, Fill);
        }

        internal Rect Bounds()
        {
            double minX = double.MaxValue, minY = double.MaxValue, maxX = double.MinValue, maxY = double.MinValue;
            void Include(double x, double y)
            {
                minX = Math.Min(minX, x); maxX = Math.Max(maxX, x);
                minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
            }
            foreach (var contour in Contours)
            {
                foreach (var s in contour.Segments)
                {
                    Include(s.X, s.Y);
                    if (s.Kind != SegmentKind.Line) Include(s.X1, s.Y1);
                    if (s.Kind == SegmentKind.Cubic) Include(s.X2, s.Y2);
                }
            }
            return new Rect(minX, minY, maxX, maxY);
        }

        // When only runs along the boundary of a convex region of the given
        // area are left, they wind the same number of times around every
        // point inside it: covers tells whether that fills the region.
        internal bool TryUniformCoverage(double regionArea, out bool covers)
        {
            covers = false;
            var winding = 0L;
            foreach (var contour in Contours)
            {
                if (!contour.OnlyBoundary) return false;
                winding += (long)Math.Round(contour.Area / regionArea);
            }
            covers = Fill == SKPathFillType.EvenOdd ? (winding & 1) != 0 : winding != 0;
            return true;
        }

        // One closed contour of a few lines that turns one way, once around.
        internal bool TryConvexPlanes(out HalfPlane[] planes)
        {
            planes = [];
            if (Contours.Count != 1) return false;
            var segments = Contours[0].Segments;
            if (segments.Count > ConvexEdgeLimit) return false;
            foreach (var s in segments)
            {
                if (s.Kind != SegmentKind.Line) return false;
            }
            var area = Contours[0].Area;
            if (area == 0) return false;
            var sign = Math.Sign(area);
            var count = segments.Count;
            var turning = 0.0;
            var result = new HalfPlane[count];
            for (var index = 0; index < count; index++)
            {
                var start = segments[(index + count - 1) % count];
                var end = segments[index];
                var next = segments[(index + 1) % count];
                double dx = end.X - start.X, dy = end.Y - start.Y;
                double nx = next.X - end.X, ny = next.Y - end.Y;
                var cross = dx * ny - dy * nx;
                // A turn the other way, or back along the same line.
                if (cross * sign < 0 || (cross == 0 && dx * nx + dy * ny < 0)) return false;
                turning += Math.Atan2(cross, dx * nx + dy * ny);
                // Inside lies left of each edge for a positive area.
                var a = sign * dy;
                var b = -sign * dx;
                result[index] = new HalfPlane(a, b, a * start.X + b * start.Y);
            }
            if (Math.Abs(Math.Abs(turning) - 2 * Math.PI) > 1e-6) return false;
            planes = result;
            return true;
        }

        internal SKPath ToPath()
        {
            var path = new SKPath { FillType = Fill };
            AppendTo(path);
            return path;
        }

        internal void AppendTo(SKPath path)
        {
            foreach (var contour in Contours)
            {
                var segments = contour.Segments;
                path.MoveTo((float)segments[^1].X, (float)segments[^1].Y);
                foreach (var s in segments)
                {
                    switch (s.Kind)
                    {
                        case SegmentKind.Line:
                            path.LineTo((float)s.X, (float)s.Y);
                            break;
                        case SegmentKind.Quad:
                            path.QuadTo((float)s.X1, (float)s.Y1, (float)s.X, (float)s.Y);
                            break;
                        default:
                            path.CubicTo((float)s.X1, (float)s.Y1, (float)s.X2, (float)s.Y2, (float)s.X, (float)s.Y);
                            break;
                    }
                }
                path.Close();
            }
        }
    }
}

#pragma warning restore CS0618
