import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.pkl.core.Evaluator;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.Loggers;
import org.pkl.core.ModuleSource;
import org.pkl.core.PModule;
import org.pkl.core.PklException;
import org.pkl.core.SecurityManager;
import org.pkl.core.SecurityManagerException;
import org.pkl.core.SecurityManagers;
import org.pkl.core.StackFrameTransformers;
import org.pkl.core.evaluatorSettings.PklEvaluatorSettings;
import org.pkl.core.evaluatorSettings.TraceMode;
import org.pkl.core.http.HttpClient;
import org.pkl.core.module.ModuleKey;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.module.ModuleKeyFactory;
import org.pkl.core.module.ModuleKeys;
import org.pkl.core.module.ModulePathResolver;
import org.pkl.core.module.ResolvedModuleKey;
import org.pkl.core.packages.DependencyMetadata;
import org.pkl.core.packages.PackageAssetUri;
import org.pkl.core.packages.PackageUri;
import org.pkl.core.project.Project;
import org.pkl.core.resource.Resource;
import org.pkl.core.resource.ResourceReader;
import org.pkl.core.resource.ResourceReaders;
import org.pkl.core.settings.PklSettings;

/** Independently executed upstream oracle for the loader, policy, and configuration contract. */
public final class LoadingContractUpstreamOracle {
  private static final Base64.Encoder BASE64 = Base64.getEncoder();

  private LoadingContractUpstreamOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("workspace root, output, and work paths are required");
    }
    Path root = Path.of(args[0]).toAbsolutePath().normalize();
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    Path work = Path.of(args[2]).toAbsolutePath().normalize();
    Path fixtures = root.resolve("vibeformer/validation/loading-contract/fixtures");
    Files.createDirectories(output.getParent());
    Files.createDirectories(work);

    try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      write(writer, "module-source/forms", "API", observeModuleSourceForms(work));
      write(writer, "local/import-resource", "LOADING", observeLocal(fixtures));
      write(writer, "local/list-glob", "LOADING", observeLocalGlob(fixtures));
      write(writer, "modulepath/directory-archive", "LOADING", observeModulePath(fixtures, work));
      write(writer, "stdlib/import", "LOADING", observeStandardLibrary());
      write(writer, "custom/module-resource-lifecycle", "LOADING", observeCustomReaders());
      write(writer, "resources/environment-property", "LOADING", observeEnvironmentAndProperties());
      write(writer, "evaluator/builder", "API", observeEvaluatorBuilder(work));
      write(writer, "security/policy", "POLICY", observeSecurityPolicy(work));

      NetworkObservations network = observeNetworkAndPackages(root, work);
      write(writer, "https/rewrite-redirect-headers", "HTTP", network.https());
      write(writer, "package/assets-cache-integrity", "PACKAGE", network.packages());
      write(writer, "project/projectpackage-dependencies", "PROJECT", network.projectpackage());
      write(writer, "network/package-errors", "ERROR", network.errors());

      write(writer, "project/evaluator-user-settings", "SETTINGS", observeSettings(fixtures, work));
      write(writer, "errors/missing-invalid-io-type", "ERROR", observeErrors(fixtures, work));
      write(writer, "project/dependency-cycles", "ERROR", observeProjectCycles(root));
      write(writer, "lifecycle/close", "LIFECYCLE", observeLifecycle());
    }
  }

  private static String observeModuleSourceForms(Path work) throws Exception {
    Path path = work.resolve("module-source.pkl");
    Files.writeString(path, "value = 1\n", StandardCharsets.UTF_8);
    ModuleSource text = ModuleSource.text("value = 1");
    ModuleSource fromPath = ModuleSource.path(path);
    ModuleSource fromFile = ModuleSource.file(path.toFile());
    ModuleSource uri = ModuleSource.uri("https://example.test/main.pkl");
    ModuleSource modulePath = ModuleSource.modulePath("lib/main.pkl");
    return "text=" + text.getUri() + ":" + text.getContents()
        + "|path=" + fromPath.getUri().getScheme() + ":" + (fromPath.getContents() == null)
        + "|file=" + fromFile.getUri().getScheme() + ":" + (fromFile.getContents() == null)
        + "|uri=" + uri.getUri() + ":" + (uri.getContents() == null)
        + "|modulepath=" + modulePath.getUri() + ":" + (modulePath.getContents() == null);
  }

  private static String observeLocal(Path fixtures) {
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      PModule module = evaluator.evaluate(ModuleSource.path(fixtures.resolve("local/main.pkl")));
      return "imported=" + module.getProperty("imported")
          + "|resource=" + escaped(module.getProperty("resource"));
    }
  }

  private static String observeLocalGlob(Path fixtures) {
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      PModule module = evaluator.evaluate(ModuleSource.path(fixtures.resolve("local/main.pkl")));
      return "modules=" + sorted(module.getProperty("moduleKeys"))
          + "|resources=" + sorted(module.getProperty("resourceKeys"));
    }
  }

  private static String observeModulePath(Path fixtures, Path work) throws Exception {
    Path source = fixtures.resolve("modulepath");
    Path directoryRoot = work.resolve("modulepath-directory");
    copyTree(source.resolve("directory"), directoryRoot.resolve("directory"));
    Path archive = work.resolve("modulepath-contract.zip");
    zipTree(source.resolve("archive"), archive, "archive/");

    try (ModulePathResolver resolver = new ModulePathResolver(List.of(directoryRoot, archive))) {
      ModuleKeyFactory factory = ModuleKeyFactories.modulePath(resolver);
      ResourceReader reader = ResourceReaders.modulePath(resolver);
      ModuleKey directory = factory.create(URI.create("modulepath:/directory/module.pkl")).orElseThrow();
      ModuleKey zipped = factory.create(URI.create("modulepath:/archive/module.pkl")).orElseThrow();
      ResolvedModuleKey resolvedDirectory = directory.resolve(SecurityManagers.defaultManager);
      ResolvedModuleKey resolvedZip = zipped.resolve(SecurityManagers.defaultManager);
      Resource directoryResource = (Resource) reader.read(URI.create("modulepath:/directory/resource.txt")).orElseThrow();
      Resource zipResource = (Resource) reader.read(URI.create("modulepath:/archive/resource.txt")).orElseThrow();
      return "directory=" + resolvedDirectory.getUri().getScheme() + ":" + compact(resolvedDirectory.loadSource())
          + ":" + compact(directoryResource.getText())
          + "|archive=" + resolvedZip.getUri().getScheme() + ":" + compact(resolvedZip.loadSource())
          + ":" + compact(zipResource.getText());
    }
  }

  private static String observeStandardLibrary() {
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      Object result = evaluator.evaluateExpression(ModuleSource.text(""), "import(\"pkl:math\").gcd(54, 24)");
      return "gcd=" + result;
    }
  }

  private static String observeCustomReaders() throws Exception {
    CountingModuleFactory factory = new CountingModuleFactory();
    CountingResourceReader reader = new CountingResourceReader();
    Evaluator evaluator = EvaluatorBuilder.unconfigured()
        .setStackFrameTransformer(StackFrameTransformers.defaultTransformer)
        .setAllowedModules(List.of(Pattern.compile("custom:"), Pattern.compile("pkl:")))
        .setAllowedResources(List.of(Pattern.compile("contractres:")))
        .addModuleKeyFactory(ModuleKeyFactories.standardLibrary)
        .addModuleKeyFactory(factory)
        .addResourceReader(reader)
        .build();
    PModule module = evaluator.evaluate(ModuleSource.create(
        URI.create("custom:main"),
        "dependency = import(\"custom:dependency\").value\n"
            + "resource = read(\"contractres:item\")\n"));
    String result = "dependency=" + module.getProperty("dependency")
        + "|resource=" + module.getProperty("resource")
        + "|creates=" + factory.creates.get();
    evaluator.close();
    evaluator.close();
    return result + "|factory-closes=" + factory.closes.get() + "|reader-closes=" + reader.closes.get();
  }

  private static String observeEnvironmentAndProperties() {
    EvaluatorBuilder builder = EvaluatorBuilder.preconfigured()
        .setEnvironmentVariables(Map.of("CONTRACT_ENV", "environment-value"))
        .setExternalProperties(Map.of("contract.property", "property-value"));
    try (Evaluator evaluator = builder.build()) {
      PModule module = evaluator.evaluate(ModuleSource.text(
          "environment = read(\"env:CONTRACT_ENV\")\n"
              + "property = read(\"prop:contract.property\")\n"));
      return "environment=" + module.getProperty("environment")
          + "|property=" + module.getProperty("property");
    }
  }

  private static String observeEvaluatorBuilder(Path work) {
    CountingModuleFactory factory = new CountingModuleFactory();
    CountingResourceReader reader = new CountingResourceReader();
    Path cache = work.resolve("builder-cache");
    EvaluatorBuilder builder = EvaluatorBuilder.unconfigured()
        .setColor(true)
        .setStackFrameTransformer(StackFrameTransformers.defaultTransformer)
        .setAllowedModules(List.of(Pattern.compile("file:")))
        .setAllowedResources(List.of(Pattern.compile("env:")))
        .setRootDir(work)
        .setLogger(Loggers.noop())
        .setHttpClient(HttpClient.dummyClient())
        .setModuleKeyFactories(List.of(factory))
        .setResourceReaders(List.of(reader))
        .setEnvironmentVariables(Map.of("A", "1"))
        .setExternalProperties(Map.of("B", "2"))
        .setTimeout(java.time.Duration.ofSeconds(2))
        .setModuleCacheDir(cache)
        .setOutputFormat("json")
        .setTraceMode(TraceMode.PRETTY)
        .setPowerAssertionsEnabled(true);
    boolean conflict = false;
    try {
      builder.setSecurityManager(SecurityManagers.defaultManager).setAllowedModules(List.of());
    } catch (IllegalStateException expected) {
      conflict = true;
    } finally {
      builder.unsetSecurityManager();
    }
    return "color=" + builder.getColor()
        + "|stack=" + (builder.getStackFrameTransformer() != null)
        + "|allowed=" + builder.getAllowedModules().size() + ":" + builder.getAllowedResources().size()
        + "|root=" + builder.getRootDir().equals(work)
        + "|logger=" + (builder.getLogger() != null)
        + "|http=" + (builder.getHttpClient() != null)
        + "|readers=" + builder.getModuleKeyFactories().size() + ":" + builder.getResourceReaders().size()
        + "|values=" + builder.getEnvironmentVariables().get("A") + ":" + builder.getExternalProperties().get("B")
        + "|timeout=" + builder.getTimeout().toSeconds()
        + "|cache=" + builder.getModuleCacheDir().equals(cache)
        + "|format=" + builder.getOutputFormat()
        + "|trace=" + builder.getTraceMode().name().toLowerCase()
        + "|power=" + builder.getPowerAssertionsEnabled()
        + "|conflict=" + conflict;
  }

  private static String observeSecurityPolicy(Path work) throws Exception {
    Path root = work.resolve("security-root");
    Path allowed = root.resolve("allowed.pkl");
    Path outside = work.resolve("outside.pkl");
    Files.createDirectories(root);
    Files.writeString(allowed, "value = 1\n", StandardCharsets.UTF_8);
    Files.writeString(outside, "value = 2\n", StandardCharsets.UTF_8);
    SecurityManager manager = SecurityManagers.standard(
        List.of(Pattern.compile("file:"), Pattern.compile("pkl:")),
        List.of(Pattern.compile("file:"), Pattern.compile("env:")),
        SecurityManagers.defaultTrustLevels,
        root);
    manager.checkResolveModule(allowed.toUri());
    manager.checkReadResource(allowed.toUri());
    boolean moduleDenied = throwsSecurity(() -> manager.checkResolveModule(URI.create("https://example.test/main.pkl")));
    boolean resourceDenied = throwsSecurity(() -> manager.checkReadResource(URI.create("prop:secret")));
    boolean trustDenied = throwsSecurity(() -> manager.checkImportModule(
        URI.create("https://example.test/main.pkl"), allowed.toUri()));
    boolean rootDenied = throwsSecurity(() -> manager.checkResolveModule(outside.toUri()));
    boolean encodedTraversal = throwsUri(() -> new PackageUri(
        "package://attacker.test/%2e%2e/legit@1.2.3"));
    boolean literalTraversal = throwsUri(() -> new PackageUri(
        "package://attacker.test/../legit@1.2.3"));
    return "allowed=true|module-denied=" + moduleDenied
        + "|resource-denied=" + resourceDenied
        + "|trust-denied=" + trustDenied
        + "|root-denied=" + rootDenied
        + "|encoded-traversal=" + encodedTraversal
        + "|literal-traversal=" + literalTraversal;
  }

  private static NetworkObservations observeNetworkAndPackages(Path root, Path work) throws Exception {
    Path commonsBuild = root.resolve("research/pkl/pkl-commons-test/build");
    try (ContractHttpsServer server = new ContractHttpsServer(commonsBuild)) {
      server.start();
      List<Pattern> httpModules = new ArrayList<>(SecurityManagers.defaultAllowedModules);
      httpModules.add(Pattern.compile("http:"));
      List<Pattern> httpResources = new ArrayList<>(SecurityManagers.defaultAllowedResources);
      httpResources.add(Pattern.compile("http:"));
      PModule httpModule;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setAllowedModules(httpModules)
          .setAllowedResources(httpResources)
          .setHttpClient(HttpClient.builder()
              .addHeaders("**", Map.of("X-Contract", List.of("enabled")))
              .build())
          .build()) {
        httpModule = evaluator.evaluate(ModuleSource.uri(server.plainUri("/plain-main.pkl")));
      }
      PModule proxied;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setAllowedModules(httpModules)
          .setAllowedResources(httpResources)
          .setHttpClient(HttpClient.builder()
              .setProxy(server.plainUri(""), List.of())
              .addHeaders("**", Map.of("X-Contract", List.of("enabled")))
              .build())
          .build()) {
        proxied = evaluator.evaluate(ModuleSource.uri("http://origin.test/proxy-main.pkl"));
      }
      HttpClient directClient = server.newClient()
          .addRewrite(URI.create("https://origin.test/"), URI.create("https://localhost:0/"))
          .addHeaders("**", Map.of("X-Contract", List.of("enabled")))
          .build();
      List<URI> checked = new ArrayList<>();
      HttpResponse<String> redirectResponse = directClient.send(
          HttpRequest.newBuilder(URI.create("https://origin.test/redirect.pkl")).build(),
          HttpResponse.BodyHandlers.ofString(),
          checked::add);
      PModule httpsModule;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured().setHttpClient(directClient).build()) {
        httpsModule = evaluator.evaluate(ModuleSource.uri("https://origin.test/main.pkl"));
      }
      String https = "http=" + httpModule.getProperty("value") + ":"
          + escaped(httpModule.getProperty("payload"))
          + "|https=" + httpsModule.getProperty("value") + ":"
          + escaped(httpsModule.getProperty("payload"))
          + "|redirect=" + compact(redirectResponse.body())
          + "|checked=" + checked.size()
          + "|headers=" + server.allRequestsHadContractHeader.get()
          + "|proxy=" + proxied.getProperty("value") + ":" + (server.proxyRequestCount.get() > 0);

      Path cache = work.resolve("package-cache");
      String packageSource =
          "bird = import(\"package://localhost:0/birds@0.5.0#/catalog/Swallow.pkl\").name\n"
              + "modules = import*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n"
              + "resources = read*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n";
      int beforePackages = server.requestCount.get();
      PModule first;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(server.newClient().build())
          .setModuleCacheDir(cache)
          .build()) {
        first = evaluator.evaluate(ModuleSource.text(packageSource));
      }
      int downloaded = server.requestCount.get() - beforePackages;

      String metadataSha = Files.readString(
          commonsBuild.resolve("test-packages/birds@0.5.0/birds@0.5.0.json.sha256"),
          StandardCharsets.UTF_8).trim();
      boolean checksumFailure;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(server.newClient().build())
          .setModuleCacheDir(work.resolve("invalid-checksum-cache"))
          .build()) {
        evaluator.evaluate(ModuleSource.text(
            "bad = import(\"package://localhost:0/birds@0.5.0::sha256:"
                + "0000000000000000000000000000000000000000000000000000000000000000#/Bird.pkl\")\n"));
        checksumFailure = false;
      } catch (PklException expected) {
        checksumFailure = expected.getMessage().contains("computed checksum")
            && expected.getMessage().contains("expected checksum");
      }

      DependencyMetadata metadata = DependencyMetadata.parse(Files.readString(
          commonsBuild.resolve("test-packages/birds@0.5.0/birds@0.5.0.json"),
          StandardCharsets.UTF_8));
      PackageAssetUri normalizedAsset = new PackageAssetUri(
          "package://localhost:0/birds@0.5.0#/foo/../Bird.pkl").resolve("./catalog.pkl");

      int beforeOffline = server.requestCount.get();
      PModule offline;
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(HttpClient.dummyClient())
          .setModuleCacheDir(cache)
          .build()) {
        offline = evaluator.evaluate(ModuleSource.text(packageSource));
      }
      boolean offlineNoNetwork = server.requestCount.get() == beforeOffline;
      String packages = "bird=" + first.getProperty("bird")
          + "|modules=" + sorted(first.getProperty("modules"))
          + "|resources=" + sorted(first.getProperty("resources"))
          + "|offline=" + offline.getProperty("bird") + ":" + offlineNoNetwork
          + "|downloads=" + (downloaded > 0)
          + "|metadata=" + metadata.getName() + ":" + metadata.getDependencies().size()
          + "|metadata-sha=" + (metadataSha.length() == 64)
          + "|asset=" + normalizedAsset.getAssetPath()
          + "|checksum-failure=" + checksumFailure;

      String projectpackage = observeProjectPackage(work, cache);
      String errors = observeNetworkErrors(server, work, checksumFailure);
      return new NetworkObservations(https, packages, projectpackage, errors);
    }
  }

  private static String observeNetworkErrors(
      ContractHttpsServer server, Path work, boolean checksumFailure) {
    List<Pattern> httpModules = new ArrayList<>(SecurityManagers.defaultAllowedModules);
    httpModules.add(Pattern.compile("http:"));
    boolean httpFailure = throwsPkl(() -> {
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setAllowedModules(httpModules)
          .build()) {
        evaluator.evaluate(ModuleSource.uri(server.plainUri("/missing.pkl")));
      }
    });
    boolean missingAsset = throwsPkl(() -> {
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(server.newClient().build())
          .setModuleCacheDir(work.resolve("missing-asset-cache"))
          .build()) {
        evaluator.evaluate(ModuleSource.text(
            "value = import(\"package://localhost:0/birds@0.5.0#/missing.pkl\")\n"));
      }
    });
    boolean invalidMetadata = throwsPkl(() -> {
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(server.newClient().build())
          .setModuleCacheDir(work.resolve("invalid-metadata-cache"))
          .build()) {
        evaluator.evaluate(ModuleSource.text(
            "value = import(\"package://localhost:0/badMetadataJson@1.0.0#/main.pkl\")\n"));
      }
    });
    boolean invalidScheme = throwsUri(() -> new PackageUri("package:invalid"));
    int beforePolicy = server.requestCount.get();
    boolean policy = throwsPkl(() -> {
      try (Evaluator evaluator = Evaluator.preconfigured()) {
        evaluator.evaluate(ModuleSource.uri(server.plainUri("/plain-main.pkl")));
      }
    }) && server.requestCount.get() == beforePolicy;
    boolean coldCache = throwsPklOrAssertion(() -> {
      try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
          .setHttpClient(HttpClient.dummyClient())
          .setModuleCacheDir(work.resolve("cold-offline-cache"))
          .build()) {
        evaluator.evaluate(ModuleSource.text(
            "value = import(\"package://localhost:0/birds@0.5.0#/Bird.pkl\")\n"));
      }
    });
    if (!(httpFailure && missingAsset && invalidMetadata && checksumFailure && invalidScheme
        && policy && coldCache)) {
      throw new IllegalStateException("deterministic HTTP/package failure matrix did not hold");
    }
    return "http=" + httpFailure + "|missing=" + missingAsset
        + "|metadata=" + invalidMetadata + "|checksum=" + checksumFailure
        + "|scheme=" + invalidScheme + "|policy=" + policy + "|cold-cache=" + coldCache;
  }

  private static String observeProjectPackage(Path work, Path cache) throws Exception {
    Path projectDir = work.resolve("projectpackage");
    Files.createDirectories(projectDir);
    Path commonsBuild = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        .resolve("research/pkl/pkl-commons-test/build/test-packages");
    if (!Files.isDirectory(commonsBuild)) {
      // The oracle can be launched from any directory; derive the source from the populated cache.
      commonsBuild = null;
    }
    String birdsSha = readCachedOrBuildMetadataSha(cache, "birds@0.5.0", commonsBuild);
    String fruitSha = readCachedOrBuildMetadataSha(cache, "fruit@1.0.5", commonsBuild);
    Files.writeString(projectDir.resolve("PklProject"),
        "amends \"pkl:Project\"\n"
            + "dependencies { [\"birds\"] { uri = \"package://localhost:0/birds@0.5.0\" } }\n",
        StandardCharsets.UTF_8);
    Files.writeString(projectDir.resolve("PklProject.deps.json"),
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"resolvedDependencies\": {\n"
            + "    \"package://localhost:0/birds@0\": {\"type\": \"remote\", \"uri\": \"projectpackage://localhost:0/birds@0.5.0\", \"checksums\": {\"sha256\": \"" + birdsSha + "\"}},\n"
            + "    \"package://localhost:0/fruit@1\": {\"type\": \"remote\", \"uri\": \"projectpackage://localhost:0/fruit@1.0.5\", \"checksums\": {\"sha256\": \"" + fruitSha + "\"}}\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);
    Path main = projectDir.resolve("main.pkl");
    Files.writeString(main,
        "bird = import(\"@birds/catalog/Swallow.pkl\").name\n"
            + "resource = read(\"@birds/catalog/Ostrich.pkl\").text.contains(\"Ostrich\")\n",
        StandardCharsets.UTF_8);
    Project project = Project.loadFromPath(projectDir.resolve("PklProject"));
    try (Evaluator evaluator = EvaluatorBuilder.preconfigured()
        .applyFromProject(project)
        .setModuleCacheDir(cache)
        .setHttpClient(HttpClient.dummyClient())
        .build()) {
      PModule module = evaluator.evaluate(ModuleSource.path(main));
      return "dependencies=" + project.getDependencies().remoteDependencies().size()
          + "|bird=" + module.getProperty("bird")
          + "|resource=" + module.getProperty("resource");
    }
  }

  private static String readCachedOrBuildMetadataSha(Path cache, String packageName, Path buildRoot)
      throws IOException {
    if (buildRoot != null) {
      Path source = buildRoot.resolve(packageName).resolve(packageName + ".json.sha256");
      if (Files.isRegularFile(source)) return Files.readString(source).trim();
    }
    Path metadata = cache.resolve("package-2/localhost(3a)0").resolve(packageName)
        .resolve(packageName + ".json");
    return sha256(metadata);
  }

  private static String observeSettings(Path fixtures, Path work) throws Exception {
    Path projectDir = work.resolve("settings-project");
    copyTree(fixtures.resolve("project"), projectDir);
    Project project = Project.loadFromPath(projectDir.resolve("PklProject"));
    PklEvaluatorSettings settings = project.getResolvedEvaluatorSettings();
    PklSettings user = PklSettings.load(ModuleSource.path(projectDir.resolve("settings.pkl")));
    PklEvaluatorSettings.ExternalReader moduleReader = settings.externalModuleReaders().get("contractmod");
    PklEvaluatorSettings.ExternalReader resourceReader = settings.externalResourceReaders().get("contractres");
    EvaluatorBuilder applied = EvaluatorBuilder.preconfigured().applyFromProject(project);
    return "env=" + settings.env().get("CONTRACT_ENV")
        + "|property=" + settings.externalProperties().get("contract.property")
        + "|allowed=" + settings.allowedModules().size() + ":" + settings.allowedResources().size()
        + "|paths=" + settings.rootDir().equals(projectDir) + ":"
        + settings.moduleCacheDir().equals(projectDir.resolve("cache")) + ":"
        + settings.modulePath().get(0).equals(projectDir.resolve("modules"))
        + "|timeout=" + settings.timeout().getValue()
        + "|color=" + settings.color().name().toLowerCase()
        + "|trace=" + settings.traceMode().name().toLowerCase()
        + "|external=" + moduleReader.executable().endsWith("tools/module-reader") + ":"
        + moduleReader.workingDir().endsWith("reader-work") + ":"
        + resourceReader.executable().equals("contract-resource-reader")
        + "|http=" + settings.http().proxy().address() + ":"
        + settings.http().rewrites().size() + ":" + settings.http().headers().size()
        + "|user=" + user.editor().equals(PklSettings.Editor.SUBLIME) + ":"
        + user.http().headers().get("https://mirror.test/**").get("X-Contract").size()
        + "|applied=" + applied.getColor() + ":" + applied.getTraceMode().name().toLowerCase()
        + ":" + applied.getEnvironmentVariables().get("CONTRACT_ENV");
  }

  private static String observeErrors(Path fixtures, Path work) throws Exception {
    boolean missing;
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      evaluator.evaluate(ModuleSource.path(work.resolve("does-not-exist.pkl")));
      missing = false;
    } catch (PklException expected) {
      missing = expected.getMessage().contains("Cannot find module");
    }
    boolean relative;
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      evaluator.evaluate(ModuleSource.create(URI.create("relative.pkl"), "value = 1"));
      relative = false;
    } catch (PklException expected) {
      relative = expected.getMessage().contains("relative module URI");
    }
    boolean projectType;
    try {
      Project.loadFromPath(fixtures.resolve("project/not-a-project.pkl"));
      projectType = false;
    } catch (PklException expected) {
      projectType = expected.getMessage().contains("pkl.Project")
          && expected.getMessage().contains("contract.NotAProject");
    }
    boolean settingsType;
    try {
      PklSettings.load(ModuleSource.path(fixtures.resolve("project/not-settings.pkl")));
      settingsType = false;
    } catch (RuntimeException expected) {
      settingsType = expected.getMessage().contains("pkl.settings");
    }
    boolean invalidPackage = throwsUri(() -> new PackageUri("package:invalid"));

    ModuleKeyFactory failingFactory = uri -> {
      if (!uri.getScheme().equals("iofail")) return Optional.empty();
      return Optional.of(new FailingModuleKey(uri));
    };
    boolean ioFailure;
    try (Evaluator evaluator = EvaluatorBuilder.unconfigured()
        .setStackFrameTransformer(StackFrameTransformers.defaultTransformer)
        .setAllowedModules(List.of(Pattern.compile("repl:"), Pattern.compile("iofail:")))
        .setAllowedResources(List.of())
        .addModuleKeyFactory(failingFactory)
        .build()) {
      evaluator.evaluate(ModuleSource.text("value = import(\"iofail:module\")\n"));
      ioFailure = false;
    } catch (PklException expected) {
      ioFailure = expected.getMessage().contains("I/O error")
          || expected.getMessage().contains("contract I/O failure");
    }
    return "missing=" + missing + "|relative=" + relative + "|invalid-package=" + invalidPackage
        + "|io=" + ioFailure + "|project-type=" + projectType + "|settings-type=" + settingsType;
  }

  private static String observeProjectCycles(Path root) {
    Path resources = root.resolve("research/pkl/pkl-core/src/test/resources/org/pkl/core/project");
    boolean single;
    try {
      Project.loadFromPath(resources.resolve("projectCycle1/PklProject"));
      single = false;
    } catch (PklException expected) {
      single = expected.getMessage().contains("cannot be circular")
          && expected.getMessage().contains("Cycle:");
    }
    boolean multiple;
    try {
      Project.loadFromPath(resources.resolve("projectCycle4/PklProject"));
      multiple = false;
    } catch (PklException expected) {
      multiple = expected.getMessage().contains("circular imports")
          && expected.getMessage().contains("Cycle 1:")
          && expected.getMessage().contains("Cycle 2:");
    }
    return "single=" + single + "|multiple=" + multiple;
  }

  private static String observeLifecycle() {
    Evaluator evaluator = Evaluator.preconfigured();
    evaluator.close();
    evaluator.close();
    boolean evaluateAfterClose;
    try {
      evaluator.evaluate(ModuleSource.text("value = 1"));
      evaluateAfterClose = false;
    } catch (RuntimeException expected) {
      evaluateAfterClose = true;
    }
    HttpClient client = HttpClient.builder().build();
    client.close();
    client.close();
    boolean httpAfterClose;
    try {
      client.send(
          HttpRequest.newBuilder(URI.create("https://example.test/")).build(),
          HttpResponse.BodyHandlers.discarding(),
          ignored -> {});
      httpAfterClose = false;
    } catch (IllegalStateException expected) {
      httpAfterClose = true;
    } catch (Exception unexpected) {
      throw new RuntimeException(unexpected);
    }
    return "evaluator-repeat=true|evaluator-after-close=" + evaluateAfterClose
        + "|http-repeat=true|http-after-close=" + httpAfterClose;
  }

  private static String sorted(Object value) {
    Collection<?> collection = (Collection<?>) value;
    return collection.stream().map(String::valueOf).sorted().toList().toString();
  }

  private static String escaped(Object value) {
    return String.valueOf(value).replace("\\", "\\\\").replace("\n", "\\n");
  }

  private static String compact(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }

  private static void copyTree(Path source, Path destination) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, destination.resolve(source.relativize(file).toString()),
            StandardCopyOption.REPLACE_EXISTING);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void zipTree(Path source, Path archive, String prefix) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      Files.walk(source).filter(Files::isRegularFile).sorted().forEach(file -> {
        try {
          ZipEntry entry = new ZipEntry(prefix + source.relativize(file).toString().replace('\\', '/'));
          entry.setTime(0);
          zip.putNextEntry(entry);
          Files.copy(file, zip);
          zip.closeEntry();
        } catch (IOException error) {
          throw new RuntimeException(error);
        }
      });
    }
  }

  private static String sha256(Path path) throws IOException {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
      StringBuilder result = new StringBuilder();
      for (byte value : digest) result.append(String.format("%02x", value & 0xff));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static boolean throwsSecurity(CheckedAction action) {
    try {
      action.run();
      return false;
    } catch (SecurityManagerException expected) {
      return true;
    } catch (Exception unexpected) {
      throw new RuntimeException(unexpected);
    }
  }

  private static boolean throwsUri(CheckedAction action) {
    try {
      action.run();
      return false;
    } catch (URISyntaxException expected) {
      return true;
    } catch (Exception unexpected) {
      throw new RuntimeException(unexpected);
    }
  }

  private static boolean throwsPkl(CheckedAction action) {
    try {
      action.run();
      return false;
    } catch (PklException expected) {
      return true;
    } catch (Exception unexpected) {
      throw new RuntimeException(unexpected);
    }
  }

  private static boolean throwsPklOrAssertion(CheckedAction action) {
    try {
      action.run();
      return false;
    } catch (PklException | AssertionError expected) {
      return true;
    } catch (Exception unexpected) {
      throw new RuntimeException(unexpected);
    }
  }

  private static void write(BufferedWriter writer, String id, String kind, String observation)
      throws IOException {
    writer.write(id);
    writer.write('\t');
    writer.write(kind);
    writer.write('\t');
    writer.write(BASE64.encodeToString(observation.getBytes(StandardCharsets.UTF_8)));
    writer.newLine();
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private record NetworkObservations(
      String https, String packages, String projectpackage, String errors) {}

  private static final class CountingModuleFactory implements ModuleKeyFactory {
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public Optional<ModuleKey> create(URI uri) {
      if (!uri.getScheme().equals("custom")) return Optional.empty();
      creates.incrementAndGet();
      return Optional.of(ModuleKeys.synthetic(uri, "value = 42\n"));
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  private static final class CountingResourceReader implements ResourceReader {
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public String getUriScheme() {
      return "contractres";
    }

    @Override
    public Optional<Object> read(URI uri) {
      return Optional.of("resource-value");
    }

    @Override
    public boolean hasHierarchicalUris() {
      return false;
    }

    @Override
    public boolean isGlobbable() {
      return false;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  private static final class FailingModuleKey implements ModuleKey {
    private final URI uri;

    private FailingModuleKey(URI uri) {
      this.uri = uri;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public ResolvedModuleKey resolve(SecurityManager securityManager)
        throws IOException, SecurityManagerException {
      securityManager.checkResolveModule(uri);
      throw new IOException("contract I/O failure");
    }

    @Override
    public boolean hasHierarchicalUris() {
      return false;
    }

    @Override
    public boolean isGlobbable() {
      return false;
    }
  }

  private static final class ContractHttpsServer implements AutoCloseable {
    private final Path buildRoot;
    private final HttpsServer server;
    private final HttpServer plainServer;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger proxyRequestCount = new AtomicInteger();
    private final AtomicBoolean allRequestsHadContractHeader = new AtomicBoolean(true);

    private ContractHttpsServer(Path buildRoot) throws Exception {
      this.buildRoot = buildRoot;
      char[] password = "password".toCharArray();
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(buildRoot.resolve("keystore/localhost.p12"))) {
        keyStore.load(input, password);
      }
      KeyManagerFactory keyManagers = KeyManagerFactory.getInstance("SunX509");
      keyManagers.init(keyStore, password);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keyManagers.getKeyManagers(), null, null);
      server = HttpsServer.create();
      server.setHttpsConfigurator(new HttpsConfigurator(context) {
        @Override
        public void configure(HttpsParameters parameters) {
          parameters.setSSLParameters(getSSLContext().getDefaultSSLParameters());
        }
      });
      server.createContext("/", new Handler());
      server.setExecutor(executor);
      plainServer = HttpServer.create();
      plainServer.createContext("/", new Handler());
      plainServer.setExecutor(executor);
    }

    private void start() throws IOException {
      server.bind(new InetSocketAddress("localhost", 0), 0);
      plainServer.bind(new InetSocketAddress("localhost", 0), 0);
      server.start();
      plainServer.start();
    }

    private URI plainUri(String path) {
      return URI.create("http://localhost:" + plainServer.getAddress().getPort() + path);
    }

    private HttpClient.Builder newClient() {
      return HttpClient.builder()
          .addCertificates(buildRoot.resolve("keystore/localhost.pem"))
          .setTestPort(server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
      plainServer.stop(0);
      executor.shutdownNow();
    }

    private final class Handler implements HttpHandler {
      @Override
      public void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        if (!"enabled".equals(exchange.getRequestHeaders().getFirst("X-Contract"))
            && !exchange.getRequestURI().getPath().contains("@")) {
          allRequestsHadContractHeader.set(false);
        }
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestURI().isAbsolute()) proxyRequestCount.incrementAndGet();
        if (path.equals("/redirect.pkl")) {
          exchange.getResponseHeaders().add("Location", "https://origin.test/main.pkl");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
          return;
        }
        if (path.equals("/main.pkl")) {
          send(exchange, 200,
              "value = 42\npayload = read(\"https://origin.test/data.txt\").text\n"
                  .getBytes(StandardCharsets.UTF_8));
          return;
        }
        if (path.equals("/plain-main.pkl")) {
          send(exchange, 200,
              ("value = 42\npayload = read(\"" + plainUri("/plain-data.txt") + "\").text\n")
                  .getBytes(StandardCharsets.UTF_8));
          return;
        }
        if (path.equals("/plain-data.txt")) {
          send(exchange, 200, "plain payload\n".getBytes(StandardCharsets.UTF_8));
          return;
        }
        if (path.equals("/data.txt")) {
          send(exchange, 200, "secure payload\n".getBytes(StandardCharsets.UTF_8));
          return;
        }
        if (path.equals("/proxy-main.pkl")) {
          send(exchange, 200, "value = 17\n".getBytes(StandardCharsets.UTF_8));
          return;
        }
        String relative = path.substring(1);
        Path packageFile;
        if (relative.endsWith(".zip")) {
          String packageDir = relative.substring(0, relative.indexOf('/'));
          packageFile = buildRoot.resolve("test-packages").resolve(packageDir)
              .resolve(relative.substring(relative.lastIndexOf('/') + 1));
        } else if (relative.contains("@")) {
          packageFile = buildRoot.resolve("test-packages").resolve(relative)
              .resolve(relative + ".json");
        } else {
          packageFile = null;
        }
        if (packageFile == null || !Files.isRegularFile(packageFile)) {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
          return;
        }
        send(exchange, 200, Files.readAllBytes(packageFile));
      }

      private void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
          output.write(bytes);
        }
        exchange.close();
      }
    }
  }
}
