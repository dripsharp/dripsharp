package vibeformer.contract;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Captures the identifiers emitted by the pinned pkl-core Gradle test task.
 *
 * <p>The output is deliberately a lossless intermediate artifact rather than the durable contract.
 * Each field is URL-safe base64 so arbitrary JUnit display names cannot corrupt the tabular stream.
 */
public final class PklCoreTestDiscoveryListener implements TestExecutionListener {
  private static final Object LOCK = new Object();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final Set<String> emitted = new HashSet<>();
  private BufferedWriter writer;

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    var configured = System.getProperty("vibeformer.pklCoreContractOutput");
    if (configured == null || configured.isBlank()) {
      return;
    }
    synchronized (LOCK) {
      try {
        var output = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        writer =
            Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        writer.write(
            "event\tunique-id\tparent-id\tdescriptor-type\tdisplay-name\tlegacy-name\t"
                + "source-kind\tsource-class\tsource-method\tsource-parameters\tstatus\treason\t"
                + "os-name\tos-arch\tjava-version\n");
        writer.flush();
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot create the Vibeformer pkl-core discovery output", exception);
      }
    }
  }

  @Override
  public void executionSkipped(TestIdentifier identifier, String reason) {
    if (identifier.isTest() || identifier.getSource().filter(MethodSource.class::isInstance).isPresent()) {
      emit("skipped", identifier, "SKIPPED", reason);
    }
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    if (identifier.isTest()) {
      emit(
          "finished",
          identifier,
          result.getStatus().name(),
          result.getThrowable().map(Throwable::toString).orElse(""));
    }
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    synchronized (LOCK) {
      if (writer == null) {
        return;
      }
      try {
        writer.close();
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot close the Vibeformer pkl-core discovery output", exception);
      } finally {
        writer = null;
      }
    }
  }

  private void emit(String event, TestIdentifier identifier, String status, String reason) {
    synchronized (LOCK) {
      if (writer == null || !emitted.add(identifier.getUniqueId())) {
        return;
      }
      try {
        var source = identifier.getSource();
        var sourceKind = source.map(value -> value.getClass().getSimpleName()).orElse("");
        var sourceClass = source.flatMap(PklCoreTestDiscoveryListener::sourceClass).orElse("");
        var sourceMethod = source.flatMap(PklCoreTestDiscoveryListener::sourceMethod).orElse("");
        var sourceParameters = source.flatMap(PklCoreTestDiscoveryListener::sourceParameters).orElse("");
        writeFields(
            event,
            identifier.getUniqueId(),
            identifier.getParentId().orElse(""),
            identifier.getType().name(),
            identifier.getDisplayName(),
            identifier.getLegacyReportingName(),
            sourceKind,
            sourceClass,
            sourceMethod,
            sourceParameters,
            status,
            reason == null ? "" : reason,
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""),
            System.getProperty("java.version", ""));
        writer.flush();
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot write the Vibeformer pkl-core discovery output", exception);
      }
    }
  }

  private void writeFields(String... fields) throws IOException {
    for (var index = 0; index < fields.length; index++) {
      if (index > 0) {
        writer.write('\t');
      }
      writer.write(ENCODER.encodeToString(fields[index].getBytes(StandardCharsets.UTF_8)));
    }
    writer.write('\n');
  }

  private static Optional<String> sourceClass(TestSource source) {
    if (source instanceof MethodSource method) {
      return Optional.of(method.getClassName());
    }
    if (source instanceof ClassSource clazz) {
      return Optional.of(clazz.getClassName());
    }
    return Optional.empty();
  }

  private static Optional<String> sourceMethod(TestSource source) {
    return source instanceof MethodSource method
        ? Optional.of(method.getMethodName())
        : Optional.empty();
  }

  private static Optional<String> sourceParameters(TestSource source) {
    return source instanceof MethodSource method
        ? Optional.of(method.getMethodParameterTypes())
        : Optional.empty();
  }
}
