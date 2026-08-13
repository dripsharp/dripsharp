#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIRECTORY/.." && pwd -P)"
RELEASE_MANIFEST="$REPOSITORY_ROOT/target/nuget-release/pdfcube/release-manifest.edn"
NUGET_SOURCE="https://api.nuget.org/v3/index.json"
RETRY_EXISTING=false

usage() {
  printf 'Usage: %s [--retry]\n' "${0##*/}"
  printf '  --retry  Publish the existing proved manifest without rebuilding it.\n'
}

cleanup() {
  unset NUGET_API_KEY NUGET_SYMBOL_API_KEY
}

trap cleanup EXIT

case "${1:-}" in
  '')
    ;;
  --retry)
    RETRY_EXISTING=true
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

if [[ ! -t 0 ]]; then
  printf 'This script requires an interactive terminal for the hidden API-key prompt.\n' >&2
  exit 1
fi

unset NUGET_API_KEY NUGET_SYMBOL_API_KEY
cd "$REPOSITORY_ROOT"

if [[ "$RETRY_EXISTING" == true ]]; then
  if [[ ! -f "$RELEASE_MANIFEST" ]]; then
    printf 'No existing proved PdfCarton manifest was found. Run without --retry first.\n' >&2
    exit 1
  fi
  printf 'Reusing the existing proved DripSharp.PdfCarton package...\n'
else
  printf 'Preparing the single DripSharp.PdfCarton alpha package...\n'
  DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run \
    nuget-release-prepare pdfcube
fi

printf 'Checking the package ID and version on nuget.org...\n'
clojure -M:run nuget-release-preflight "$RELEASE_MANIFEST" \
  --check-nuget-org

if [[ "$RETRY_EXISTING" == false ]]; then
  printf 'Validating the publication plan...\n'
  clojure -M:run nuget-release-publish "$RELEASE_MANIFEST"
fi

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
