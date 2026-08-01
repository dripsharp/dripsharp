// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

#nullable enable
namespace DripSharp.CrossTarget.Generated.Pdfcarton;

public interface Clock
{
    int tick();
}

public class RealClock : Clock
{
    public virtual int tick() => 5;
}
