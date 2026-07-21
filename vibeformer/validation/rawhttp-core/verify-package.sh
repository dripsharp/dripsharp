#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
vibeformer_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)

# The complete-generation safety contract fixes the worker and JVM heap sizes.
export VIBEFORMER_WORKERS=22
export JAVA_TOOL_OPTIONS=-Xmx28g

cd "$vibeformer_root"
exec clojure -M -m vibeformer.rawhttp-package
