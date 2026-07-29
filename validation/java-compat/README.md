# Direct JavaCompat Differential

This validation project proves the authored compatibility runtime directly,
without exposing it as a standalone package. `JavaCompatOracle.java` executes
the named JDK contracts on a live JVM, while `Program.cs` compiles the durable
runtime sources into the probe assembly with
`DRIPSHARP_INTERNAL_JAVA_COMPAT` and executes the matching operations.

`TypeProvenance.tsv` is fail-closed provenance for every compiled top-level
compatibility type:

* `compat-type` is the exact non-generic C# type name.
* `jdk-contract` is the JDK type or nearest JDK contract replaced by the
  authored type. Internal adapter types cite the public JDK contract whose
  behavior they support.
* `targets` lists target manifests that request `:java-compat` and therefore
  internalize the source into their owning package. It is not a target-scope
  or completion statement.
* `proof-rows` names the focused observation rows that exercise the type. Every
  type must cite its dedicated `type-contract/<compat-type>` JVM/.NET
  availability row; types used by behavioral probes also cite those rows.

The probe rejects missing or extra compiled types, public type visibility,
unknown proof rows, and runtime source drift. The Clojure runner additionally
requires exact JVM/.NET trace equality and proves that the comparator detects a
deliberate perturbation.
