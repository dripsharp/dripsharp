#!/usr/bin/env python3
"""Benchmark identical raster workloads against two actual PdfCarton revisions.

Creates retained, isolated git-archive source snapshots and runs BenchmarkDotNet
sequentially. Does not check out, build in, or modify the product working tree.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


DEFAULT_BEFORE = "e235b924c9a52f23603e02a9aa7ff80bed3c98e4"
DEFAULT_AFTER = "e9930c79165d077df0593981947810fdf5c410c9"
FILTERS = {
    "all": ["*RasterBenchmarks.*", "*BlendComparisonBenchmarks.*"],
    "raster": ["*RasterBenchmarks.*"],
    "blend": ["*BlendComparisonBenchmarks.*"],
    "write": ["*RasterBenchmarks.WritePixels"],
    "read": ["*RasterBenchmarks.ReadPixels*"],
    "read-reused": ["*RasterBenchmarks.ReadPixelsReused"],
    "read-allocated": ["*RasterBenchmarks.ReadPixelsAllocated"],
    "integration": ["*RasterStrategyBenchmarks.CurrentRead", "*RasterStrategyBenchmarks.CurrentWrite"],
    "strategies": ["*RasterStrategyBenchmarks.CurrentRead", "*RasterStrategyBenchmarks.CurrentWrite",
                   "*RasterStrategyBenchmarks.Vector128Read", "*RasterStrategyBenchmarks.Vector128Write"],
    "rendering": ["*RenderingBenchmarks.*"],
}


def git_revision(repository: Path, revision: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repository), "rev-parse", "--verify", "--end-of-options",
         revision + "^{commit}"], text=True,
    ).strip()


def snapshot(repository: Path, revision: str, destination: Path) -> None:
    destination.mkdir(parents=True)
    with tempfile.TemporaryFile() as archive:
        subprocess.run(
            ["git", "-C", str(repository), "archive", "--format=tar", revision],
            stdout=archive, check=True,
        )
        archive.seek(0)
        with tarfile.open(fileobj=archive, mode="r:") as contents:
            # Reject escaping paths and unsafe links, including in older snapshots.
            contents.extractall(destination, filter="data")


def working_snapshot(repository: Path, destination: Path) -> None:
    """Freeze generated sources before committing, excluding ignored build output."""
    destination.mkdir(parents=True)
    paths = subprocess.check_output(
        ["git", "-C", str(repository), "ls-files", "-z", "--cached", "--others", "--exclude-standard"]
    ).decode().split("\0")
    for relative in set(paths) - {""}:
        original = repository / relative
        if not original.exists():
            continue  # Tracked deletion in the candidate working tree.
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target, follow_symlinks=False)


def run_logged(command: list[str], cwd: Path, environment: dict[str, str], log: Path) -> int:
    with log.open("w") as stream:
        process = subprocess.Popen(
            command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        try:
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="", flush=True)
                stream.write(line)
            return process.wait()
        except BaseException:
            process.terminate()
            process.wait()
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", default=DEFAULT_BEFORE, help="Old product git ref or SHA")
    parser.add_argument("--after", default=DEFAULT_AFTER, help="New product git ref or SHA")
    parser.add_argument("--after-working-tree", action="store_true",
                        help="Freeze the current generated product working tree instead of --after")
    parser.add_argument("--output", type=Path, help="New output directory; must not already exist")
    parser.add_argument("--job", help="Optional BenchmarkDotNet job (e.g. short); omitted uses its default job")
    parser.add_argument("--operations", choices=FILTERS, default="all",
                        help="Methods to compare (default: three raster methods and one reset + blend; excludes large blend batch)")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    repository = source.parent.parent / "products" / "pdfcarton"
    revisions = {"before": git_revision(repository, args.before),
                 "after": git_revision(repository, "HEAD" if args.after_working_tree else args.after)}
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = (args.output or source.parent.parent / "validation-output" /
              ("pdfcarton-revision-comparison-" + timestamp)).resolve()
    if output == source or source in output.parents:
        parser.error("--output must be outside the benchmark source directory")
    output.mkdir(parents=True, exist_ok=False)
    metadata = {
        "started_utc": timestamp,
        "product_repository": str(repository),
        "benchmark_source": str(source),
        "requested_refs": {"before": args.before,
                           "after": "WORKING_TREE" if args.after_working_tree else args.after},
        "after_working_tree": args.after_working_tree,
        "revisions": revisions,
        "operations": args.operations,
        "job": args.job or "BenchmarkDotNet default",
        "runs": [],
    }
    metadata_path = output / "comparison.json"

    def save() -> None:
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")

    save()
    print(f"Output: {output}\nBefore: {revisions['before']}\nAfter:  {revisions['after']}", flush=True)
    # Freeze one harness copy before either run, so both use the same sources.
    harness = output / "benchmark-source"
    # Preserve the exact candidate workloads when comparing the integrated API
    # with the prototype; historical comparisons do not need those kernels.
    ignored = ["bin", "obj", "__pycache__", "BenchmarkDotNet.Artifacts"]
    if args.operations not in ("integration", "strategies"):
        ignored.extend(["RasterStrategyBenchmarks.cs", "PortableRasterBenchmarks.cs"])
    shutil.copytree(source, harness, ignore=shutil.ignore_patterns(
        *ignored))
    # Freeze both products before measurements, including an uncommitted
    # generated candidate, so later workspace edits cannot affect either run.
    for label, revision in revisions.items():
        work = output / label
        product = work / "products" / "pdfcarton"
        benchmark = work / "validation" / "pdfcube-benchmarks"
        if label == "after" and args.after_working_tree:
            working_snapshot(repository, product)
            (work / "product.patch").write_bytes(subprocess.check_output(
                ["git", "-C", str(repository), "diff", "--binary", "HEAD"]))
            (work / "product-status.txt").write_bytes(subprocess.check_output(
                ["git", "-C", str(repository), "status", "--short"]))
        else:
            snapshot(repository, revision, product)
        shutil.copytree(harness, benchmark)

    failures = False
    for label, revision in revisions.items():
        work = output / label
        product = work / "products" / "pdfcarton"
        benchmark = work / "validation" / "pdfcube-benchmarks"
        artifacts = work / "artifacts"
        command = ["dotnet", "run", "--configuration", "Release", "--project",
                   str(benchmark / "DripSharp.PdfCarton.Tests.csproj"), "--",
                   "--filter", *FILTERS[args.operations], "--artifacts", str(artifacts)]
        if args.job:
            command += ["--job", args.job]
        environment = os.environ.copy()
        # MSBuild imports this environment property in both the launcher and BDN's
        # generated child build; a launcher-only -p argument would not suffice.
        environment["PdfCartonRoot"] = str(product)
        environment.setdefault("PDFBOX_RESOURCE_ROOT", str(
            source.parent.parent / "research" / "pdfbox" / "pdfbox" / "src" / "test" / "resources"))
        entry = {"label": label, "revision": revision,
                 "working_tree": label == "after" and args.after_working_tree,
                 "command": command,
                 "PdfCartonRoot": str(product), "artifacts": str(artifacts),
                 "log": str(work / "run.log"), "status": "running"}
        metadata["runs"].append(entry)
        save()
        print(f"\nRunning {label}: {revision}", flush=True)
        code = run_logged(command, benchmark, environment, work / "run.log")
        entry.update(exit_code=code, status="passed" if code == 0 else "failed")
        save()
        failures |= code != 0
    metadata["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    save()
    print(f"\nComparison metadata and commands: {metadata_path}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
