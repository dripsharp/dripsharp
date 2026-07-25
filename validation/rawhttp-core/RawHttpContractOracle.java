import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import rawhttp.core.HttpVersion;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpOptions;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.RequestLine;
import rawhttp.core.StatusLine;
import rawhttp.core.UriUtil;
import rawhttp.core.body.BodyReader;
import rawhttp.core.body.BytesBody;
import rawhttp.core.body.encoding.HttpBodyEncodingRegistry;
import rawhttp.core.errors.InvalidHttpRequest;
import rawhttp.core.errors.InvalidHttpResponse;

/**
 * Independently executes the pinned RawHTTP Java artifact. The oracle uses only
 * RawHTTP's public API and Java reflection over Gradle's complete compiled main
 * output. Output intentionally excludes paths, clocks, ports, stack traces,
 * locale-sensitive formatting, and platform line endings.
 */
public final class RawHttpContractOracle {
  private static final String OBSERVATION_HEADER = "DRIPSHARP_RAWHTTP_OBSERVATIONS_V1";
  private static final String SURFACE_HEADER = "DRIPSHARP_JAVA_LIBRARY_PUBLIC_SURFACE_V1";
  private static final Base64.Encoder BASE64 = Base64.getEncoder();

  private RawHttpContractOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Usage: RawHttpContractOracle <classes-directory> <observations.tsv> <surface.tsv>");
    }
    Path classesDirectory = Paths.get(args[0]).toAbsolutePath().normalize();
    writeObservations(Paths.get(args[1]));
    writeSurface(classesDirectory, Paths.get(args[2]));
  }

  private static void writeObservations(Path output) throws Exception {
    Map<String, Observation> observations = new TreeMap<>();
    RawHttp rawHttp = new RawHttp();

    success(observations, "body.bytes-and-string", () -> {
      BodyReader reader = new BytesBody("snowman=☃".getBytes(StandardCharsets.UTF_8)).toBodyReader();
      return reader.getLengthIfKnown().getAsLong() + "|" + reader.asRawString(StandardCharsets.UTF_8);
    });

    success(observations, "encoding.deflate-resource", () -> {
      byte[] payload = "deflate-body".getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      try (DeflaterOutputStream stream = new DeflaterOutputStream(encoded)) {
        stream.write(payload);
      }
      RawHttpResponse<Void> response = rawHttp.parseResponse(messageWithBytes(
          "HTTP/1.1 200 OK\r\nContent-Encoding: deflate\r\nContent-Length: "
              + encoded.size() + "\r\n\r\n",
          encoded.toByteArray())).eagerly();
      return decodedBody(response);
    });

    success(observations, "encoding.gzip-resource", () -> {
      byte[] payload = "gzip-body".getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      try (GZIPOutputStream stream = new GZIPOutputStream(encoded)) {
        stream.write(payload);
      }
      RawHttpResponse<Void> response = rawHttp.parseResponse(messageWithBytes(
          "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
              + encoded.size() + "\r\n\r\n",
          encoded.toByteArray())).eagerly();
      return decodedBody(response);
    });

    success(observations, "encoding.service-loader", () -> {
      HttpBodyEncodingRegistry registry = rawHttp.getOptions().getEncodingRegistry();
      List<String> resolved = new ArrayList<>();
      for (String name : Arrays.asList("chunked", "deflate", "gzip", "identity")) {
        Optional<?> decoder = registry.get(name);
        resolved.add(name + "=" + (decoder.isPresent()
            ? decoder.get().getClass().getName() : "missing"));
      }
      return String.join("|", resolved);
    });

    success(observations, "headers.case-order-and-values", () -> {
      RawHttpHeaders headers = RawHttpHeaders.newBuilder()
          .with("X-Trace", "one")
          .with("accept", "text/plain")
          .with("X-Trace", "two")
          .build();
      return String.join(",", headers.getHeaderNames()) + "|"
          + String.join(",", headers.get("x-trace")) + "|"
          + headers.contains("ACCEPT") + "|" + headers.toString();
    });

    success(observations, "headers.merge-overwrite-remove", () -> {
      RawHttpHeaders left = RawHttpHeaders.newBuilder()
          .with("A", "1").with("B", "2").build();
      RawHttpHeaders right = RawHttpHeaders.newBuilder()
          .with("B", "3").with("C", "4").build();
      RawHttpHeaders result = RawHttpHeaders.newBuilder(left)
          .merge(right).overwrite("A", "5").remove("C").build();
      return sortedHeaders(result);
    });

    success(observations, "http-version.parse-and-order", () ->
        HttpVersion.parse("HTTP/1.0") + "|"
            + HttpVersion.HTTP_1_0.isOlderThan(HttpVersion.HTTP_1_1));

    success(observations, "request.absolute-query", () -> {
      RawHttpRequest request = rawHttp.parseRequest(
          "GET https://example.com:8443/a%20b?x=hello%20world#ignored HTTP/1.0").eagerly();
      return request.getMethod() + "|" + request.getUri() + "|"
          + request.getStartLine().getHttpVersion() + "|" + request.toString();
    });

    success(observations, "request.body", () -> {
      RawHttpRequest request = rawHttp.parseRequest(
          "POST /submit HTTP/1.1\r\nHost: example.test\r\n"
              + "Content-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello").eagerly();
      return request.getMethod() + "|" + request.getUri() + "|"
          + request.getBody().get().asRawString(StandardCharsets.UTF_8) + "|"
          + sortedHeaders(request.getHeaders());
    });

    success(observations, "request.comments-option", () -> {
      RawHttp configured = new RawHttp(RawHttpOptions.Builder.newBuilder()
          .allowComments().build());
      RawHttpRequest request = configured.parseRequest(
          "GET / HTTP/1.1\r\nHost: example.test\r\n# header comment\r\n"
              + "Accept: */*\r\n\r\n").eagerly();
      return request.getUri() + "|" + sortedHeaders(request.getHeaders());
    });

    success(observations, "request.simple", () -> {
      RawHttpRequest request = rawHttp.parseRequest("GET localhost:8080").eagerly();
      return request.getMethod() + "|" + request.getUri() + "|"
          + request.getStartLine().getHttpVersion() + "|"
          + sortedHeaders(request.getHeaders()) + "|" + request.toString();
    });

    success(observations, "response.chunked-trailer", () -> {
      RawHttpResponse<Void> response = rawHttp.parseResponse(
          "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
              + "4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-End: yes\r\n\r\n").eagerly();
      return decodedBody(response) + "|"
          + sortedHeaders(response.getHeaders()) + "|"
          + sortedHeaders(response.getBody().get().asChunkedBodyContents().get().getTrailerHeaders());
    });

    success(observations, "response.head-has-no-body", () -> {
      StatusLine status = new StatusLine(HttpVersion.HTTP_1_1, 200, "OK");
      RequestLine head = new RequestLine("HEAD", URI.create("https://example.test/"),
          HttpVersion.HTTP_1_1);
      return Boolean.toString(RawHttp.responseHasBody(status, head));
    });

    success(observations, "response.simple-body", () -> {
      RawHttpResponse<Void> response = rawHttp.parseResponse(
          "HTTP/1.0 201 Created\r\nServer: contract\r\nContent-Length: 5\r\n\r\nhello")
          .eagerly();
      return response.getStatusCode() + "|" + response.getStartLine().getReason() + "|"
          + response.getBody().get().asRawString(StandardCharsets.UTF_8) + "|"
          + response.toString();
    });

    success(observations, "status.redirect-family", () ->
        StatusLine.isRedirectCode(301) + "|" + StatusLine.isRedirectCode(308) + "|"
            + StatusLine.isRedirectCode(304) + "|" + StatusLine.isRedirectCode(200));

    success(observations, "uri.builder-and-transforms", () -> {
      URI built = UriUtil.builder().withScheme("https").withHost("example.test:8443")
          .withPath("a/b").withQuery("x=1").withFragment("frag").build();
      return built + "|" + UriUtil.withHost(built, "other.test:9443") + "|"
          + UriUtil.withPath(built, "/changed") + "|" + UriUtil.concatPaths("/a/", "/b");
    });

    failure(observations, "failure.chunk-invalid-size", () -> rawHttp.parseRequest(
        "GET http://localhost\r\nTransfer-Encoding: chunked\r\n\r\nERR\r\n0\r\n\r\n")
        .eagerly());
    failure(observations, "failure.chunk-truncated", () -> rawHttp.parseRequest(
        "GET http://localhost\r\nTransfer-Encoding: chunked\r\n\r\nA\r\nXX")
        .eagerly());
    failure(observations, "failure.duplicate-host", () -> rawHttp.parseRequest(
        "GET /\r\nHost: one.test\r\nAccept: */*\r\nHost: two.test"));
    failure(observations, "failure.empty-request", () -> rawHttp.parseRequest(""));
    failure(observations, "failure.invalid-header", () -> rawHttp.parseRequest(
        "GET / HTTP/1.1\r\nHost: example.test\r\nBROKEN\r\n"));
    failure(observations, "failure.invalid-http-version", () -> HttpVersion.parse("HTTP/9.9"));
    failure(observations, "failure.missing-host", () -> rawHttp.parseRequest("GET / HTTP/1.1"));
    failure(observations, "failure.multiple-content-length", () -> rawHttp.parseRequest(
        "POST / HTTP/1.1\r\nHost: example.test\r\nContent-Length: 1\r\n"
            + "Content-Length: 2\r\n\r\nx"));
    failure(observations, "failure.strict-newline", () -> new RawHttp(
        RawHttpOptions.Builder.newBuilder().doNotAllowNewLineWithoutReturn().build())
        .parseRequest("GET / HTTP/1.1\nHost: example.test\r\n"));
    failure(observations, "failure.strict-host", () -> new RawHttp(
        RawHttpOptions.Builder.newBuilder().doNotInsertHostHeaderIfMissing().build())
        .parseRequest("GET http://example.test HTTP/1.1\r\nAccept: */*"));
    failure(observations, "failure.unknown-content-encoding", () -> rawHttp.parseResponse(
        "HTTP/1.1 200 OK\r\nContent-Encoding: made-up\r\nContent-Length: 1\r\n\r\nx")
        .eagerly().getBody().get().decodeBody());
    failure(observations, "failure.uri-port-without-host", () ->
        UriUtil.withPort(URI.create("urn:example:value"), 80));

    List<String> lines = new ArrayList<>();
    lines.add(OBSERVATION_HEADER);
    observations.forEach((id, observation) -> lines.add(
        id + "\t" + observation.status + "\t" + encode(observation.payload)));
    writeUtf8Lines(output, lines);
  }

  private static void writeSurface(Path classesDirectory, Path output) throws Exception {
    List<String> classNames = discoverClassNames(classesDirectory);
    List<String> signatures = new ArrayList<>();
    ClassLoader loader = RawHttpContractOracle.class.getClassLoader();
    for (String className : classNames) {
      Class<?> type = Class.forName(className, false, loader);
      if (!isAccessibleType(type)) continue;
      signatures.add(renderType(type));
      for (Field field : type.getDeclaredFields()) {
        if (isSurfaceMember(field) && !field.isSynthetic()) signatures.add(renderField(field));
      }
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        if (isSurfaceMember(constructor) && !constructor.isSynthetic()) {
          signatures.add(renderConstructor(constructor));
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        if (isSurfaceMember(method) && !method.isSynthetic() && !method.isBridge()) {
          signatures.add(renderMethod(method));
        }
      }
    }
    Collections.sort(signatures);
    List<String> lines = new ArrayList<>();
    lines.add(SURFACE_HEADER);
    for (String signature : signatures) lines.add("surface\t" + encode(signature));
    writeUtf8Lines(output, lines);
  }

  private static List<String> discoverClassNames(Path classesDirectory) throws IOException {
    List<String> names = new ArrayList<>();
    Files.walkFileTree(classesDirectory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        String relative = classesDirectory.relativize(file).toString().replace('\\', '/');
        if (relative.endsWith(".class") && !relative.equals("module-info.class")) {
          names.add(relative.substring(0, relative.length() - 6).replace('/', '.'));
        }
        return FileVisitResult.CONTINUE;
      }
    });
    Collections.sort(names);
    return names;
  }

  private static boolean isAccessibleType(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      int modifiers = current.getModifiers();
      if (!(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))) return false;
    }
    return true;
  }

  private static boolean isSurfaceMember(Member member) {
    int modifiers = member.getModifiers();
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static String renderType(Class<?> type) {
    String kind = type.isAnnotation() ? "annotation"
        : type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
    List<String> interfaces = new ArrayList<>();
    for (Type item : type.getGenericInterfaces()) interfaces.add(typeName(item));
    Collections.sort(interfaces);
    Type parent = type.getGenericSuperclass();
    return "type|" + kind + "|" + Modifier.toString(type.getModifiers()) + "|"
        + type.getName().replace('$', '.') + "|type-parameters="
        + renderTypeVariables(type.getTypeParameters()) + "|extends="
        + (parent == null ? "" : typeName(parent)) + "|implements=" + String.join(",", interfaces);
  }

  private static String renderField(Field field) {
    return "field|" + field.getDeclaringClass().getName().replace('$', '.') + "|"
        + Modifier.toString(field.getModifiers()) + "|" + typeName(field.getGenericType())
        + "|" + field.getName();
  }

  private static String renderConstructor(Constructor<?> constructor) {
    return "constructor|" + constructor.getDeclaringClass().getName().replace('$', '.') + "|"
        + Modifier.toString(constructor.getModifiers()) + "|type-parameters="
        + renderTypeVariables(constructor.getTypeParameters()) + "|parameters="
        + renderTypes(constructor.getGenericParameterTypes()) + "|throws="
        + renderSortedTypes(constructor.getGenericExceptionTypes());
  }

  private static String renderMethod(Method method) {
    return "method|" + method.getDeclaringClass().getName().replace('$', '.') + "|"
        + Modifier.toString(method.getModifiers()) + "|type-parameters="
        + renderTypeVariables(method.getTypeParameters()) + "|" + typeName(method.getGenericReturnType())
        + "|" + method.getName() + "|parameters=" + renderTypes(method.getGenericParameterTypes())
        + "|throws=" + renderSortedTypes(method.getGenericExceptionTypes());
  }

  private static String renderTypeVariables(TypeVariable<?>[] variables) {
    List<String> rendered = new ArrayList<>();
    for (TypeVariable<?> variable : variables) {
      rendered.add(variable.getName() + ":" + renderTypes(variable.getBounds()));
    }
    return String.join(",", rendered);
  }

  private static String renderTypes(Type[] types) {
    List<String> result = new ArrayList<>();
    for (Type type : types) result.add(typeName(type));
    return String.join(",", result);
  }

  private static String renderSortedTypes(Type[] types) {
    List<String> result = new ArrayList<>();
    for (Type type : types) result.add(typeName(type));
    Collections.sort(result);
    return String.join(",", result);
  }

  private static String typeName(Type type) {
    if (type instanceof Class<?>) return ((Class<?>) type).getName().replace('$', '.');
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) type;
      return typeName(parameterized.getRawType()) + "<" + renderTypes(parameterized.getActualTypeArguments()) + ">";
    }
    if (type instanceof GenericArrayType) {
      return typeName(((GenericArrayType) type).getGenericComponentType()) + "[]";
    }
    if (type instanceof TypeVariable<?>) return ((TypeVariable<?>) type).getName();
    if (type instanceof WildcardType) {
      WildcardType wildcard = (WildcardType) type;
      if (wildcard.getLowerBounds().length > 0) {
        return "? super " + renderTypes(wildcard.getLowerBounds());
      }
      Type[] upper = wildcard.getUpperBounds();
      return upper.length == 0 || upper[0] == Object.class
          ? "?" : "? extends " + renderTypes(upper);
    }
    return type.getTypeName().replace('$', '.');
  }

  private static String decodedBody(RawHttpResponse<?> response) throws IOException {
    return response.getBody().get().decodeBodyToString(StandardCharsets.UTF_8);
  }

  private static ByteArrayInputStream messageWithBytes(String headers, byte[] body) throws IOException {
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    message.write(headers.getBytes(StandardCharsets.US_ASCII));
    message.write(body);
    return new ByteArrayInputStream(message.toByteArray());
  }

  private static String sortedHeaders(RawHttpHeaders headers) {
    Map<String, List<String>> sorted = new TreeMap<>(headers.asMap());
    List<String> values = new ArrayList<>();
    sorted.forEach((name, entries) -> values.add(name + "=" + String.join(",", entries)));
    return String.join(";", values);
  }

  private static void success(Map<String, Observation> target, String id, ThrowingSupplier action) {
    try {
      put(target, id, new Observation("SUCCESS", action.get()));
    } catch (Throwable failure) {
      throw new AssertionError("Expected successful RawHTTP observation " + id, failure);
    }
  }

  private static void failure(Map<String, Observation> target, String id, ThrowingAction action) {
    try {
      action.run();
      throw new AssertionError("Expected deterministic RawHTTP failure " + id);
    } catch (AssertionError error) {
      throw error;
    } catch (Throwable actual) {
      put(target, id, new Observation("FAILURE", describeFailure(actual)));
    }
  }

  private static String describeFailure(Throwable failure) {
    StringBuilder result = new StringBuilder(failure.getClass().getName())
        .append('|').append(String.valueOf(failure.getMessage()));
    if (failure instanceof InvalidHttpRequest) {
      result.append("|line=").append(((InvalidHttpRequest) failure).getLineNumber());
    } else if (failure instanceof InvalidHttpResponse) {
      result.append("|line=").append(((InvalidHttpResponse) failure).getLineNumber());
    }
    return result.toString();
  }

  private static void put(Map<String, Observation> target, String id, Observation observation) {
    if (id.indexOf('\t') >= 0 || id.indexOf('\n') >= 0 || target.put(id, observation) != null) {
      throw new IllegalStateException("Invalid or duplicate observation identity: " + id);
    }
  }

  private static String encode(String value) {
    return BASE64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeUtf8Lines(Path output, List<String> lines) throws IOException {
    Path absolute = output.toAbsolutePath().normalize();
    Files.createDirectories(absolute.getParent());
    Files.write(absolute, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private interface ThrowingSupplier {
    String get() throws Exception;
  }

  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final class Observation {
    private final String status;
    private final String payload;

    private Observation(String status, String payload) {
      this.status = status;
      this.payload = payload;
    }
  }
}
