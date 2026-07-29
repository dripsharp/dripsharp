using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using RawHttp.Core;
using RawHttp.Core.Body;
using RawHttp.Core.Body.Encoding;
using RawHttp.Core.Errors;
using DripSharp.Runtime;

internal static class Program
{
    private const string ObservationHeader = "DRIPSHARP_RAWHTTP_OBSERVATIONS_V1";
    private const string ProvenanceHeader = "DRIPSHARP_RAWHTTP_PACKAGE_PROVENANCE_V1";

    private static int Main()
    {
        var observations = new SortedDictionary<string, Observation>(StringComparer.Ordinal);
        var rawHttp = new RawHttp.Core.RawHttp();

        Success(observations, "body.bytes-and-string", () =>
        {
            var reader = new BytesBody(Signed(Encoding.UTF8.GetBytes("snowman=☃"))).toBodyReader();
            return reader.getLengthIfKnown()!.Value.ToString(CultureInfo.InvariantCulture) + "|" +
                reader.asRawString(Encoding.UTF8);
        });

        Success(observations, "encoding.deflate-resource", () =>
        {
            var encoded = Compress("deflate-body", stream => new ZLibStream(
                stream, CompressionLevel.Optimal, leaveOpen: true));
            var response = rawHttp.parseResponse(MessageWithBytes(
                "HTTP/1.1 200 OK\r\nContent-Encoding: deflate\r\nContent-Length: " +
                encoded.Length.ToString(CultureInfo.InvariantCulture) + "\r\n\r\n", encoded)).eagerly();
            return DecodedBody(response);
        });

        Success(observations, "encoding.gzip-resource", () =>
        {
            var encoded = Compress("gzip-body", stream => new GZipStream(
                stream, CompressionLevel.Optimal, leaveOpen: true));
            var response = rawHttp.parseResponse(MessageWithBytes(
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " +
                encoded.Length.ToString(CultureInfo.InvariantCulture) + "\r\n\r\n", encoded)).eagerly();
            return DecodedBody(response);
        });

        Success(observations, "encoding.service-loader", () =>
        {
            var registry = rawHttp.getOptions().getEncodingRegistry();
            var resolved = new List<string>();
            foreach (var name in new[] { "chunked", "deflate", "gzip", "identity" })
            {
                var decoder = registry.get(name);
                resolved.Add(name + "=" + (((IJavaOptional)decoder).HasValue
                    ? JavaTypeName(Optional(decoder).GetType()) : "missing"));
            }
            return string.Join("|", resolved);
        });

        Success(observations, "headers.case-order-and-values", () =>
        {
            var headers = RawHttpHeaders.newBuilder()
                .with("X-Trace", "one")
                .with("accept", "text/plain")
                .with("X-Trace", "two")
                .build();
            return string.Join(",", headers.getHeaderNames()) + "|" +
                string.Join(",", headers.get("x-trace")) + "|" +
                Bool(headers.contains("ACCEPT")) + "|" + headers;
        });

        Success(observations, "headers.merge-overwrite-remove", () =>
        {
            var left = RawHttpHeaders.newBuilder().with("A", "1").with("B", "2").build();
            var right = RawHttpHeaders.newBuilder().with("B", "3").with("C", "4").build();
            var result = RawHttpHeaders.newBuilder(left)
                .merge(right).overwrite("A", "5").remove("C").build();
            return SortedHeaders(result);
        });

        Success(observations, "http-version.parse-and-order", () =>
            HttpVersion.parse("HTTP/1.0") + "|" +
            Bool(HttpVersion.HTTP_1_0.isOlderThan(HttpVersion.HTTP_1_1)));

        Success(observations, "request.absolute-query", () =>
        {
            var request = rawHttp.parseRequest(
                "GET https://example.com:8443/a%20b?x=hello%20world#ignored HTTP/1.0").eagerly();
            return request.getMethod() + "|" + UriText(request.getUri()) + "|" +
                request.getStartLine().getHttpVersion() + "|" + request;
        });

        Success(observations, "request.body", () =>
        {
            var request = rawHttp.parseRequest(
                "POST /submit HTTP/1.1\r\nHost: example.test\r\n" +
                "Content-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello").eagerly();
            return request.getMethod() + "|" + UriText(request.getUri()) + "|" +
                Optional(request.getBody()).asRawString(Encoding.UTF8) + "|" +
                SortedHeaders(request.getHeaders());
        });

        Success(observations, "request.comments-option", () =>
        {
            var configured = new RawHttp.Core.RawHttp(
                RawHttpOptions.Builder.newBuilder().allowComments().build());
            var request = configured.parseRequest(
                "GET / HTTP/1.1\r\nHost: example.test\r\n# header comment\r\n" +
                "Accept: */*\r\n\r\n").eagerly();
            return UriText(request.getUri()) + "|" + SortedHeaders(request.getHeaders());
        });

        Success(observations, "request.simple", () =>
        {
            var request = rawHttp.parseRequest("GET localhost:8080").eagerly();
            return request.getMethod() + "|" + UriText(request.getUri()) + "|" +
                request.getStartLine().getHttpVersion() + "|" +
                SortedHeaders(request.getHeaders()) + "|" + request;
        });

        Success(observations, "response.chunked-trailer", () =>
        {
            var response = rawHttp.parseResponse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-End: yes\r\n\r\n").eagerly();
            return DecodedBody(response) + "|" + SortedHeaders(response.getHeaders()) + "|" +
                SortedHeaders(Optional(Optional(response.getBody()).asChunkedBodyContents()).getTrailerHeaders());
        });

        Success(observations, "response.head-has-no-body", () =>
        {
            var status = new StatusLine(HttpVersion.HTTP_1_1, 200, "OK");
            var head = new RequestLine("HEAD", new Uri("https://example.test/"), HttpVersion.HTTP_1_1);
            return Bool(RawHttp.Core.RawHttp.responseHasBody(status, head));
        });

        Success(observations, "response.simple-body", () =>
        {
            var response = rawHttp.parseResponse(
                "HTTP/1.0 201 Created\r\nServer: contract\r\nContent-Length: 5\r\n\r\nhello")
                .eagerly();
            return response.getStatusCode().ToString(CultureInfo.InvariantCulture) + "|" +
                response.getStartLine().getReason() + "|" +
                Optional(response.getBody()).asRawString(Encoding.UTF8) + "|" + response;
        });

        Success(observations, "status.redirect-family", () =>
            Bool(StatusLine.isRedirectCode(301)) + "|" + Bool(StatusLine.isRedirectCode(308)) + "|" +
            Bool(StatusLine.isRedirectCode(304)) + "|" + Bool(StatusLine.isRedirectCode(200)));

        Success(observations, "uri.builder-and-transforms", () =>
        {
            var built = UriUtil.builder().withScheme("https").withHost("example.test:8443")
                .withPath("a/b").withQuery("x=1").withFragment("frag").build();
            return UriText(built) + "|" + UriText(UriUtil.withHost(built, "other.test:9443")) + "|" +
                UriText(UriUtil.withPath(built, "/changed")) + "|" + UriUtil.concatPaths("/a/", "/b");
        });

        Failure(observations, "failure.chunk-invalid-size", () => rawHttp.parseRequest(
            "GET http://localhost\r\nTransfer-Encoding: chunked\r\n\r\nERR\r\n0\r\n\r\n").eagerly());
        Failure(observations, "failure.chunk-truncated", () => rawHttp.parseRequest(
            "GET http://localhost\r\nTransfer-Encoding: chunked\r\n\r\nA\r\nXX").eagerly());
        Failure(observations, "failure.duplicate-host", () => rawHttp.parseRequest(
            "GET /\r\nHost: one.test\r\nAccept: */*\r\nHost: two.test"));
        Failure(observations, "failure.empty-request", () => rawHttp.parseRequest(""));
        Failure(observations, "failure.invalid-header", () => rawHttp.parseRequest(
            "GET / HTTP/1.1\r\nHost: example.test\r\nBROKEN\r\n"));
        Failure(observations, "failure.invalid-http-version", () => HttpVersion.parse("HTTP/9.9"));
        Failure(observations, "failure.missing-host", () => rawHttp.parseRequest("GET / HTTP/1.1"));
        Failure(observations, "failure.multiple-content-length", () => rawHttp.parseRequest(
            "POST / HTTP/1.1\r\nHost: example.test\r\nContent-Length: 1\r\n" +
            "Content-Length: 2\r\n\r\nx"));
        Failure(observations, "failure.strict-newline", () => new RawHttp.Core.RawHttp(
            RawHttpOptions.Builder.newBuilder().doNotAllowNewLineWithoutReturn().build())
            .parseRequest("GET / HTTP/1.1\nHost: example.test\r\n"));
        Failure(observations, "failure.strict-host", () => new RawHttp.Core.RawHttp(
            RawHttpOptions.Builder.newBuilder().doNotInsertHostHeaderIfMissing().build())
            .parseRequest("GET http://example.test HTTP/1.1\r\nAccept: */*"));
        Failure(observations, "failure.unknown-content-encoding", () =>
        {
            var response = rawHttp.parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Encoding: made-up\r\nContent-Length: 1\r\n\r\nx")
                .eagerly();
            Optional(response.getBody()).decodeBody();
        });
        Failure(observations, "failure.uri-port-without-host", () =>
            UriUtil.withPort(new Uri("urn:example:value"), 80));

        Console.WriteLine(ObservationHeader);
        foreach (var pair in observations)
            Console.WriteLine(pair.Key + "\t" + pair.Value.Status + "\t" + Encode(pair.Value.Payload));

        var assembly = typeof(RawHttp.Core.RawHttp).Assembly;
        var assemblyPath = Path.GetFullPath(assembly.Location);
        Console.WriteLine(ProvenanceHeader);
        Console.WriteLine("assembly\t" + Encode(assembly.GetName().Name ?? "") + "\t" +
            Encode(assembly.GetName().Version?.ToString() ?? "") + "\t" +
            Encode(Sha256(assemblyPath)) + "\t" + Encode(assemblyPath));
        Console.WriteLine("Independent RawHttp.Core package behavior passed.");
        return 0;
    }

    private static byte[] Compress(string value, Func<Stream, Stream> compressor)
    {
        using var output = new MemoryStream();
        using (var stream = compressor(output))
            stream.Write(Encoding.UTF8.GetBytes(value));
        return output.ToArray();
    }

    private static MemoryStream MessageWithBytes(string headers, byte[] body)
    {
        var message = new MemoryStream();
        message.Write(Encoding.ASCII.GetBytes(headers));
        message.Write(body);
        message.Position = 0;
        return message;
    }

    private static string DecodedBody<TReturn>(RawHttpResponse<TReturn> response) =>
        Optional(response.getBody()).decodeBodyToString(Encoding.UTF8);

    private static string SortedHeaders(RawHttpHeaders headers) => string.Join(";",
        headers.asMap().OrderBy(pair => pair.Key, StringComparer.Ordinal)
            .Select(pair => pair.Key + "=" + string.Join(",", pair.Value)));

    private static string DescribeFailure(Exception failure)
    {
        while (failure.GetType() == typeof(Exception) && failure.InnerException is not null)
            failure = failure.InnerException;
        var message = failure is UnknownEncodingException unknown ? unknown.getMessage() : failure.Message;
        var result = JavaExceptionName(failure) + "|" + message;
        if (failure is InvalidHttpRequest request) result += "|line=" + request.getLineNumber();
        else if (failure is InvalidHttpResponse response) result += "|line=" + response.getLineNumber();
        return result;
    }

    private static string JavaExceptionName(Exception failure) => failure switch
    {
        InvalidHttpRequest => "rawhttp.core.errors.InvalidHttpRequest",
        InvalidHttpResponse => "rawhttp.core.errors.InvalidHttpResponse",
        InvalidMessageFrame => "rawhttp.core.errors.InvalidMessageFrame",
        UnknownEncodingException => "rawhttp.core.errors.UnknownEncodingException",
        ArgumentException => "java.lang.IllegalArgumentException",
        InvalidOperationException => "java.lang.IllegalStateException",
        _ => throw new InvalidOperationException(
            "The RawHTTP package probe observed an undocumented failure type: " + failure.GetType().FullName,
            failure)
    };

    private static string JavaTypeName(Type type)
    {
        const string prefix = "RawHttp.Core.";
        var name = type.FullName ?? type.Name;
        if (!name.StartsWith(prefix, StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected RawHTTP service type: " + name);
        return "rawhttp.core." + name[prefix.Length..].Replace('+', '.').ToLowerInvariantNamespace();
    }

    private static string ToLowerInvariantNamespace(this string value)
    {
        var parts = value.Split('.');
        if (parts.Length < 2) return value;
        for (var index = 0; index < parts.Length - 1; index++)
            parts[index] = parts[index].ToLowerInvariant();
        return string.Join('.', parts);
    }

    private static string UriText(Uri uri) => uri.OriginalString;
    private static T Optional<T>(JavaOptional<T> value)
    {
        var optional = (IJavaOptional)value;
        if (!optional.HasValue) throw new InvalidOperationException("Optional is empty");
        return (T)optional.BoxedValue!;
    }
    private static string Bool(bool value) => value ? "true" : "false";
    private static sbyte[] Signed(byte[] bytes) => Array.ConvertAll(bytes, value => unchecked((sbyte)value));
    private static string Encode(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
    private static string Sha256(string path) =>
        Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant();

    private static void Success(
        IDictionary<string, Observation> target,
        string id,
        Func<string> action)
    {
        try { Put(target, id, new Observation("SUCCESS", action())); }
        catch (Exception failure)
        {
            throw new InvalidOperationException("Expected successful RawHTTP observation " + id, failure);
        }
    }

    private static void Failure(
        IDictionary<string, Observation> target,
        string id,
        Action action)
    {
        try
        {
            action();
            throw new InvalidOperationException("Expected deterministic RawHTTP failure " + id);
        }
        catch (InvalidOperationException error)
            when (error.Message.StartsWith("Expected deterministic", StringComparison.Ordinal))
        {
            throw;
        }
        catch (Exception actual) { Put(target, id, new Observation("FAILURE", DescribeFailure(actual))); }
    }

    private static void Put(IDictionary<string, Observation> target, string id, Observation observation)
    {
        if (id.Contains('\t') || id.Contains('\n') ||
            target.ContainsKey(id))
            throw new InvalidOperationException("Invalid or duplicate observation identity: " + id);
        target.Add(id, observation);
    }

    private sealed record Observation(string Status, string Payload);
}
