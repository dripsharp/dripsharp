# Product Repository Contract

## Repository Roles

[`dripsharp/dripsharp`](https://github.com/dripsharp/dripsharp) is the
authoritative source repository. It owns the translator, target configuration,
authored runtime support, generation rules, and conformance evidence.

Each publishable product family has one DripSharp-owned GitHub repository and
one corresponding Git submodule in the source repository:

```text
products/<product-id> -> https://github.com/dripsharp/<product-id>
```

The product repository is a generated publication repository. It provides a
focused history and checkout for .NET consumers, but it is not an independent
source of authored generated-code changes. One product-family repository may
contain multiple assemblies and projects when they form one product.

DripSharp and its DripSharp-owned product repositories use `master` as their
default branch.

Repository creation is an explicit owner action. Generation and publication
automation must not create GitHub repositories implicitly. GitHub repository
identity also does not decide NuGet publisher or package-release metadata;
those are separate release decisions.

## Local Directory Types

Publication-capable targets distinguish two output types:

* `:generated-repository` produces a product family that can be synchronized
  to a product repository.
* `:conformance-only` produces evidence or fixtures that are never published
  as a product repository.

Disposable generation and durable product checkouts have separate roots:

```text
target/generated/<product-id>/  # disposable generation and proof staging
products/<product-id>/          # Git submodule for the product repository
```

Generation, clean-generation checks, and cleanup operate under
`target/generated/`. They must not generate directly into, or remove content
from, `products/`.

## Publication Flow

Publication follows this order:

1. Generate the complete product family into its disposable staging directory.
2. Run the required compilation, packaging, consumption, and conformance
   checks against that staged output.
3. Restore, build, and run every declared generated test project, including
   focused consumers and any target-required complete adapted upstream suite,
   against that complete staged family.
4. Require the destination product submodule to be clean, then synchronize
   only its declared managed paths.
5. Review and commit the generated change in the product repository.
6. Update the parent repository's submodule gitlink only after the product
   repository commit exists.

`product-sync <target>` runs the target's complete required proof before the
managed-path copy. `product-prepare <target> <branch> <commit-message>` adds a
local product branch and commit, pull-request metadata, and a staged parent
gitlink update. Neither command creates a repository, pushes a branch, or
opens a pull request; those external actions remain separately authorized
owner operations.

Synchronization must fail closed when the product submodule contains unrelated
changes, when a managed path escapes the declared product root, or when another
product submodule would be modified. Manual patches to generated C# are not a
durable fix: the corresponding translator, mapping, runtime input, or target
contract must change in `dripsharp/dripsharp`, followed by regeneration.

Each generated product repository contains one or more declared test projects
under `tests/`. Their project references resolve only within that checkout,
and `tests/README.md` records clean restore, build, and test commands for every
project. Target strategies distinguish focused consumers from adapted upstream
suites and declare shipped or validation-only policy explicitly.
Target-owned checksums, fixture attribution, and generated `SHA256SUMS` files
make the suite deterministic. Brine additionally publishes independently named
xUnit rows generated from the complete pinned LanguageSnippet, Pkl.Core, and
pkl-parser contracts for its selected production profiles, with all required
upstream fixtures/resources and authored .NET adapters needed to run without a
DripSharp checkout, JVM, Kotlin toolchain, Gradle installation, or external
fixture tree. The projects are non-packable. Its generated test-provenance and
authorship ledgers keep normalized upstream material, vendored fixtures,
authored adapters, and deterministic wrapper glue distinct. Comprehensive
translator, differential, conformance, and packaging gates remain in
`dripsharp/dripsharp` and run before the generated suite and synchronization.
Translator-only `source-map.edn` files likewise remain in the proved staging
tree and are excluded from generated product repositories. They are not needed
to restore, build, or run the generated test suites.

## GitHub Alpha-Release Assets

Early GitHub distribution is a DLL-focused prerelease workflow, separate from
NuGet package creation and publication. Each generated product target owns a
typed `targets/<target>/release.edn` inventory. The inventory names every
DripSharp product assembly, every required non-framework managed dependency,
and each supported platform's required native runtime assets.

```text
alpha-release-prepare <target> <authorized-alpha-tag> <product-commit> [platform-id,...]
```

This command runs the target's complete proof and then requires all of the
following before assembling assets:

* The product submodule is clean and its `HEAD` and parent gitlink both equal
  the supplied full product commit.
* The product repository's managed source state exactly matches the freshly
  proved generated staging state.
* Every build uses `Release` configuration.
* Each build restores dependencies into an isolated preparation-owned package
  root.
* The managed and native binary output matches the target-owned inventory
  exactly and contains no framework assemblies or dependency-name collisions.
* The entry assembly dependency file selects the declared runtime target and
  binds every managed DLL and native asset to its exact package ID, version,
  and runtime or package path. Each packaged dependency's bytes must also match
  that exact regular, contained, non-symbolic restored package asset.

Without the optional platform list, assembly produces one deterministic,
versioned target-framework/platform ZIP for every declared platform variant.
An explicit selection must be a nonempty comma-separated list of unique, exact
platform IDs from the target-owned inventory. Unknown, empty, malformed, or
duplicate selections fail before the proof ladder. The preparation record,
dry-run GitHub release notes, and asset metadata contain only the selected
platforms and ZIPs.

Downloaded ZIP verification first requires the exact prepared archive SHA-256,
then rejects package files, symbols, XML documentation, source archives, unsafe
paths, unrelated files, and entry-byte mismatches. A preparation record
contains that archive checksum plus dry-run GitHub release metadata with an
exact target commit, `prerelease` set to true, and `latest` set to false.

Preparation does not create a tag or release and does not upload an asset or
push a ref. Those external mutations require explicit owner authorization in
the current conversation.

## Brine Mapping

The Pkl product family uses this mapping:

| Property | Approved value |
| --- | --- |
| Product name | Brine — Pkl for .NET |
| GitHub repository | [`dripsharp/brine`](https://github.com/dripsharp/brine) |
| Parent submodule | `products/brine` |
| Default branch | `master` |
| Product project | `src/DripSharp.Brine` |
| Parser project | `src/DripSharp.Brine.Parser` |
| Managed repository files | `src/`, `tests/`, `LICENSE`, `NOTICE`, `README.md` |

The Brine repository contains both generated projects. Splitting the parser
into a second repository is not part of this model.

## PdfCarton Mapping

The Apache PDFBox-derived product family uses this mapping:

| Property | Approved value |
| --- | --- |
| Product name | PdfCarton |
| GitHub repository | `dripsharp/pdfcarton` |
| Parent submodule | `products/pdfcarton` |
| Default branch | `master` |
| Core project | `src/DripSharp.PdfCarton` |
| I/O project | `src/DripSharp.PdfCarton.IO` |
| Fonts project | `src/DripSharp.PdfCarton.Fonts` |
| XMP project | `src/DripSharp.PdfCarton.Xmp` |
| Preflight project | `src/DripSharp.PdfCarton.Preflight` |
| Complete adapted test project | `tests/DripSharp.PdfCarton.Tests` |
| Managed repository files | `src/`, `tests/`, `LICENSE`, `NOTICE`, `README.md` |

The PdfCarton repository contains all five generated projects as one versioned
product family. It also contains the complete adapted ordinary PDFBox suite and
fixtures as one or more repository-local runnable, non-packable .NET test
projects. The five focused package-consumer tests remain a distinct strategy and
are not counted as that upstream coverage. The shipped suite restores, builds
with warnings as errors, and runs through `dotnet test` using only the generated
checkout; it requires no DripSharp checkout, Java runtime, Maven installation,
or external fixture tree. Repository creation remains an explicit owner action
and is not implied by this approved mapping.

## SqlTrellis Mapping

The JSqlParser-derived product family uses this mapping:

| Property | Approved value |
| --- | --- |
| Product name | SqlTrellis — JSqlParser for .NET |
| GitHub repository | `dripsharp/sqltrellis` |
| Parent submodule | `products/sqltrellis` |
| Default branch | `master` |
| Product project | `src/DripSharp.SqlTrellis` |
| Test project | `tests/DripSharp.SqlTrellis.Tests` |
| Managed repository files | `src/`, `tests/`, `LICENSE`, `NOTICE`, `README.md` |

The SqlTrellis repository contains the generated production project and the
complete adapted upstream test suite and fixtures. The test project is included
in the generated solution and must run independently through `dotnet test`, but
it is not packed or published to NuGet. Repository creation remains an explicit
owner action and is not implied by this approved mapping.
