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
3. Restore, build, and run the focused generated consumer test suite against
   that complete staged family.
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

Each generated product repository contains one focused public-API test project
under `tests/`. Its project references resolve only to that checkout's `src/`
tree, and its `tests/README.md` records clean restore, build, and test commands.
Target-owned checksums, fixture attribution, and generated `SHA256SUMS` files
make the suite deterministic. Comprehensive translator, differential,
conformance, packaging, and large-corpus gates remain in
`dripsharp/dripsharp` and run before this focused suite and synchronization.

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
* The managed and native binary output matches the target-owned inventory
  exactly and contains no framework assemblies or dependency-name collisions.

Without the optional platform list, assembly produces one deterministic,
versioned target-framework/platform ZIP for every declared platform variant.
An explicit selection must be a nonempty comma-separated list of unique, exact
platform IDs from the target-owned inventory. Unknown, empty, malformed, or
duplicate selections fail before the proof ladder. The preparation record,
dry-run GitHub release notes, and asset metadata contain only the selected
platforms and ZIPs.

ZIP verification rejects package files, symbols, XML documentation, source
archives, unsafe paths, and unrelated files. A preparation record contains
dry-run GitHub release metadata with an exact target commit, `prerelease` set
to true, and `latest` set to false.

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
| Managed repository files | `src/`, `tests/`, `LICENSE`, `NOTICE`, `README.md` |

The PdfCarton repository contains all five generated projects as one versioned
product family. Repository creation remains an explicit owner action and is not
implied by this approved mapping.
