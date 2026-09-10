#!/usr/bin/env python3
r"""Summarize speedscope attribution; weights are not benchmark elapsed timings.

Example: python3 summarize-profile.py trace.speedscope.json \
    --include '!PdfCarton.Benchmarks.RenderingBenchmarks.RenderPage\(' \
    --include '^Activity WorkloadActual'

Use the benchmark method as the include expression to omit unrelated setup,
JIT, and background GC stacks. Work on the selected stack (including blocking,
JIT, or GC) remains included. Anchor expressions to avoid matching process
arguments. Native stacks may be unresolved by EventPipe. Its CPU_TIME and
UNMANAGED_CODE_TIME leaf markers are omitted to show their sampled caller;
this is attribution to the nearest visible frame, not proof of exclusive CPU.
Format: https://github.com/jlfwong/speedscope/blob/main/src/lib/file-format-spec.ts
"""

import argparse
from collections import Counter
import json
from pathlib import Path
import re


def stacks(profile):
    if profile["type"] == "sampled":
        samples = profile["samples"]
        weights = profile.get("weights", [1] * len(samples))
        if len(samples) != len(weights):
            raise ValueError("Sample and weight counts differ")
        yield from zip(samples, weights)
    elif profile["type"] == "evented":
        stack = []
        previous = profile["startValue"]
        for event in profile["events"]:
            if event["at"] < previous:
                raise ValueError("Events are not ordered")
            yield stack, event["at"] - previous
            previous = event["at"]
            if event["type"] == "O":
                stack.append(event["frame"])
            elif event["type"] == "C" and stack and stack[-1] == event["frame"]:
                stack.pop()
            else:
                raise ValueError("Unbalanced or unknown event")
        if stack:
            raise ValueError("Unclosed event frames")
    else:
        raise ValueError(f"Unsupported profile type: {profile['type']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--include", required=True, action="append", help="Regex matching a frame; repeat to require all expressions")
    parser.add_argument("--show", default=".", help="Display only matching frame names; denominator is unchanged")
    parser.add_argument("--top", type=int, default=20)
    args = parser.parse_args()
    includes = [re.compile(pattern) for pattern in args.include]
    show = re.compile(args.show)
    data = json.loads(args.trace.read_text())
    frames = [frame["name"] for frame in data["shared"]["frames"]]
    # Keep unlike units separate; profile weights can represent time or samples.
    groups = {}
    for profile in data["profiles"]:
        inclusive, leaf, totals = groups.setdefault(profile["unit"], (Counter(), Counter(), [0, 0]))
        for stack, weight in stacks(profile):
            if weight < 0:
                raise ValueError("Negative stack weight")
            names = [frames[index] for index in stack]
            totals[0] += weight
            if not names or not all(any(pattern.search(name) for name in names) for pattern in includes):
                continue
            totals[1] += weight
            while names and names[-1] in {"CPU_TIME", "UNMANAGED_CODE_TIME"}:
                names.pop()
            if names:
                leaf[names[-1]] += weight
            for name in set(names):
                inclusive[name] += weight
    matched = False
    for unit, (inclusive, leaf, (total, selected)) in groups.items():
        print(f"{args.trace.name}: selected weight {selected:.3f}/{total:.3f} {unit}")
        if selected == 0:
            continue
        matched = True
        for title, counts in (("Inclusive (overlapping)", inclusive), ("Leaf", leaf)):
            print(f"\n{title}: % of selected weight; weight ({unit}); frame")
            visible = Counter({name: weight for name, weight in counts.items() if show.search(name)})
            for name, weight in visible.most_common(args.top):
                print(f"{weight / selected * 100:6.2f}% {weight:12.3f}  {name}")
    if not matched:
        parser.error("No matching stack weight; inspect frame names or possible inlining")


if __name__ == "__main__":
    main()
