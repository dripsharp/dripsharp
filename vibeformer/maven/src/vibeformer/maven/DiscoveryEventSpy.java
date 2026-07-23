package vibeformer.maven;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Captures Maven's effective, lifecycle-mutated reactor without parsing POMs
 * independently of Maven. The Clojure backend validates and adapts this
 * backend-specific manifest into Vibeformer's neutral project-input model.
 */
public final class DiscoveryEventSpy extends AbstractEventSpy {
    private static final String MANIFEST_PROPERTY = "vibeformer.discovery.manifest";
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, Set<String>> initialCompileRoots =
            new HashMap<String, Set<String>>();
    private final Map<String, List<Resource>> initialResources =
            new HashMap<String, List<Resource>>();
    private final Map<String, List<Resource>> initialTestResources =
            new HashMap<String, List<Resource>>();

    @Override
    public void onEvent(Object event) throws Exception {
        if (!(event instanceof ExecutionEvent)) {
            return;
        }
        ExecutionEvent execution = (ExecutionEvent) event;
        if (execution.getType() == ExecutionEvent.Type.SessionStarted) {
            snapshotInitialRoots(execution.getSession());
        } else if (execution.getType() == ExecutionEvent.Type.SessionEnded) {
            writeManifest(execution.getSession());
        }
    }

    private void snapshotInitialRoots(MavenSession session) throws IOException {
        initialCompileRoots.clear();
        initialResources.clear();
        initialTestResources.clear();
        for (MavenProject project : session.getProjects()) {
            Set<String> roots = new LinkedHashSet<String>();
            for (String root : project.getCompileSourceRoots()) {
                roots.add(canonical(new File(root)));
            }
            String id = projectId(project);
            initialCompileRoots.put(id, roots);
            initialResources.put(id, copyResources(project.getResources()));
            initialTestResources.put(id, copyResources(project.getTestResources()));
        }
    }

    private List<Resource> copyResources(List<Resource> originals) {
        List<Resource> copies = new ArrayList<Resource>();
        for (Resource original : originals) {
            Resource copy = new Resource();
            copy.setDirectory(original.getDirectory());
            copy.setTargetPath(original.getTargetPath());
            copy.setFiltering(original.isFiltering());
            copy.setIncludes(new ArrayList<String>(original.getIncludes()));
            copy.setExcludes(new ArrayList<String>(original.getExcludes()));
            copies.add(copy);
        }
        return copies;
    }

    private void writeManifest(MavenSession session) throws Exception {
        String configured = System.getProperty(MANIFEST_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required -D" + MANIFEST_PROPERTY + "=<path>");
        }

        TreeSet<String> records = new TreeSet<String>();
        Map<String, MavenProject> reactor = new HashMap<String, MavenProject>();
        for (MavenProject project : session.getProjects()) {
            reactor.put(projectId(project), project);
        }
        for (MavenProject project : session.getProjects()) {
            describeProject(records, reactor, project);
        }

        Path output = new File(configured).toPath().toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalStateException(
                    "Discovery manifest has no parent directory: " + output);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".vibeformer-maven-", ".tmp");
        try {
            try (BufferedWriter writer =
                         Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("VIBEFORMER_MAVEN_REACTOR_V1");
                writer.newLine();
                for (String record : records) {
                    writer.write(record);
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void describeProject(
            Set<String> records,
            Map<String, MavenProject> reactor,
            MavenProject project) throws Exception {
        String id = projectId(project);
        record(records, "project", id, canonical(project.getBasedir()),
               nonblank(project.getPackaging(), "jar"));
        record(records, "java-home", id,
               canonical(new File(System.getProperty("java.home"))));
        record(records, "java-release", id,
               Integer.toString(javaRelease(project)));
        record(records, "preview-features", id,
               Boolean.toString(previewFeatures(project)));

        Set<String> initial = initialCompileRoots.get(id);
        if (initial == null) {
            initial = Collections.emptySet();
        }
        Set<String> generatedRoots = configuredGeneratedRoots(project);
        for (String rootValue : project.getCompileSourceRoots()) {
            File root = new File(rootValue);
            if (!root.isDirectory()) {
                continue;
            }
            String path = canonical(root);
            boolean generated = !initial.contains(path)
                    || insideGeneratedBuildDirectory(project, root)
                    || generatedRoots.contains(path);
            record(records, "source-root", id,
                   generated ? "generated" : "ordinary", path);
            addJavaFiles(records,
                         generated ? "generated-source" : "source", id, root);
        }
        for (String generatedRoot : generatedRoots) {
            File root = new File(generatedRoot);
            if (root.isDirectory()) {
                record(records, "source-root", id, "generated", generatedRoot);
                addJavaFiles(records, "generated-source", id, root);
            }
        }

        for (String rootValue : project.getTestCompileSourceRoots()) {
            File root = new File(rootValue);
            if (root.isDirectory()) {
                String path = canonical(root);
                record(records, "test-source-root", id, path);
                addJavaFiles(records, "test-source", id, root);
            }
        }

        addResources(records, "resource-root", "resource", id,
                     resourcesFor(initialResources, id));
        addResources(records, "test-resource-root", "test-resource", id,
                     resourcesFor(initialTestResources, id));
        addDependencies(records, reactor, project);
    }

    private List<Resource> resourcesFor(
            Map<String, List<Resource>> resources, String projectId) {
        List<Resource> selected = resources.get(projectId);
        return selected == null ? Collections.<Resource>emptyList() : selected;
    }

    private void addJavaFiles(
            Set<String> records, String kind, String projectId, File root)
            throws IOException {
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".java")) {
                    record(records, kind, projectId,
                           canonical(path.toFile()));
                }
            }
        }
    }

    private void addResources(
            Set<String> records,
            String rootKind,
            String resourceKind,
            String projectId,
            List<Resource> resources) throws IOException {
        for (Resource resource : resources) {
            if (resource.getDirectory() == null) {
                continue;
            }
            File root = new File(resource.getDirectory());
            if (!root.isDirectory()) {
                continue;
            }
            record(records, rootKind, projectId, canonical(root));

            DirectoryScanner scanner = new DirectoryScanner();
            scanner.setBasedir(root);
            if (resource.getIncludes() != null
                    && !resource.getIncludes().isEmpty()) {
                scanner.setIncludes(resource.getIncludes().toArray(new String[0]));
            }
            if (resource.getExcludes() != null
                    && !resource.getExcludes().isEmpty()) {
                scanner.setExcludes(resource.getExcludes().toArray(new String[0]));
            }
            scanner.addDefaultExcludes();
            scanner.scan();
            for (String included : scanner.getIncludedFiles()) {
                record(records, resourceKind, projectId,
                       canonical(new File(root, included)));
            }
        }
    }

    private void addDependencies(
            Set<String> records,
            Map<String, MavenProject> reactor,
            MavenProject project) throws IOException {
        String owner = projectId(project);
        List<Artifact> artifacts = new ArrayList<Artifact>(project.getArtifacts());
        Collections.sort(artifacts, (left, right) ->
                artifactCoordinate(left).compareTo(artifactCoordinate(right)));
        for (Artifact artifact : artifacts) {
            List<String> scopes = neutralScopes(artifact.getScope());
            if (scopes.isEmpty()) {
                continue;
            }
            String dependencyId = artifactProjectId(artifact);
            MavenProject reactorProject = reactor.get(dependencyId);
            if (reactorProject != null) {
                if ("pom".equals(reactorProject.getPackaging())) {
                    continue;
                }
                File output = new File(reactorProject.getBuild().getOutputDirectory());
                for (String scope : scopes) {
                    record(records, "project-dependency",
                           owner, scope, dependencyId);
                    if (output.isDirectory()) {
                        record(records, "classpath-artifact",
                               owner, scope, "project", dependencyId,
                               canonical(output));
                    } else {
                        record(records, "unresolved-artifact",
                               owner, scope, "project", dependencyId,
                               canonical(output));
                    }
                }
            } else {
                String coordinate = artifactCoordinate(artifact);
                File file = artifact.getFile();
                for (String scope : scopes) {
                    record(records, "external-dependency",
                           owner, scope, coordinate);
                    if (file != null && file.isFile()) {
                        record(records, "classpath-artifact",
                               owner, scope, "external", coordinate,
                               canonical(file));
                    } else {
                        record(records, "unresolved-artifact",
                               owner, scope, "external", coordinate,
                               file == null ? "<unresolved>" : canonical(file));
                    }
                }
            }
        }
    }

    private List<String> neutralScopes(String mavenScope) {
        if (Artifact.SCOPE_COMPILE.equals(mavenScope)
                || mavenScope == null || mavenScope.isEmpty()) {
            return Arrays.asList("compile", "runtime");
        }
        if (Artifact.SCOPE_RUNTIME.equals(mavenScope)) {
            return Collections.singletonList("runtime");
        }
        if (Artifact.SCOPE_PROVIDED.equals(mavenScope)
                || Artifact.SCOPE_SYSTEM.equals(mavenScope)) {
            return Collections.singletonList("compile");
        }
        return Collections.emptyList();
    }

    private Set<String> configuredGeneratedRoots(MavenProject project)
            throws IOException {
        Set<String> roots = new LinkedHashSet<String>();
        String configured = compilerConfiguration(project,
                                                  "generatedSourcesDirectory");
        if (configured != null && !configured.trim().isEmpty()) {
            File path = new File(interpolate(project, configured));
            if (!path.isAbsolute()) {
                path = new File(project.getBasedir(), path.getPath());
            }
            if (path.isDirectory()) {
                roots.add(canonical(path));
            }
        }
        return roots;
    }

    private boolean insideGeneratedBuildDirectory(
            MavenProject project, File sourceRoot) throws IOException {
        Path generated = new File(project.getBuild().getDirectory(),
                                  "generated-sources")
                .getCanonicalFile().toPath();
        return sourceRoot.getCanonicalFile().toPath().startsWith(generated);
    }

    private int javaRelease(MavenProject project) {
        String value = firstNonblank(
                compilerConfiguration(project, "release"),
                project.getProperties().getProperty("maven.compiler.release"),
                compilerConfiguration(project, "target"),
                project.getProperties().getProperty("maven.compiler.target"),
                compilerConfiguration(project, "source"),
                project.getProperties().getProperty("maven.compiler.source"));
        if (value == null) {
            throw new IllegalStateException(
                    "Maven project " + projectId(project)
                    + " has no effective Java release, target, or source");
        }
        value = interpolate(project, value).trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "Maven project " + projectId(project)
                    + " has an invalid effective Java release: " + value,
                    error);
        }
    }

    private boolean previewFeatures(MavenProject project) {
        String property =
                project.getProperties().getProperty("maven.compiler.enablePreview");
        if (property != null
                && Boolean.parseBoolean(interpolate(project, property))) {
            return true;
        }
        String argument = compilerConfiguration(project, "compilerArgument");
        if (argument != null && argument.contains("--enable-preview")) {
            return true;
        }
        Plugin compiler = compilerPlugin(project);
        if (compiler != null && compiler.getConfiguration() instanceof Xpp3Dom) {
            Xpp3Dom arguments =
                    ((Xpp3Dom) compiler.getConfiguration()).getChild("compilerArgs");
            if (arguments != null) {
                for (Xpp3Dom child : arguments.getChildren()) {
                    if (child.getValue() != null
                            && child.getValue().contains("--enable-preview")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String compilerConfiguration(MavenProject project, String childName) {
        Plugin compiler = compilerPlugin(project);
        if (compiler == null || !(compiler.getConfiguration() instanceof Xpp3Dom)) {
            return null;
        }
        Xpp3Dom child =
                ((Xpp3Dom) compiler.getConfiguration()).getChild(childName);
        return child == null ? null : child.getValue();
    }

    private Plugin compilerPlugin(MavenProject project) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if ("org.apache.maven.plugins".equals(
                        nonblank(plugin.getGroupId(), "org.apache.maven.plugins"))
                    && "maven-compiler-plugin".equals(plugin.getArtifactId())) {
                return plugin;
            }
        }
        return null;
    }

    private String interpolate(MavenProject project, String value) {
        String current = value;
        for (int iteration = 0; iteration < 8; iteration++) {
            Matcher matcher = PROPERTY_REFERENCE.matcher(current);
            StringBuffer replaced = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = projectProperty(project, matcher.group(1));
                if (replacement == null) {
                    continue;
                }
                matcher.appendReplacement(replaced,
                                          Matcher.quoteReplacement(replacement));
                changed = true;
            }
            matcher.appendTail(replaced);
            current = replaced.toString();
            if (!changed) {
                return current;
            }
        }
        return current;
    }

    private String projectProperty(MavenProject project, String name) {
        Properties properties = project.getProperties();
        String value = properties.getProperty(name);
        if (value != null) {
            return value;
        }
        if ("project.basedir".equals(name) || "basedir".equals(name)) {
            return project.getBasedir().getAbsolutePath();
        }
        if ("project.build.directory".equals(name)) {
            return project.getBuild().getDirectory();
        }
        if ("project.version".equals(name)) {
            return project.getVersion();
        }
        return null;
    }

    private String firstNonblank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String projectId(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId()
                + ":" + project.getVersion();
    }

    private String artifactProjectId(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId()
                + ":" + artifact.getVersion();
    }

    private String artifactCoordinate(Artifact artifact) {
        StringBuilder coordinate = new StringBuilder();
        coordinate.append(artifact.getGroupId()).append(":")
                .append(artifact.getArtifactId()).append(":")
                .append(nonblank(artifact.getType(), "jar"));
        if (artifact.getClassifier() != null
                && !artifact.getClassifier().isEmpty()) {
            coordinate.append(":").append(artifact.getClassifier());
        }
        coordinate.append(":").append(artifact.getVersion());
        return coordinate.toString();
    }

    private String nonblank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String canonical(File file) throws IOException {
        return file.getCanonicalPath();
    }

    private void record(Set<String> records, String kind, String... fields) {
        StringBuilder line = new StringBuilder(validField(kind));
        for (String field : fields) {
            line.append('\t').append(validField(field));
        }
        records.add(line.toString());
    }

    private String validField(String value) {
        if (value == null || value.isEmpty()
                || value.indexOf('\t') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Maven discovery cannot encode manifest field: " + value);
        }
        return value;
    }
}
