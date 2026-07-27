param(
    [Parameter(Mandatory = $true)]
    [string] $OperatingSystem,

    [Parameter(Mandatory = $true)]
    [string] $Architecture,

    [Parameter(Mandatory = $true)]
    [string] $FixtureRoot,

    [Parameter(Mandatory = $true)]
    [string] $CanonicalRoot,

    [Parameter(Mandatory = $true)]
    [string] $PackagesRoot,

    [Parameter(Mandatory = $true)]
    [string] $Evidence
)

$ErrorActionPreference = "Stop"

$configuration = "validation/pdfcube-family/NuGet.Config"
$targetRoot = [System.IO.Path]::GetFullPath(
    "target/pdfcube-family-$OperatingSystem-$Architecture")
$evidencePath = [System.IO.Path]::GetFullPath($Evidence)
$dotnetInfo = Join-Path $targetRoot "dotnet-info.txt"
$rows = [System.Collections.Generic.List[string]]::new()

New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
New-Item -ItemType Directory -Force -Path $PackagesRoot | Out-Null
New-Item -ItemType Directory -Force `
    -Path ([System.IO.Path]::GetDirectoryName($evidencePath)) | Out-Null

function Add-Observation {
    param(
        [string] $Subject,
        [string] $Identifier,
        [string] $Value
    )

    $rows.Add("$Subject`t$Identifier`t$Value")
}

function Invoke-DotNet {
    param(
        [string] $Description,
        [string[]] $Arguments
    )

    Write-Host "::group::$Description"
    & dotnet @Arguments
    $exit = $LASTEXITCODE
    Write-Host "::endgroup::"
    if ($exit -ne 0) {
        throw "$Description failed with exit code $exit."
    }
}

function Restore-Build {
    param(
        [string] $Name,
        [string] $Project
    )

    $cache = Join-Path $PackagesRoot $Name
    Invoke-DotNet -Description "Restore $Name from the isolated family feed" `
        -Arguments @(
            "restore", $Project,
            "--configfile", $configuration,
            "--packages", $cache,
            "--no-cache",
            "--force",
            "--force-evaluate"
        )
    Invoke-DotNet -Description "Build $Name without incremental state" `
        -Arguments @(
            "build", $Project,
            "--nologo",
            "--verbosity:minimal",
            "--no-restore",
            "--no-incremental",
            "-warnaserror"
        )
}

function Run-Project {
    param(
        [string] $Description,
        [string] $Project,
        [string[]] $ProgramArguments
    )

    $arguments = @(
        "run",
        "--project", $Project,
        "--no-build",
        "--no-restore",
        "--"
    ) + $ProgramArguments
    Invoke-DotNet -Description $Description -Arguments $arguments
}

function Write-Evidence {
    [System.IO.File]::WriteAllLines(
        $evidencePath,
        $rows,
        [System.Text.UTF8Encoding]::new($false))
}

Add-Observation "schema" "version" "pdfcube-family-host-v1"
Add-Observation "host" "os" $OperatingSystem
Add-Observation "host" "architecture" $Architecture

try {
    & dotnet --info *> $dotnetInfo
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet --info failed with exit code $LASTEXITCODE."
    }

    $familyProject =
        "validation/pdfcube-family/PdfCube.Family.HostSmoke.csproj"
    Restore-Build "family" $familyProject
    Run-Project "Run the complete five-package workflow" $familyProject @()
    foreach ($package in @(
        "PdfCube.IO",
        "PdfCube.FontBox",
        "PdfCube.XmpBox",
        "PdfCube.PdfBox",
        "PdfCube.Preflight"
    )) {
        Add-Observation "package" $package "consumed"
    }
    Add-Observation "capability" "family-workflow" "passed"

    $ioProject = "validation/pdfcube-io/PdfCube.IO.HostSmoke.csproj"
    Restore-Build "io" $ioProject
    Run-Project "Exercise file and memory-mapped IO" $ioProject @(
        (Join-Path $targetRoot "io.tsv"),
        (Join-Path $CanonicalRoot "io.tsv"),
        $OperatingSystem,
        $Architecture
    )
    Add-Observation "capability" "file-memory-mapping" "passed"

    $fontBoxProject =
        "validation/pdfcube-fontbox/PdfCube.FontBox.HostSmoke.csproj"
    Restore-Build "fontbox" $fontBoxProject
    Run-Project "Exercise host font discovery and parsing" $fontBoxProject @(
        (Join-Path $targetRoot "fontbox.tsv"),
        (Join-Path $FixtureRoot "fontbox-resources"),
        (Join-Path $FixtureRoot "fontbox-fonts"),
        (Join-Path $CanonicalRoot "fontbox.tsv"),
        $OperatingSystem,
        $Architecture
    )
    Add-Observation "capability" "font-discovery" "passed"

    $xmpBoxProject =
        "validation/pdfcube-xmpbox/PdfCube.XmpBox.HostSmoke.csproj"
    Restore-Build "xmpbox" $xmpBoxProject
    Run-Project "Exercise XML parsing and serialization" $xmpBoxProject @(
        (Join-Path $targetRoot "xmpbox.tsv"),
        (Join-Path $FixtureRoot "xmpbox-resources"),
        (Join-Path $CanonicalRoot "xmpbox.tsv"),
        $OperatingSystem,
        $Architecture
    )
    Add-Observation "capability" "xml" "passed"

    $securityProject =
        "validation/pdfcube-pdfbox-security/PdfCube.PdfBox.SecurityHostSmoke.csproj"
    Restore-Build "security" $securityProject
    Run-Project "Exercise encryption, CMS, certificates, and signing" `
        $securityProject @(
            (Join-Path $targetRoot "security.tsv"),
            (Join-Path $targetRoot "security-exchange"),
            (Join-Path $FixtureRoot "security"),
            "--write-only"
        )
    Add-Observation "capability" "cryptography" "passed"

    $printingProject =
        "validation/pdfcube-pdfbox-printing/PdfCube.PdfBox.PrintingHostSmoke.csproj"
    Restore-Build "printing" $printingProject
    Run-Project "Exercise CPU rendering and printable/pageable layout" `
        $printingProject @(
            (Join-Path $targetRoot "printing.tsv"),
            (Join-Path $CanonicalRoot "printing.tsv"),
            $OperatingSystem,
            $Architecture
        )
    Add-Observation "capability" "cpu-rendering" "passed"
    Add-Observation "capability" "page-layout" "passed"
    Add-Observation "native-assets" "SkiaSharp" "loaded"
    Add-Observation "rendering" "backend" "cpu"
    Add-Observation "normalization" "policy" "canonical-exact"

    $preflightProject =
        "validation/pdfcube-preflight/PdfCube.Preflight.HostSmoke.csproj"
    Restore-Build "preflight" $preflightProject
    Run-Project "Exercise PDF/A validation host paths" $preflightProject @(
        $OperatingSystem,
        $Architecture
    )
    Add-Observation "capability" "preflight" "passed"

    $harfBuzzFiles = @(
        Get-ChildItem -Path $PackagesRoot -Recurse -File |
            Where-Object { $_.Name -match "HarfBuzz" }
    )
    if ($harfBuzzFiles.Count -ne 0) {
        throw "HarfBuzz assets were restored even though the 3.0.8 contract does not select them."
    }
    Add-Observation "native-assets" "HarfBuzzSharp" "not-selected"

    Add-Observation "capability" "clean-restore" "passed"
    Add-Observation "capability" "clean-build" "passed"
    Add-Observation "result" "status" "passed"
    Write-Evidence
}
catch {
    $message = [System.Text.RegularExpressions.Regex]::Replace(
        $_.Exception.Message,
        "[\t\r\n]+",
        " ")
    Add-Observation "result" "status" "failed"
    Add-Observation "failure" "message" $message
    Write-Evidence
    throw
}
