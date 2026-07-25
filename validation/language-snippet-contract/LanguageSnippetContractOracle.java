import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes the pinned upstream LanguageSnippetTestsEngine directly. The engine
 * remains the owner of evaluator configuration, output-byte decoding, logger
 * capture, project application, and every normalization applied by the source
 * test. Reflection only exposes the protected per-case hook without copying or
 * subtly reimplementing that behavior here.
 */
public final class LanguageSnippetContractOracle {
  private static final int EXPECTED_CASES = 940;

  private record ContractCase(String id, Path input) {}

  private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
      try {
        Method method = candidate.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
      } catch (NoSuchMethodException ignored) {
        // Continue through the engine hierarchy.
      }
    }
    throw new NoSuchMethodException(type.getName() + "." + name);
  }

  private static List<ContractCase> readCases(Path manifest) throws Exception {
    List<String> columns = null;
    List<ContractCase> cases = new ArrayList<>();
    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      String[] fields = line.split("\t", -1);
      if (fields.length == 0) continue;
      if (fields[0].equals("columns")) {
        columns = List.of(fields).subList(1, fields.length);
      } else if (fields[0].equals("case")) {
        if (columns == null || fields.length != columns.size() + 1) {
          throw new IllegalArgumentException("Malformed case row in " + manifest);
        }
        Map<String, String> row = new HashMap<>();
        for (int index = 0; index < columns.size(); index++) {
          row.put(columns.get(index), fields[index + 1]);
        }
        String id = row.get("case-id");
        String input = row.get("input-path");
        if (id == null || input == null) {
          throw new IllegalArgumentException("Manifest omits case-id or input-path");
        }
        // Upstream discovery supplies absolute paths. This is semantically
        // significant because the engine uses startsWith(projectsDir) before
        // applying the nearest PklProject.
        Path inputPath = Path.of(input).toAbsolutePath().normalize();
        if (!Files.isRegularFile(inputPath)) {
          throw new IllegalArgumentException("Manifest input does not exist: " + inputPath);
        }
        cases.add(new ContractCase(id, inputPath));
      }
    }
    if (cases.size() != EXPECTED_CASES) {
      throw new IllegalArgumentException(
          "Expected " + EXPECTED_CASES + " contract cases but found " + cases.size());
    }
    return cases;
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while (current instanceof InvocationTargetException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String stackTrace(Throwable throwable) {
    StringWriter buffer = new StringWriter();
    unwrap(throwable).printStackTrace(new PrintWriter(buffer));
    return buffer.toString().replace("\r\n", "\n");
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: oracle <manifest.tsv> <output.tsv>");
    }

    Locale.setDefault(Locale.ROOT);
    Path manifest = Path.of(args[0]).toAbsolutePath().normalize();
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    Files.createDirectories(output.getParent());
    Path temporary = output.resolveSibling(output.getFileName() + ".tmp");

    Class<?> engineClass = Class.forName("org.pkl.core.LanguageSnippetTestsEngine");
    Object engine = engineClass.getConstructor().newInstance();
    Method beforeAll = findMethod(engineClass, "beforeAll");
    Method afterAll = findMethod(engineClass, "afterAll");
    Method generateOutput = findMethod(engineClass, "generateOutputFor", Path.class);
    Method pairFirst = Class.forName("kotlin.Pair").getMethod("getFirst");
    Method pairSecond = Class.forName("kotlin.Pair").getMethod("getSecond");

    boolean started = false;
    boolean unexecutable = false;
    try {
      beforeAll.invoke(engine);
      started = true;
      try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        for (ContractCase contractCase : readCases(manifest)) {
          String status;
          String normalized;
          try {
            Object outcome = generateOutput.invoke(engine, contractCase.input());
            boolean success = (Boolean) pairFirst.invoke(outcome);
            normalized = (String) pairSecond.invoke(outcome);
            status = success ? "SUCCESS" : "ERROR";
          } catch (Throwable throwable) {
            unexecutable = true;
            status = "UNEXECUTABLE";
            normalized = stackTrace(throwable);
          }
          writer.write(contractCase.id());
          writer.write('\t');
          writer.write(status);
          writer.write('\t');
          writer.write(encode(normalized));
          writer.write('\n');
        }
      }
      Files.move(
          temporary,
          output,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      if (started) afterAll.invoke(engine);
      Files.deleteIfExists(temporary);
    }

    // Truffle and HTTP dependencies can retain non-daemon runtime threads.
    // Exit only after the engine's afterAll hook and atomic output move have
    // completed, preserving bounded execution without skipping cleanup.
    System.exit(unexecutable ? 1 : 0);
  }
}
