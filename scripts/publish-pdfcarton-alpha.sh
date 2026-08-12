#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIRECTORY/.." && pwd -P)"
RELEASE_MANIFEST="$REPOSITORY_ROOT/target/nuget-release/pdfcube/release-manifest.edn"
NUGET_SOURCE="https://api.nuget.org/v3/index.json"

cleanup() {
  unset NUGET_API_KEY NUGET_SYMBOL_API_KEY
}

trap cleanup EXIT

if [[ ! -t 0 ]]; then
  printf 'This script requires an interactive terminal for the hidden API-key prompt.\n' >&2
  exit 1
fi

unset NUGET_API_KEY NUGET_SYMBOL_API_KEY
cd "$REPOSITORY_ROOT"

printf 'Preparing the single DripSharp.PdfCarton alpha package...\n'
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run \
  nuget-release-prepare pdfcube

printf 'Checking the package ID and version on nuget.org...\n'
clojure -M:run nuget-release-preflight "$RELEASE_MANIFEST" \
  --check-nuget-org

printf 'Validating the publication plan...\n'
clojure -M:run nuget-release-publish "$RELEASE_MANIFEST"

printf 'NuGet API key: ' >&2
IFS= read -r -s NUGET_API_KEY
printf '\n' >&2

if [[ -z "$NUGET_API_KEY" ]]; then
  printf 'The NuGet API key cannot be empty.\n' >&2
  exit 1
fi

printf 'Publishing DripSharp.PdfCarton 3.0.8-alpha.1 to nuget.org...\n'
NUGET_API_KEY="$NUGET_API_KEY" clojure -M:run \
  nuget-release-publish "$RELEASE_MANIFEST" \
  --live \
  --authorize-publish \
  --source "$NUGET_SOURCE"

printf 'DripSharp.PdfCarton 3.0.8-alpha.1 publication completed.\n'
