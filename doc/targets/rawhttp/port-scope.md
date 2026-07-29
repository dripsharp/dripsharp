# RawHTTP Conformance Port Scope

The selected source is the pinned `rawhttp-core` Gradle project recorded in
`targets/rawhttp/baseline.edn`. The operational target includes every
production source and resource discovered from that project and preserves the
existing public-surface and package-equivalence contracts.

`targets/rawhttp/target.edn` owns the permanent conformance role and its
required proof ladder. Removing the profile or behavior validation from that
ladder is a contract failure rather than a CI optimization.

Missing or difficult production behavior remains pending work. This scope
declares no product exclusions.
