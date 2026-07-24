#nullable enable

using System;
using PdfCube.FontBox.Util;
using PdfCube.IO;

internal static class Program
{
    private static void Main()
    {
        var bounds = new BoundingBox(1, 2, 6, 10);
        if (bounds.GetWidth() != 5 ||
            bounds.GetHeight() != 8 ||
            !bounds.Contains(3, 4) ||
            bounds.Contains(7, 4))
        {
            throw new InvalidOperationException(
                "Translated FontBox bounding-box behavior did not match Java.");
        }

        using var input = new RandomAccessReadBuffer(new sbyte[] { 1, -2 });
        if (input.Read() != 1 || input.Read() != 254 || input.Read() != -1)
        {
            throw new InvalidOperationException(
                "The transitive PdfCube.IO package boundary is not usable.");
        }

        Console.WriteLine("PdfCube.FontBox focused behavior passed.");
    }
}
