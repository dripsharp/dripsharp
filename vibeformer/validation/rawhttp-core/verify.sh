#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_root=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
contract="$script_dir/ProjectContract.tsv"

fail() {
  printf 'RawHTTP contract failure: %s\n' "$*" >&2
  exit 1
}

contract_value() {
  key=$1
  value=$(awk -F '\t' -v wanted="$key" '$1 == wanted { count++; value=$2 } END { if (count == 1) print value; else exit 1 }' "$contract") \
    || fail "ProjectContract.tsv must contain exactly one $key record"
  printf '%s\n' "$value"
}

count_kind() {
  kind=$1
  file=$2
  awk -F '\t' -v wanted="$kind" 'NR > 1 && $1 == wanted { count++ } END { print count + 0 }' "$file"
}

assert_equal() {
  label=$1
  expected=$2
  actual=$3
  [ "$expected" = "$actual" ] || fail "$label: expected $expected, found $actual"
}

[ "$(sed -n '1p' "$contract")" = "VIBEFORMER_JAVA_LIBRARY_CONTRACT_V1" ] \
  || fail "unsupported project contract header"

source_path=$(contract_value source-path)
source_root="$workspace_root/$source_path"
revision=$(contract_value source-revision)
release=$(contract_value source-release)
license_file="$workspace_root/$(contract_value license-file)"
profile_file="$workspace_root/$(contract_value profile-file)"
destination_file="$workspace_root/$(contract_value destination-file)"
inventory_file="$workspace_root/$(contract_value inventory-file)"
observations_file="$workspace_root/$(contract_value observations-file)"
surface_file="$workspace_root/$(contract_value public-surface-file)"
body_review_file="$workspace_root/$(contract_value body-review-file)"
oracle_source="$workspace_root/$(contract_value oracle-source)"
inventory_script="$workspace_root/$(contract_value gradle-inventory-script)"
gradle_project=$(contract_value gradle-project)

[ -e "$source_root/.git" ] || fail "$source_path is not an initialized pinned checkout"
assert_equal "pinned source revision" "$revision" "$(git -C "$source_root" rev-parse HEAD)"
assert_equal "release tag revision" "$revision" \
  "$(git -C "$source_root" rev-parse "refs/tags/$release^{commit}")"
assert_equal "source repository" "$(contract_value source-repository)" \
  "$(git -C "$source_root" remote get-url origin)"
[ -z "$(git -C "$source_root" status --porcelain)" ] || fail "$source_path contains local changes"

if command -v shasum >/dev/null 2>&1; then
  license_sha=$(shasum -a 256 "$license_file" | awk '{print $1}')
elif command -v sha256sum >/dev/null 2>&1; then
  license_sha=$(sha256sum "$license_file" | awk '{print $1}')
else
  fail "neither shasum nor sha256sum is available"
fi
assert_equal "license SHA-256" "$(contract_value license-sha256)" "$license_sha"

grep -Fq ":profile \"$(contract_value destination-profile)\"" "$profile_file" \
  || fail "profile identity does not match the project contract"
grep -Fq ":revision \"$revision\"" "$profile_file" \
  || fail "profile revision does not match the pinned checkout"
grep -Fq ":assembly-name \"$(contract_value destination-assembly)\"" "$destination_file" \
  || fail "destination assembly does not match the project contract"
grep -Fq ":root-namespace \"$(contract_value destination-root-namespace)\"" "$destination_file" \
  || fail "destination namespace does not match the project contract"
grep -Fq ':identity-guard {:forbidden-fragments ["pkl"]}' "$profile_file" \
  || fail "profile does not explicitly reject Pkl identity leaks"
if {
  sed '/^[[:space:]]*:identity-guard {:forbidden-fragments \["pkl"\]}}$/d' "$profile_file"
  cat "$destination_file"
} | grep -Eiq '(^|[^[:alpha:]])pkl([^[:alpha:]]|$)'; then
  fail "the independent profile or destination imports a Pkl identity"
fi

java_home=${VIBEFORMER_JAVA_HOME:-}
if [ -z "$java_home" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_home=$JAVA_HOME
fi
if [ -z "$java_home" ] && [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
  java_home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -z "$java_home" ] && [ -x /usr/local/opt/openjdk@17/bin/java ]; then
  java_home=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -z "$java_home" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64; do
    if [ -x "$candidate/bin/java" ]; then java_home=$candidate; break; fi
  done
fi
[ -x "$java_home/bin/java" ] && [ -x "$java_home/bin/javac" ] \
  || fail "set VIBEFORMER_JAVA_HOME to a JDK 17 installation"
java_major=$("$java_home/bin/java" -version 2>&1 \
  | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' \
  | sed -n '1p')
assert_equal "oracle Java runtime major" "$(contract_value oracle-runtime-major)" "$java_major"

work=$(mktemp -d /tmp/vibeformer-rawhttp-contract.XXXXXX)
cleanup() {
  case "$work" in
    /tmp/vibeformer-rawhttp-contract.*) rm -rf -- "$work" ;;
    *) fail "refusing to remove unexpected temporary path $work" ;;
  esac
}
trap cleanup EXIT HUP INT TERM

actual_inventory="$work/GradleInventory.tsv"
(
  cd "$source_root"
  JAVA_HOME="$java_home" ./gradlew --quiet --console=plain --no-daemon \
    -I "$inventory_script" \
    "${gradle_project}:vibeformerInventoryMain" \
    "-Pvibeformer.project=$gradle_project" \
    "-Pvibeformer.output=$actual_inventory"
)

cmp -s "$inventory_file" "$actual_inventory" || {
  diff -u "$inventory_file" "$actual_inventory" | sed -n '1,160p' >&2 || true
  fail "live Gradle production inventory differs from the pinned inventory"
}

assert_equal "production source count" "$(contract_value production-source-count)" \
  "$(count_kind production-source "$actual_inventory")"
assert_equal "generated source count" "$(contract_value generated-source-count)" \
  "$(count_kind generated-source "$actual_inventory")"
assert_equal "resource count" "$(contract_value resource-count)" \
  "$(count_kind resource "$actual_inventory")"
assert_equal "project dependency count" "$(contract_value project-dependency-count)" \
  "$(count_kind project-dependency "$actual_inventory")"
assert_equal "external dependency count" "$(contract_value external-dependency-count)" \
  "$(count_kind external-dependency "$actual_inventory")"
assert_equal "external artifact count" "$(contract_value external-artifact-count)" \
  "$(count_kind external-artifact "$actual_inventory")"
external_dependency=$(contract_value external-dependency)
external_dependency_scope=$(contract_value external-dependency-scope)
awk -F '\t' -v wanted="$external_dependency" -v scope="$external_dependency_scope" \
  '$1 == "external-dependency" && $2 == scope && $3 == wanted { found=1 } END { exit !found }' \
  "$actual_inventory" || fail "pinned external dependency is absent from live Gradle discovery"
external_artifact=$(contract_value external-dependency-artifact)
external_artifact_sha=$(contract_value external-dependency-artifact-sha256)
awk -F '\t' -v wanted="$external_dependency" -v scope="$external_dependency_scope" \
  -v artifact="$external_artifact" -v sha="$external_artifact_sha" \
  '$1 == "external-artifact" && $2 == scope && $3 == wanted && $4 == artifact && $5 == sha { found=1 }
   END { exit !found }' "$actual_inventory" \
  || fail "pinned external dependency artifact or SHA-256 differs from live Gradle discovery"
resource_record=$(contract_value resource)
awk -F '\t' -v wanted="$resource_record" '$1 == "resource" && $2 == wanted { found=1 } END { exit !found }' \
  "$actual_inventory" || fail "pinned production resource is absent from live Gradle discovery"

[ "$(sed -n '1p' "$body_review_file")" = "VIBEFORMER_RAWHTTP_BODY_REVIEW_V1" ] \
  || fail "unsupported RawHTTP body-review contract header"
body_review_count=$(awk 'NR > 2 && NF { count++ } END { print count + 0 }' "$body_review_file")
assert_equal "authoritative Java body review count" "4" "$body_review_count"

oracle_classes="$work/oracle-classes"
mkdir -p "$oracle_classes"
main_classes="$source_root/rawhttp-core/build/classes/java/main"
main_resources="$source_root/rawhttp-core/build/resources/main"
"$java_home/bin/javac" --release "$(contract_value java-release)" \
  -cp "$main_classes" -d "$oracle_classes" "$oracle_source"
oracle_cp="$oracle_classes:$main_classes:$main_resources"

first_observations="$work/observations-first.tsv"
second_observations="$work/observations-second.tsv"
first_surface="$work/surface-first.tsv"
second_surface="$work/surface-second.tsv"
"$java_home/bin/java" -cp "$oracle_cp" RawHttpContractOracle \
  "$main_classes" "$first_observations" "$first_surface"
"$java_home/bin/java" -cp "$oracle_cp" RawHttpContractOracle \
  "$main_classes" "$second_observations" "$second_surface"

cmp -s "$first_observations" "$second_observations" \
  || fail "the two independently executed Java observation streams differ"
cmp -s "$first_surface" "$second_surface" \
  || fail "the two independently extracted Java public surfaces differ"
cmp -s "$observations_file" "$first_observations" \
  || fail "live Java observations differ from the pinned observations"
cmp -s "$surface_file" "$first_surface" \
  || fail "live Java public surface differs from the pinned surface"

observation_count=$(awk 'END { print NR - 1 }' "$first_observations")
success_count=$(awk -F '\t' 'NR > 1 && $2 == "SUCCESS" { count++ } END { print count + 0 }' "$first_observations")
failure_count=$(awk -F '\t' 'NR > 1 && $2 == "FAILURE" { count++ } END { print count + 0 }' "$first_observations")
surface_count=$(awk 'END { print NR - 1 }' "$first_surface")
assert_equal "observation count" "$(contract_value observation-count)" "$observation_count"
assert_equal "successful observation count" "$(contract_value successful-observation-count)" "$success_count"
assert_equal "failure observation count" "$(contract_value failure-observation-count)" "$failure_count"
assert_equal "public surface row count" "$(contract_value public-surface-row-count)" "$surface_count"

perturbed="$work/observations-perturbed.tsv"
awk 'NR == 2 { sub(/\tSUCCESS\t/, "\tFAILURE\t") } { print }' \
  "$first_observations" > "$perturbed"
if cmp -s "$first_observations" "$perturbed"; then
  fail "the deliberate Java observation perturbation was not detected"
fi

printf 'RawHTTP contract verified: %s production sources, %s generated sources, %s resource, %s external dependency, %s public-surface rows, and %s observations (%s success, %s deterministic failure); repeated runs matched and deliberate perturbation failed comparison.\n' \
  "$(contract_value production-source-count)" \
  "$(contract_value generated-source-count)" \
  "$(contract_value resource-count)" \
  "$(contract_value external-dependency-count)" \
  "$surface_count" "$observation_count" "$success_count" "$failure_count"
