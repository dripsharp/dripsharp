public final class GenerateMirroredData
{
    private GenerateMirroredData()
    {
    }

    public static void main(String[] args)
    {
        int rangeStart = -1;
        int previous = -1;
        int ranges = 0;
        int codePoints = 0;
        for (int codePoint = Character.MIN_CODE_POINT;
                codePoint <= Character.MAX_CODE_POINT;
                codePoint++)
        {
            if (Character.isMirrored(codePoint))
            {
                codePoints++;
                if (rangeStart < 0)
                {
                    rangeStart = codePoint;
                }
                else if (codePoint != previous + 1)
                {
                    printRange(rangeStart, previous);
                    ranges++;
                    rangeStart = codePoint;
                }
                previous = codePoint;
            }
        }
        if (rangeStart >= 0)
        {
            printRange(rangeStart, previous);
            ranges++;
        }
        System.err.println("ranges=" + ranges + " codePoints=" + codePoints);
    }

    private static void printRange(int start, int end)
    {
        System.out.printf("        0x%06Xu, 0x%06Xu,%n", start, end);
    }
}
