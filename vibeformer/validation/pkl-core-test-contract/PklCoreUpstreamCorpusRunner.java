package vibeformer.contract;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs each pinned non-language Pkl.Core JUnit identifier in a separate bounded JVM.
 *
 * <p>The parent never infers success from a child exit code. A child must emit a complete result
 * record for its exact manifest row. Missing output, a non-zero exit, or a timeout becomes explicit
 * crash/timeout evidence and does not prevent later rows from running.
 */
public final class PklCoreUpstreamCorpusRunner {
  private static final String RESULT_MAGIC = "VIBEFORMER_PKL_CORE_CORPUS_RESULTS_V1";
  private static final String CHILD_MAGIC = "VIBEFORMER_PKL_CORE_CORPUS_CHILD_V1";
  private static final String ORIGIN = "upstream-jvm";
  private static final Base64.Encoder ENCODER = Base64.getEncoder();
  private static final Base64.Decoder DECODER = Base64.getDecoder();

  private static final List<String> RESULT_COLUMNS =
      List.of(
          "case-id",
          "origin",
          "upstream-revision",
          "junit-unique-id",
          "source-path",
          "source-sha256",
          "source-line",
          "behavior-family",
          "product-classification",
          "execution-owner",
          "status",
          "observation-base64",
          "diagnostic-base64");

  private PklCoreUpstreamCorpusRunner() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--child")) {
      runChild(args);
      return;
    }
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Usage: PklCoreUpstreamCorpusRunner <manifest> <output> <timeout-ms> <workers>");
    }
    runParent(
        Path.of(args[0]).toAbsolutePath().normalize(),
        Path.of(args[1]).toAbsolutePath().normalize(),
        parsePositiveInt(args[2], "timeout-ms"),
        parsePositiveInt(args[3], "workers"));
  }

  private static int parsePositiveInt(String value, String label) {
    int parsed = Integer.parseInt(value);
    if (parsed <= 0) throw new IllegalArgumentException(label + " must be positive");
    return parsed;
  }

  private static void runParent(Path manifestPath, Path output, int timeoutMs, int workers)
      throws Exception {
    Manifest manifest = Manifest.read(manifestPath);
    Path scratch = Files.createTempDirectory("vibeformer-pkl-core-upstream-");
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Result>> futures = new ArrayList<>(
          Collections.nCopies(manifest.cases.size(), null));
      for (int index = 0; index < manifest.cases.size(); index++) {
        int rowIndex = index;
        if (!requiresExclusiveFixture(manifest.cases.get(rowIndex))) {
          futures.set(
              rowIndex,
              executor.submit(
                  (Callable<Result>)
                      () -> runBoundedChild(
                          manifestPath, manifest, rowIndex, scratch, timeoutMs)));
        }
      }
      Result[] results = new Result[manifest.cases.size()];
      for (int index = 0; index < futures.size(); index++) {
        Future<Result> future = futures.get(index);
        if (future != null) results[index] = future.get();
      }
      for (int index = 0; index < manifest.cases.size(); index++) {
        if (requiresExclusiveFixture(manifest.cases.get(index))) {
          results[index] = runBoundedChild(manifestPath, manifest, index, scratch, timeoutMs);
        }
      }
      writeResults(output, List.of(results));
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
      deleteTree(scratch);
    }
  }

  private static Result runBoundedChild(
      Path manifestPath, Manifest manifest, int rowIndex, Path scratch, int timeoutMs) {
    CaseRow row = manifest.cases.get(rowIndex);
    Path childResult = scratch.resolve(String.format("%04d.result", rowIndex));
    Path childLog = scratch.resolve(String.format("%04d.log", rowIndex));
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-Xmx1g");
    command.add("-Xss4m");
    command.add("--add-modules=jdk.unsupported");
    forwardedProperties().forEach((key, value) -> command.add("-D" + key + "=" + value));
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(PklCoreUpstreamCorpusRunner.class.getName());
    command.add("--child");
    command.add(manifestPath.toString());
    command.add(Integer.toString(rowIndex));
    command.add(childResult.toString());
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(Path.of("").toAbsolutePath().normalize().toFile());
      builder.redirectErrorStream(true);
      builder.redirectOutput(childLog.toFile());
      process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
      while (process.isAlive()
          && !Files.isRegularFile(childResult)
          && System.nanoTime() < deadline) {
        process.waitFor(100, TimeUnit.MILLISECONDS);
      }
      if (Files.isRegularFile(childResult)) {
        Result result = readChildResult(childResult, row, manifest.revision);
        if (process.isAlive()) {
          destroyProcessTree(process);
          process.waitFor();
        }
        return result;
      }
      if (process.isAlive()) {
        destroyProcessTree(process);
        process.waitFor();
        return Result.from(
            row,
            manifest.revision,
            "TIMEOUT",
            "",
            "Bounded JVM child timed out after " + timeoutMs + " ms");
      }
      if (process.exitValue() != 0) {
        return Result.from(
            row,
            manifest.revision,
            "CRASH",
            "",
            normalizeDiagnostic(
                "Bounded JVM child exited "
                    + process.exitValue()
                    + ":\n"
                    + readIfPresent(childLog)));
      }
      return readChildResult(childResult, row, manifest.revision);
    } catch (Throwable error) {
      if (process != null && process.isAlive()) destroyProcessTree(process);
      return Result.from(
          row,
          manifest.revision,
          "CRASH",
          "",
          normalizeDiagnostic(error.getClass().getName() + ": " + nullToEmpty(error.getMessage())));
    }
  }

  private static Map<String, String> forwardedProperties() {
    Map<String, String> result = new LinkedHashMap<>();
    Properties properties = System.getProperties();
    List<String> names = new ArrayList<>();
    for (Object key : properties.keySet()) names.add(key.toString());
    Collections.sort(names);
    for (String name : names) {
      if (name.startsWith("org.pkl.") || name.startsWith("polyglotimpl.")) {
        result.put(name, properties.getProperty(name));
      }
    }
    return result;
  }

  private static boolean requiresExclusiveFixture(CaseRow row) {
    return row.sourcePath.endsWith("/packages/PackageResolversTest.kt")
        || row.sourceMethod.equals("evaluation timeout");
  }

  private static String javaExecutable() {
    String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private static void destroyProcessTree(Process process) {
    List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
    Collections.reverse(descendants);
    for (ProcessHandle descendant : descendants) {
      try {
        descendant.destroyForcibly();
      } catch (RuntimeException ignored) {
        // A descendant can exit between enumeration and cleanup.
      }
    }
    try {
      process.destroyForcibly();
    } catch (RuntimeException ignored) {
      // The process can exit between the liveness check and cleanup.
    }
  }

  private static void runChild(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Usage: PklCoreUpstreamCorpusRunner --child <manifest> <row-index> <result>");
    }
    Manifest manifest = Manifest.read(Path.of(args[1]).toAbsolutePath().normalize());
    int rowIndex = Integer.parseInt(args[2]);
    if (rowIndex < 0 || rowIndex >= manifest.cases.size()) {
      throw new IllegalArgumentException("row-index is outside the manifest");
    }
    CaseRow row = manifest.cases.get(rowIndex);
    Path resultFile = Path.of(args[3]).toAbsolutePath().normalize();
    // Gradle's ordinary suite initializes this exception class before the stack-overflow
    // assertions run. A row-isolated JVM must make that lifecycle prerequisite explicit or class
    // initialization itself occurs after the stack has been exhausted.
    Class.forName("org.pkl.core.runtime.VmStackOverflowException", true,
        Thread.currentThread().getContextClassLoader());
    ChildListener listener = new ChildListener(row.uniqueId);
    var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(selectUniqueId(row.uniqueId))
            .build();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);
    if (!listener.observed && row.caseKind.equals("test")) {
      listener = new ChildListener(row.uniqueId);
      request =
          LauncherDiscoveryRequestBuilder.request()
              .selectors(selectMethod(row.sourceClass, row.sourceMethod))
              .build();
      launcher = LauncherFactory.create();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(request);
    }
    ChildResult child = listener.result(row);
    Path temporary = resultFile.resolveSibling(resultFile.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        CHILD_MAGIC
            + "\n"
            + child.status
            + "\n"
            + encode(child.observation)
            + "\n"
            + encode(child.diagnostic)
            + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(
          temporary,
          resultFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, resultFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Result readChildResult(Path childResult, CaseRow row, String revision)
      throws IOException {
    List<String> lines = Files.readAllLines(childResult, StandardCharsets.UTF_8);
    if (lines.size() != 4 || !lines.get(0).equals(CHILD_MAGIC)) {
      return Result.from(
          row, revision, "CRASH", "", "Bounded JVM child emitted a malformed result record");
    }
    try {
      return Result.from(
          row,
          revision,
          lines.get(1),
          decode(lines.get(2)),
          normalizeDiagnostic(decode(lines.get(3))));
    } catch (IllegalArgumentException error) {
      return Result.from(
          row, revision, "CRASH", "", "Bounded JVM child emitted invalid base64 evidence");
    }
  }

  private static String readIfPresent(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    } catch (IOException ignored) {
      return "<unreadable-child-log>";
    }
  }

  private static String normalizeDiagnostic(String value) {
    if (value == null) return "";
    String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
    String workspace = System.getProperty("vibeformer.workspaceRoot", "");
    if (!workspace.isBlank()) normalized = normalized.replace(workspace, "<workspace>");
    String temporary = System.getProperty("java.io.tmpdir", "");
    if (!temporary.isBlank()) normalized = normalized.replace(temporary, "<temp>/");
    return normalized.strip();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String encode(String value) {
    return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(DECODER.decode(value), StandardCharsets.UTF_8);
  }

  private static void writeResults(Path output, List<Result> results) throws IOException {
    Files.createDirectories(output.getParent());
    StringBuilder builder = new StringBuilder();
    builder.append(RESULT_MAGIC).append('\n');
    builder.append("columns\t").append(String.join("\t", RESULT_COLUMNS)).append('\n');
    for (Result result : results) builder.append(result.render()).append('\n');
    Files.writeString(
        output,
        builder.toString(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Collections.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // The proof output never depends on scratch cleanup.
        }
      });
    } catch (IOException ignored) {
      // The proof output never depends on scratch cleanup.
    }
  }

  private static final class ChildListener implements TestExecutionListener {
    private final String selectedId;
    private boolean observed;
    private String status;
    private Throwable throwable;
    private String skippedReason;

    private ChildListener(String selectedId) {
      this.selectedId = selectedId;
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
      if (identifier.getUniqueId().equals(selectedId)) {
        observed = true;
        status = "SKIPPED";
        skippedReason = nullToEmpty(reason);
      }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
      if (identifier.getUniqueId().equals(selectedId)) {
        observed = true;
        status = result.getStatus().name();
        throwable = result.getThrowable().orElse(null);
      }
    }

    private ChildResult result(CaseRow row) {
      if (!observed) {
        return new ChildResult(
            "FAIL", "", "JUnit did not execute the selected unique identifier");
      }
      if ("SUCCESSFUL".equals(status)) {
        return new ChildResult("PASS", row.expectedOutcome, "");
      }
      if ("SKIPPED".equals(status) || "ABORTED".equals(status)) {
        if (row.expectedOutcome.equals("upstream-explicitly-disabled")) {
          return new ChildResult(
              "CONDITION_AUDIT",
              row.expectedOutcome,
              "Focused condition oracle: upstream @Disabled; " + normalizeDiagnostic(skippedReason));
        }
        if (row.expectedOutcome.equals("enabled-on-windows")
            && !System.getProperty("os.name", "").startsWith("Windows")) {
          return new ChildResult(
              "CONDITION_AUDIT",
              row.expectedOutcome,
              "Focused condition oracle: os=windows; current host is non-Windows");
        }
        if (row.expectedOutcome.equals("external-reader-path-conditional")) {
          return new ChildResult(
              "CONDITION_AUDIT",
              row.expectedOutcome,
              "Focused condition oracle: upstream PATH predicate is explicitly conditional");
        }
        return new ChildResult(
            "FAIL", "", "Unexpected JUnit " + status + ": " + normalizeDiagnostic(skippedReason));
      }
      String diagnostic =
          throwable == null
              ? "JUnit execution failed without a throwable"
              : throwableChain(throwable);
      return new ChildResult("FAIL", "", normalizeDiagnostic(diagnostic));
    }

    private static String throwableChain(Throwable error) {
      StringBuilder result = new StringBuilder();
      Throwable current = error;
      while (current != null) {
        if (!result.isEmpty()) result.append("\ncaused by: ");
        result.append(current.getClass().getName())
            .append(": ")
            .append(nullToEmpty(current.getMessage()));
        current = current.getCause();
      }
      return result.toString();
    }
  }

  private record ChildResult(String status, String observation, String diagnostic) {}

  private record Result(
      CaseRow row,
      String revision,
      String status,
      String observation,
      String diagnostic) {
    private static Result from(
        CaseRow row, String revision, String status, String observation, String diagnostic) {
      return new Result(row, revision, status, observation, diagnostic);
    }

    private String render() {
      return String.join(
          "\t",
          "case",
          row.caseId,
          ORIGIN,
          revision,
          row.uniqueId,
          row.sourcePath,
          row.sourceSha256,
          row.sourceLine,
          row.behaviorFamily,
          row.productClassification,
          row.executionOwner,
          status,
          encode(observation),
          encode(diagnostic));
    }
  }

  private record CaseRow(
      String caseId,
      String uniqueId,
      String sourcePath,
      String sourceSha256,
      String sourceLine,
      String sourceClass,
      String sourceMethod,
      String caseKind,
      String behaviorFamily,
      String productClassification,
      String executionOwner,
      String expectedOutcome) {}

  private record Manifest(String revision, List<CaseRow> cases) {
    private static Manifest read(Path path) throws IOException {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      if (lines.isEmpty() || !lines.get(0).equals("VIBEFORMER_PKL_CORE_TEST_CONTRACT_V1")) {
        throw new IllegalArgumentException("Pkl.Core contract has the wrong schema marker");
      }
      String revision = null;
      List<String> columns = null;
      List<CaseRow> cases = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        String[] fields = line.split("\\t", -1);
        if (fields[0].equals("meta") && fields.length == 3 && fields[1].equals("source-revision")) {
          revision = fields[2];
        } else if (fields[0].equals("case-columns")) {
          columns = List.of(fields).subList(1, fields.length);
        } else if (fields[0].equals("case")) {
          if (columns == null || fields.length != columns.size() + 1) {
            throw new IllegalArgumentException("Malformed Pkl.Core contract case row");
          }
          Map<String, String> row = new LinkedHashMap<>();
          for (int index = 0; index < columns.size(); index++) {
            row.put(columns.get(index), fields[index + 1]);
          }
          cases.add(
              new CaseRow(
                  required(row, "case-id"),
                  required(row, "junit-unique-id"),
                  required(row, "source-path"),
                  required(row, "source-sha256"),
                  required(row, "source-line"),
                  required(row, "source-class"),
                  required(row, "source-method"),
                  required(row, "case-kind"),
                  required(row, "behavior-family"),
                  required(row, "product-classification"),
                  required(row, "execution-owner"),
                  required(row, "expected-outcome")));
        }
      }
      if (revision == null || cases.isEmpty()) {
        throw new IllegalArgumentException("Pkl.Core contract is missing provenance or cases");
      }
      return new Manifest(revision, List.copyOf(cases));
    }

    private static String required(Map<String, String> row, String key) {
      return Optional.ofNullable(row.get(key))
          .filter(value -> !value.isBlank())
          .orElseThrow(() -> new IllegalArgumentException("Missing contract field: " + key));
    }
  }
}
