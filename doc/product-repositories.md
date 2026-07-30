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
3. Require the destination product submodule to be clean, then synchronize
   only its declared managed paths.
4. Review and commit the generated change in the product repository.
5. Update the parent repository's submodule gitlink only after the product
   repository commit exists.

Synchronization must fail closed when the product submodule contains unrelated
changes, when a managed path escapes the declared product root, or when another
product submodule would be modified. Manual patches to generated C# are not a
durable fix: the corresponding translator, mapping, runtime input, or target
contract must change in `dripsharp/dripsharp`, followed by regeneration.

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
| Managed repository files | `src/`, `LICENSE`, `NOTICE`, `README.md` |

The Brine repository contains both generated projects. Splitting the parser
into a second repository is not part of this model.
