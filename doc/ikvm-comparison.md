# IKVM Comparison and Ecosystem Assessment

This is a dated reference snapshot of the comparison between DripSharp and
[IKVM](https://github.com/ikvmnet/ikvm), assessed on 2026-08-06. It records
external ecosystem evidence and technical implications; it does not change any
DripSharp product goal, target scope, exclusion, synchronization policy, or
completion criterion.

## Executive Summary

IKVM and DripSharp address the same broad desire—using Java software from
.NET—at different layers:

* IKVM is a Java compatibility platform for .NET. It consumes compiled Java
  bytecode, translates it to CIL statically or dynamically, and supplies a
  Java SE 8 runtime and class library on .NET.
* DripSharp is a Java-source-to-C# product generator. It consumes a resolved
  Java project through Spoon, applies structural and resolved-symbol mappings,
  and emits reviewable C# projects and independently validated .NET packages.

IKVM is the lower-effort choice for embedding a compatible Java 8 library in
an application. DripSharp is aimed at the more expensive outcome of producing
independently packaged, inspectable .NET implementations without a general
Java compatibility runtime.

The public evidence shows that IKVM is production-proven and maintained, but
does not show a broad or rapidly growing contemporary application ecosystem.
Its visible installed base is specialized, legacy-heavy, and concentrated in
enterprise integrations. The Java 8 ceiling, erased Java generics, Java-shaped
APIs, runtime coupling, and IKVM's own guidance against republishing compiled
FOSS libraries as ordinary NuGet artifacts materially limit its appeal as a
general bridge to the modern Java ecosystem.

## Architecture and Delivery Model

| Dimension | DripSharp | IKVM |
| --- | --- | --- |
| Primary input | Java source plus the resolved Gradle or Maven project | Compiled Java class files and JARs |
| Frontend | Spoon typed semantic AST | JVM bytecode and class-file metadata |
| Translation | Recursive source translation with explicit Java/JDK-to-.NET mappings | Bytecode-to-CIL compilation, statically or on demand |
| Output | Ordinary generated C# projects, assemblies, and target-owned NuGet packages | CLR assemblies that retain Java runtime semantics |
| Runtime | Focused C# compatibility helpers and ordinary .NET dependencies | `IKVM.Runtime`, `IKVM.Java`, and optionally JRE/JDK images |
| Coverage strategy | Target-by-target; unresolved or unsupported behavior fails closed | Broad Java SE 8 compatibility |
| Validation | Clean compilation, deterministic packaging, isolated consumers, adapted tests, and independent JVM differentials | JVM/runtime compatibility and IKVM's own test suites |
| Best fit | Curated, independently maintained .NET ports | Quickly embedding compatible Java software in a controlled .NET application |

DripSharp's source pipeline is described in the [project README](../README.md)
and [transform pipeline](transform-pipeline.md). IKVM explicitly describes
itself as a JVM and bytecode-to-IL converter and explicitly says that it is not
a Java-source-to-C# converter in its
[README](https://github.com/ikvmnet/ikvm#what-is-ikvm).

The distinction affects maintenance and the consumer experience:

* IKVM can consume an existing JAR without maintaining a source port. This is
  a large engineering advantage when compatibility is sufficient.
* DripSharp requires translation rules, mappings, compatibility work, and
  target-specific proof. That is substantially more work per library.
* IKVM consumers retain the IKVM runtime and Java semantic model. DripSharp
  product consumers receive generated C# and normal .NET dependencies.
* DripSharp output is inspectable and can be mapped back to its Java source and
  translation rules. IKVM's principal output is IL rather than authored C#.
* Source generation makes deliberate .NET adaptation possible, but does not
  automatically make every mechanically translated public API idiomatic C#.

## NuGet Redistribution Guidance

IKVM recommends that developers not redistribute third-party FOSS Java
libraries compiled with IKVM through public systems such as NuGet.org unless
they are the original owner or have a compelling reason. This is a project
recommendation rather than a blanket technical or licensing prohibition.

The [official guidance](https://github.com/ikvmnet/ikvm#notice-to-project-owners)
gives two main reasons:

1. Publishing independently converted Java dependency graphs can produce
   duplicate classes, incompatible dependency selections, and conflicts
   between Maven and NuGet resolution.
2. IKVM does not currently guarantee that assemblies statically compiled
   against one `IKVM.Java` or `IKVM.Runtime` version will remain compatible
   with a later IKVM version, even at patch level.

IKVM's preferred model is therefore to keep Maven coordinates in the build and
let the final application resolve and generate the Java assembly graph. The
[`IKVM.Maven.Sdk`](https://www.nuget.org/packages/IKVM.Maven.Sdk) documentation
states that dependent NuGet packages carry a partial POM rather than generated
Java assemblies; the final consumer build resolves the Maven graph and
generates the assemblies.

This guidance is not a dealbreaker when an application owner wants to use a
Java library internally and controls the final build. It is close to a
strategic dealbreaker when the objective is to publish a conventional,
independently versioned NuGet port that hides Maven and IKVM from downstream
consumers and promises ordinary .NET binary compatibility.

## Adoption and Momentum

Public package and repository signals show real use, but need careful
interpretation:

* The [IKVM NuGet package](https://www.nuget.org/packages/IKVM/) reported about
  8.1 million lifetime downloads, about 168,000 downloads for version 8.15.0,
  and 118 dependent NuGet packages at the time of assessment.
* [`IKVM.Maven.Sdk`](https://www.nuget.org/packages/IKVM.Maven.Sdk) reported
  about 567,000 lifetime downloads and about 47,000 downloads for version
  1.11.0. NuGet displayed five dependent packages and two prominent public
  GitHub repositories.
* Visible Maven SDK dependents included MPXJ.Net, Saxon extension helpers,
  Lucene.Net's OpenNLP integration, ORMix, and an Apache Calcite Entity
  Framework integration. Other IKVM-based packages included TikaOnDotNet and
  Stanford CoreNLP wrappers.
* The GitHub repository displayed roughly 1,600 stars and 138 forks.
* [IKVM 8.15.0](https://github.com/ikvmnet/ikvm/releases/tag/8.15.0) was
  released in December 2025 with .NET 10 tooling and an update to JDK 8u472.
  Issues and [project discussions](https://github.com/orgs/ikvmnet/discussions)
  remained active in early 2026.

NuGet downloads are not unique installations or applications. CI restores,
transitive dependencies, IKVM's large component graph, older packages, and
legacy deployments inflate them, while private enterprise applications are
not publicly observable. The figures establish genuine use; they do not
establish a mainstream or fast-growing ecosystem.

The resulting assessment is:

* **Production history:** established.
* **Current maintenance:** active enough that IKVM is not abandonware.
* **Current public adoption:** real but niche, with uncertain breadth outside
  packages and private enterprise systems.
* **Momentum:** sustaining and modernizing .NET support rather than expanding
  into newer Java generations.
* **Maintenance concentration:** the visible release and discussion activity
  appears maintainer-concentrated, increasing platform-bet risk.

## Documented Commercial Application Use

The strongest identifiable examples are enterprise products rather than
widely recognized consumer applications:

| Product | Evidence | Present-day qualification |
| --- | --- | --- |
| Windward Reports / AutoTag | [Windward stated](https://www.windwardstudios.com/content/blog?9b215a87_page=23) that IKVM was used to build the .NET version of its reporting engine. | A genuine commercial use, but closely connected to IKVM's origin and stewardship rather than fully independent adoption. |
| Enterprise Vault Capture / Merge1 | Relatively recent [third-party notices](https://www.enterprisevault.com/content/dam/eds-enterprise-vault/pdfs/license-agreements/arctera-merge1-tpa-702411.pdf) include IKVM; Merge1 remains an active enterprise compliance-ingestion product. | The clearest relatively current commercial inclusion found. A third-party notice proves inclusion, not that every deployment exercises the component. |
| Adobe FrameMaker Publishing Server | The [2019 notices](https://www.adobe.com/content/dam/cc/en/products/eula/third_party/pdfs/Third-PartyNotices_FrameMakerPublishingServer%282019%29_2020.pdf) list IKVM alongside Saxon. | Historical evidence; it does not prove that current FrameMaker releases retain IKVM. |
| Micro Focus Fortify | Fortify 18.x [third-party notices](https://www.microfocus.com/documentation/fortify-static-code-analyzer-and-tools/1820/Fortify_OpenSrc_18.20.pdf) include IKVM. | Historical evidence; it does not prove current use. |
| Progress Corticon Server for .NET 5.x | [Progress documented](https://documentation.progress.com/output/Corticon/5.7.2/html/corticon/installation-option-4-3a-in-process-java-classes-w.html) IKVM as an essential component translating the Java rules engine for .NET. | Corticon 6 replaced the IKVM architecture and [reported at least a threefold performance improvement](https://documentation.progress.com/output/Corticon/7.1/pdf/new_in_corticon.pdf). |
| Chemaxon .NET API | [Chemaxon's documentation](https://docs.chemaxon.com/display/docs/dotnet-api_chemaxon-dotnet-api.md) says its .NET API used IKVM through its September 2021 LTS release. | Historical library/API use rather than a current application example. |

No well-documented, current, mass-market consumer .NET application built around
IKVM was identified. The commercial record proves that IKVM is usable in
serious production systems, while also suggesting a recurring lifecycle:

```text
need Java capability in .NET
  -> adopt IKVM to avoid an immediate port
  -> ship behind a controlled facade
  -> accumulate runtime, performance, or maintenance constraints
  -> replace the bridge when a dedicated implementation becomes economical
```

This is a pattern inferred from the examples, not a claim that every IKVM
deployment follows it.

## Java Version Boundary

Java 8 was released in March 2014. As of this assessment it was approximately
twelve years old, not twenty, but it predates the later long-term-support
generations Java 11, 17, 21, and 25.

IKVM's documented language/runtime boundary remains
[Java SE 8](https://github.com/ikvmnet/ikvm#support). It can use a newer
library only when that library deliberately publishes a Java 8-compatible
artifact and does not depend on later standard-library APIs. This materially
narrows IKVM's reach into the contemporary Java ecosystem. The same support
statement lists .NET Framework 4.7.2 and .NET 6 or later, with Windows, Linux,
and macOS coverage across the architectures available for each artifact.

The DripSharp target baselines use these upstream Java language versions:

| DripSharp product or target | Upstream baseline | Java language version | IKVM Java 8 bytecode boundary |
| --- | --- | ---: | --- |
| Brine | Pkl 0.32.0 | 17 | Outside the boundary |
| PdfCarton | Apache PDFBox 3.0.8 | 8 | Potentially inside the boundary |
| SqlTrellis | JSqlParser 5.3 | 11 | Outside the boundary |
| RawHTTP conformance target | RawHTTP Core 2.5.2 | 8 | Potentially inside the boundary |

The authoritative values are recorded in the
[Pkl](../targets/pkl/baseline.edn),
[PDFBox](../targets/pdfcube/baseline.edn),
[JSqlParser](../targets/sqltrellis/baseline.edn), and
[RawHTTP](../targets/rawhttp/baseline.edn) baseline records. A matching Java
language level establishes only possible class-file compatibility; it does not
prove that dependencies, reflection, dynamic loading, native integration, or
runtime behavior work under IKVM. PDFBox dependency names containing
`jdk18on` mean JDK 1.8 and later, not Java 18.

## Generic Erasure and .NET API Quality

IKVM inherits Java generic erasure because the Java compiler has already
erased generic runtime types before IKVM receives the class file. Java retains
generic declarations in an auxiliary class-file `Signature` attribute for
compiler and reflection use, but the executable JVM descriptor uses the erased
bound or `Object`.

For example:

```java
class Box<T> {
  T get();
}
```

has an executable shape approximately equivalent to:

```java
class Box {
  Object get();
}
```

IKVM can retain the signature metadata needed to emulate Java reflection, but
it does not generally reconstruct this as a reified CLR `Box<T>`. A C# caller
therefore sees an API approximately like:

```csharp
Box box = GetBox();
object value = box.get();
string text = (string)value;
```

rather than a normal CLR-generic API:

```csharp
Box<string> box = GetBox();
string text = box.Get();
```

This matters substantially to .NET consumers. Reified CLR generics normally
provide distinct constructed runtime types, reflection-visible type
arguments, value-type specialization, BCL collection interfaces, nullable
analysis, LINQ composition, and compile-time safety without repeated casts.
An exposed IKVM API may instead contain raw Java collections, Java wrapper
types, `object` results, camel-case members, Java exception conventions, and
Java stream, date, delegate, annotation, and reflection models.

A focused C# facade can hide these differences for a small black-box engine.
Wrapping a broad object model faithfully becomes a significant secondary port.
This helps explain why the strongest IKVM examples are embedded behind
controlled product boundaries rather than presented as arbitrary Java APIs for
ordinary C# consumption.

See Oracle's [type-erasure explanation](https://docs.oracle.com/javase/tutorial/java/generics/erasure.html)
and IKVM's description of its
[bytecode translation model](https://github.com/ikvmnet/ikvm#what-is-ikvm).

## Practical Conclusion

IKVM is a credible tactical dependency when all of the following are true:

* a particular valuable library publishes Java 8-compatible bytecode;
* application owners control and can pin the final build and IKVM version;
* the Java API can remain internal or be hidden behind a narrow .NET facade;
* runtime behavior, dependency resolution, deployment size, and performance
  have been validated for the application;
* the team accepts the maintenance and succession risk.

It is a weak strategic fit when the goal is broad access to modern Java,
ordinary public NuGet redistribution, idiomatic and reified .NET APIs, or an
independent library whose consumers should not inherit Maven and IKVM.

The most accurate overall characterization is:

> IKVM has a real installed base and a proven enterprise history, but its
> visible present-day ecosystem is small, legacy-heavy, and sustained rather
> than growing. It is a pragmatic Java 8 compatibility bridge, not a durable
> general-purpose replacement for native .NET libraries or curated ports.
