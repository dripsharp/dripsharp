#!/usr/bin/env python3
"""Check the small structural contract of the three product release workflows."""

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
PRODUCTS = (
    {
        "name": "Brine",
        "root": "products/brine",
        "reduced_env": "BRINE_RELEASE_REDUCED_TESTS",
        "artifact": "brine-nuget-release",
        "projects": (
            "src/DripSharp.Brine.Parser/DripSharp.Brine.Parser.csproj",
            "src/DripSharp.Brine/DripSharp.Brine.csproj",
        ),
        "smoke_project": (
            "tests/DripSharp.Brine.ReleaseSmoke/"
            "DripSharp.Brine.ReleaseSmoke.csproj"
        ),
        "build_command": 'dotnet build "$project"',
        "build_command_count": 2,
        "pack_command_count": 2,
        "pushes": (
            'dotnet nuget push "${{ steps.packages.outputs.parser }}"',
            'dotnet nuget push "${{ steps.packages.outputs.brine }}"',
        ),
    },
    {
        "name": "PdfCarton",
        "root": "products/pdfcarton",
        "reduced_env": "PDFCARTON_RELEASE_REDUCED_TESTS",
        "artifact": "pdfcarton-nuget-release",
        "projects": (
            "src/DripSharp.PdfCarton.IO/DripSharp.PdfCarton.IO.csproj",
            "src/DripSharp.PdfCarton.Fonts/DripSharp.PdfCarton.Fonts.csproj",
            "src/DripSharp.PdfCarton.Xmp/DripSharp.PdfCarton.Xmp.csproj",
            "src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj",
            "src/DripSharp.PdfCarton.Preflight/"
            "DripSharp.PdfCarton.Preflight.csproj",
        ),
        "smoke_project": (
            "tests/DripSharp.PdfCarton.ReleaseSmoke/"
            "DripSharp.PdfCarton.ReleaseSmoke.csproj"
        ),
        "build_command": 'dotnet build "$project"',
        "build_command_count": 1,
        "pack_command_count": 1,
        "pushes": (
            'dotnet nuget push "${{ steps.packages.outputs.io }}"',
            'dotnet nuget push "${{ steps.packages.outputs.fonts }}"',
            'dotnet nuget push "${{ steps.packages.outputs.xmp }}"',
            'dotnet nuget push "${{ steps.packages.outputs.pdfcarton }}"',
            'dotnet nuget push "${{ steps.packages.outputs.preflight }}"',
        ),
    },
    {
        "name": "SqlTrellis",
        "root": "products/sqltrellis",
        "reduced_env": "SQLTRELLIS_RELEASE_REDUCED_TESTS",
        "artifact": "sqltrellis-nuget-release",
        "projects": (
            "src/DripSharp.SqlTrellis/DripSharp.SqlTrellis.csproj",
        ),
        "smoke_project": (
            "tests/DripSharp.SqlTrellis.ReleaseSmoke/"
            "DripSharp.SqlTrellis.ReleaseSmoke.csproj"
        ),
        "build_command": 'dotnet build "$published_project"',
        "build_command_count": 1,
        "pack_command_count": 1,
        "pushes": (
            'dotnet nuget push "${{ steps.packages.outputs.package }}"',
        ),
    },
)


def require_counts(errors, text, expectations):
    for needle, expected, description in expectations:
        actual = text.count(needle)
        if actual != expected:
            errors.append(f"{description}: expected {expected}, found {actual}")


def require_absent(errors, text, needles, description):
    for needle in needles:
        if needle in text:
            errors.append(f"{description}: found {needle!r}")


def require_order(errors, text, needles, description):
    positions = tuple(text.find(needle) for needle in needles)
    if any(position < 0 for position in positions) or positions != tuple(
        sorted(set(positions))
    ):
        errors.append(f"{description}: required order is {needles!r}")


def check_product(contract):
    product_root = ROOT / contract["root"]
    workflow_path = product_root / ".github/workflows/nuget-release.yml"
    verifier_path = product_root / "eng/verify-release.sh"
    packer_path = product_root / "eng/pack-release.sh"
    missing = tuple(
        str(path.relative_to(ROOT))
        for path in (workflow_path, verifier_path, packer_path)
        if not path.is_file()
    )
    if missing:
        return [f"required product file is missing: {path}" for path in missing]

    workflow = workflow_path.read_text(encoding="utf-8")
    verifier = verifier_path.read_text(encoding="utf-8")
    packer = packer_path.read_text(encoding="utf-8")
    errors = []

    if "\non:\n  workflow_dispatch:\n\npermissions:" not in workflow:
        errors.append("trigger must be only a manual workflow_dispatch")
    master_guard = 'if [[ "$GITHUB_REF" != "refs/heads/master" ]]'
    require_counts(
        errors,
        workflow,
        (
            ("workflow_dispatch:", 1, "manual trigger"),
            (master_guard, 1, "master ref guard"),
            ("uses: actions/checkout@", 1, "product checkout"),
            (f'{contract["reduced_env"]}: "1"', 1, "product reduced mode"),
            ("run: eng/verify-release.sh", 1, "release verifier call"),
            (
                "run: eng/pack-release.sh release-artifacts",
                1,
                "release packer call",
            ),
        ),
    )
    require_order(
        errors,
        workflow,
        (master_guard, "uses: actions/checkout@"),
        "ref guard before product checkout",
    )
    require_absent(
        errors,
        workflow,
        (
            "\n  push:",
            "\n  pull_request:",
            "\n  schedule:",
            "\n  workflow_call:",
            "\n  repository_dispatch:",
            "dripsharp/dripsharp",
            "repository:",
            "submodules:",
            "\n          ref:",
            "git clone",
            "git fetch",
            "git checkout",
            "git submodule",
            ".gitmodules",
            "gitlink",
            "products/",
            "release-manifest",
            "attest",
        ),
        "forbidden trigger or parent coordination",
    )

    require_absent(
        errors,
        verifier,
        ("set +e", "|| true", "|| :", "DRIPSHARP_NUGET_RELEASE_SKIP_TESTS"),
        "release verification bypass",
    )
    reduced_branch = 'if [[ "$reduced_tests" == 1 ]]'
    smoke_command = 'dotnet test "$release_smoke_project"'
    require_counts(
        errors,
        verifier,
        (
            ("set -euo pipefail", 1, "strict verifier"),
            (reduced_branch, 1, "reduced-mode branch"),
            (
                contract["build_command"],
                contract["build_command_count"],
                "published and test build loops",
            ),
            (smoke_command, 1, "mandatory smoke test"),
            (
                f'release_smoke_project="{contract["smoke_project"]}"',
                1,
                "release smoke project",
            ),
        ),
    )
    require_order(
        errors,
        verifier,
        (contract["build_command"], smoke_command, reduced_branch, "  exit 0"),
        "build and smoke before reduced-mode success",
    )
    for project in contract["projects"]:
        require_counts(errors, verifier, ((project, 1, f"verify project {project}"),))
        require_counts(errors, packer, ((project, 1, f"pack project {project}"),))
    require_counts(
        errors,
        packer,
        (
            (
                "dotnet pack ",
                contract["pack_command_count"],
                "pack command cardinality",
            ),
            (
                "--no-build",
                contract["pack_command_count"],
                "pack must reuse the single build",
            ),
        ),
    )
    require_absent(errors, verifier, ("dotnet pack ",), "verifier must not pack")
    require_absent(errors, packer, ("dotnet build ",), "packer must not rebuild")

    publish_marker = "\n  publish:\n"
    if publish_marker not in workflow:
        errors.append("publish job is missing")
        return errors
    publish = workflow[workflow.index(publish_marker) :]
    require_counts(
        errors,
        workflow,
        (
            ("xargs -0 sha256sum > SHA256SUMS", 1, "checksum creation"),
            ("release-artifacts/SHA256SUMS", 1, "checksum upload"),
            ("uses: actions/upload-artifact@", 1, "artifact upload"),
            ("uses: actions/download-artifact@", 1, "artifact download"),
            (f'name: {contract["artifact"]}', 2, "artifact handoff name"),
        ),
    )
    checksum_check = "sha256sum --check --strict SHA256SUMS"
    require_counts(
        errors,
        publish,
        (
            ("needs: prepare", 1, "publish dependency"),
            ("environment: release", 1, "release environment"),
            ("id-token: write", 1, "trusted-publishing permission"),
            ("uses: NuGet/login@", 1, "trusted-publishing login"),
            (checksum_check, 1, "downloaded checksum verification"),
            ("dotnet nuget push ", len(contract["pushes"]), "push cardinality"),
        ),
    )
    require_order(
        errors,
        publish,
        (checksum_check, "uses: NuGet/login@", contract["pushes"][0]),
        "checksum, authentication, and publication",
    )
    for push in contract["pushes"]:
        require_counts(errors, publish, ((push, 1, f"ordered push {push}"),))
    require_order(errors, publish, contract["pushes"], "dependency push order")
    require_absent(
        errors,
        publish,
        (
            "actions/checkout@",
            "dotnet build",
            "dotnet pack",
            "eng/pack-release.sh",
            "actions/upload-artifact@",
            "secrets.",
        ),
        "publish job must only consume tested artifacts",
    )
    return errors


def main():
    failed = False
    for contract in PRODUCTS:
        errors = check_product(contract)
        if errors:
            failed = True
            for error in errors:
                print(f'{contract["name"]}: {error}', file=sys.stderr)
    if failed:
        return 1
    print("Product release workflow structural checks passed for Brine, PdfCarton, and SqlTrellis.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
