// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Xml

internal static class JavaXPathConstants
{
    internal static readonly XmlQualifiedName NODE = new("NODE");
    internal static readonly XmlQualifiedName NODESET = new("NODESET");
}

internal sealed class JavaXPathFactory
{
    internal static readonly JavaXPathFactory Instance = new();
    internal JavaXPath NewXPath() => new();
}

internal sealed class JavaXPath
{
    internal string Evaluate(string expression, object context)
    {
        ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as XmlNode
            ?? throw new ArgumentException("XPath context must be an XML node.", nameof(context));
        return node.SelectSingleNode(expression)?.InnerText ?? string.Empty;
    }

    internal object? Evaluate(string expression, object context, XmlQualifiedName returnType)
    {
        ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as XmlNode
            ?? throw new ArgumentException("XPath context must be an XML node.", nameof(context));
        if (returnType == JavaXPathConstants.NODESET) return node.SelectNodes(expression);
        if (returnType == JavaXPathConstants.NODE) return node.SelectSingleNode(expression);
        return Evaluate(expression, context);
    }
}


internal static partial class JavaCompat
{
    private sealed class XmlQualifiedNameMetadata
    {
        internal string Prefix = "";
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Xml.XmlQualifiedName, XmlQualifiedNameMetadata> XmlQualifiedNameMetadataTable = new();
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(string localName) =>
        NewXmlQualifiedName("", localName, "");
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(string namespaceUri, string localName) =>
        NewXmlQualifiedName(namespaceUri, localName, "");
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(
        string namespaceUri,
        string localName,
        string prefix)
    {
        var name = new System.Xml.XmlQualifiedName(localName, namespaceUri);
        XmlQualifiedNameMetadataTable.Add(name, new XmlQualifiedNameMetadata { Prefix = prefix });
        return name;
    }
    internal static string XmlQualifiedNamePrefix(System.Xml.XmlQualifiedName name) =>
        XmlQualifiedNameMetadataTable.TryGetValue(name, out var metadata) ? metadata.Prefix : "";
    internal static string? XmlNodePrefix(System.Xml.XmlNode node) =>
        string.IsNullOrEmpty(node.Prefix) ? null : node.Prefix;
    internal static string? XmlNodeNamespaceUri(System.Xml.XmlNode node) =>
        string.IsNullOrEmpty(node.NamespaceURI) ? null : node.NamespaceURI;
    internal static System.Xml.XmlAttribute XmlAttributeItem(
        System.Xml.XmlAttributeCollection attributes,
        int index) =>
        attributes
            .Cast<System.Xml.XmlAttribute>()
            .OrderBy(attribute => attribute.Name, StringComparer.Ordinal)
            .ElementAtOrDefault(index)!;
    internal static System.Xml.XmlReaderSettings NewXmlReaderSettings() =>
        new()
        {
            DtdProcessing = System.Xml.DtdProcessing.Prohibit,
            XmlResolver = null,
            CloseInput = true
        };
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Xml.XmlReaderSettings,
        System.Runtime.CompilerServices.StrongBox<bool>> XmlReaderBehaviors = new();
    private static System.Runtime.CompilerServices.StrongBox<bool>
        GetXmlReaderBehavior(
        System.Xml.XmlReaderSettings settings) =>
        XmlReaderBehaviors.GetValue(
            settings,
            _ => new System.Runtime.CompilerServices.StrongBox<bool>(true));
    internal static System.Xml.XmlReaderSettings XmlReaderSettingsClone(
        System.Xml.XmlReaderSettings settings)
    {
        var clone = settings.Clone();
        GetXmlReaderBehavior(clone).Value =
            GetXmlReaderBehavior(settings).Value;
        return clone;
    }
    internal static void XmlReaderSetFeature(
        System.Xml.XmlReaderSettings settings,
        string feature,
        bool enabled)
    {
        switch (feature)
        {
            case "http://apache.org/xml/features/disallow-doctype-decl":
                settings.DtdProcessing = enabled
                    ? System.Xml.DtdProcessing.Prohibit
                    : System.Xml.DtdProcessing.Parse;
                break;
            case "http://xml.org/sax/features/external-general-entities":
            case "http://xml.org/sax/features/external-parameter-entities":
            case "http://apache.org/xml/features/nonvalidating/load-external-dtd":
                if (enabled)
                    throw new System.Xml.XmlException(
                        $"External XML feature '{feature}' is not supported.");
                settings.XmlResolver = null;
                break;
            default:
                throw new System.Xml.XmlException($"Unknown XML feature '{feature}'.");
        }
    }
    internal static void XmlReaderSetXIncludeAware(
        System.Xml.XmlReaderSettings settings,
        bool enabled)
    {
        _ = settings;
        if (enabled) throw new System.Xml.XmlException("XInclude is not supported.");
    }
    internal static void XmlReaderSetExpandEntityReferences(
        System.Xml.XmlReaderSettings settings,
        bool enabled)
    {
        _ = settings;
        if (enabled)
            throw new System.Xml.XmlException(
                "Entity-reference expansion requires an enabled DTD.");
    }
    internal static void XmlReaderSetNamespaceAware(
        System.Xml.XmlReaderSettings settings,
        bool enabled) =>
        GetXmlReaderBehavior(settings).Value = enabled;
    internal static void XmlSetErrorHandler(
        System.Xml.XmlReaderSettings settings,
        object? errorHandler)
    {
        _ = settings;
        _ = errorHandler;
    }
    internal static System.Xml.XmlDocument XmlParse(
        System.Xml.XmlReaderSettings settings,
        Stream input)
    {
        using var reader =
            GetXmlReaderBehavior(settings).Value
                ? System.Xml.XmlReader.Create(input, settings)
                : CreateNamespaceUnawareXmlReader(settings, input);
        var document = new System.Xml.XmlDocument { PreserveWhitespace = true };
        document.Load(reader);
        if (document.FirstChild is System.Xml.XmlDeclaration declaration)
            document.RemoveChild(declaration);
        NormalizeJavaDomWhitespace(document, document);
        return document;
    }
    private static System.Xml.XmlReader CreateNamespaceUnawareXmlReader(
        System.Xml.XmlReaderSettings settings,
        Stream input) =>
        new System.Xml.XmlTextReader(input)
        {
            Namespaces = false,
            DtdProcessing = settings.DtdProcessing,
            XmlResolver = null,
            WhitespaceHandling = settings.IgnoreWhitespace
                ? System.Xml.WhitespaceHandling.None
                : System.Xml.WhitespaceHandling.All
        };
    private static void NormalizeJavaDomWhitespace(
        System.Xml.XmlDocument document,
        System.Xml.XmlNode parent)
    {
        foreach (System.Xml.XmlNode child in parent.ChildNodes.Cast<System.Xml.XmlNode>().ToArray())
        {
            if (child.NodeType is System.Xml.XmlNodeType.Whitespace
                or System.Xml.XmlNodeType.SignificantWhitespace)
            {
                if (parent is System.Xml.XmlDocument)
                    parent.RemoveChild(child);
                else
                    parent.ReplaceChild(
                        document.CreateTextNode(child.Value ?? ""),
                        child);
            }
            else
            {
                NormalizeJavaDomWhitespace(document, child);
            }
        }
    }
    internal static System.Xml.XmlWriterSettings XmlWriterSettingsClone(
        System.Xml.XmlWriterSettings settings) =>
        settings.Clone();
    internal static void XmlSetOutputProperty(
        System.Xml.XmlWriterSettings settings,
        string name,
        string value)
    {
        switch (name)
        {
            case "indent":
                settings.Indent = string.Equals(value, "yes", StringComparison.OrdinalIgnoreCase);
                break;
            case "{http://xml.apache.org/xslt}indent-amount":
                settings.IndentChars = new string(' ', ParseInt(value, 10));
                break;
            case "encoding":
                settings.Encoding =
                    string.Equals(value, "UTF-8", StringComparison.OrdinalIgnoreCase)
                        ? new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)
                        : Encoding.GetEncoding(value);
                break;
            case "omit-xml-declaration":
                settings.OmitXmlDeclaration =
                    string.Equals(value, "yes", StringComparison.OrdinalIgnoreCase);
                break;
            default:
                throw new ArgumentException($"Unsupported XML output property '{name}'.", nameof(name));
        }
    }
    internal static void XmlTransform(
        System.Xml.XmlWriterSettings settings,
        System.Xml.XmlNode source,
        Stream result)
    {
        settings.CloseOutput = false;
        using var writer = System.Xml.XmlWriter.Create(result, settings);
        WriteXmlTransform(settings, source, writer);
    }
    internal static void XmlTransform(
        System.Xml.XmlWriterSettings settings,
        System.Xml.XmlNode source,
        TextWriter result)
    {
        settings.CloseOutput = false;
        using var writer = System.Xml.XmlWriter.Create(result, settings);
        WriteXmlTransform(settings, source, writer);
    }
    private static void WriteXmlTransform(
        System.Xml.XmlWriterSettings settings,
        System.Xml.XmlNode source,
        System.Xml.XmlWriter writer)
    {
        var namespaces = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["xml"] = "http://www.w3.org/XML/1998/namespace",
            ["xmlns"] = "http://www.w3.org/2000/xmlns/"
        };
        WriteJavaDomNode(writer, source, namespaces);
        writer.WriteWhitespace(settings.NewLineChars);
        writer.Flush();
    }
    private static void WriteJavaDomNode(
        System.Xml.XmlWriter writer,
        System.Xml.XmlNode node,
        IReadOnlyDictionary<string, string> inheritedNamespaces)
    {
        switch (node.NodeType)
        {
            case System.Xml.XmlNodeType.Document:
                foreach (System.Xml.XmlNode child in node.ChildNodes)
                {
                    if (child is System.Xml.XmlProcessingInstruction instruction)
                    {
                        writer.WriteRaw(
                            $"<?{instruction.Name} {instruction.Value}?>");
                    }
                    else
                    {
                        WriteJavaDomNode(writer, child, inheritedNamespaces);
                    }
                }
                return;
            case System.Xml.XmlNodeType.Element:
                var element = (System.Xml.XmlElement)node;
                var namespaces =
                    new Dictionary<string, string>(inheritedNamespaces, StringComparer.Ordinal);
                if (!string.IsNullOrEmpty(element.NamespaceURI))
                    namespaces[element.Prefix] = element.NamespaceURI;
                var orderedAttributes =
                    element.Attributes
                        .Cast<System.Xml.XmlAttribute>()
                        .Where(
                            attribute =>
                                string.Equals(
                                    attribute.Name,
                                    "xmlns",
                                    StringComparison.Ordinal) ||
                                string.Equals(
                                    attribute.Prefix,
                                    "xmlns",
                                    StringComparison.Ordinal))
                        .OrderBy(
                            attribute => attribute.LocalName,
                            StringComparer.Ordinal)
                        .Concat(
                            element.Attributes
                                .Cast<System.Xml.XmlAttribute>()
                                .Where(
                                    attribute =>
                                        !string.Equals(
                                            attribute.Name,
                                            "xmlns",
                                            StringComparison.Ordinal) &&
                                        !string.Equals(
                                            attribute.Prefix,
                                            "xmlns",
                                            StringComparison.Ordinal)));
                foreach (System.Xml.XmlAttribute attribute in orderedAttributes)
                {
                    if (string.Equals(attribute.Name, "xmlns", StringComparison.Ordinal))
                        namespaces[""] = attribute.Value;
                    else if (string.Equals(attribute.Prefix, "xmlns", StringComparison.Ordinal))
                        namespaces[attribute.LocalName] = attribute.Value;
                    else if (!string.IsNullOrEmpty(attribute.Prefix) &&
                             !string.IsNullOrEmpty(attribute.NamespaceURI))
                        namespaces[attribute.Prefix] = attribute.NamespaceURI;
                }
                var elementNamespace =
                    ResolveJavaDomNamespace(element.Prefix, element.NamespaceURI, namespaces);
                writer.WriteStartElement(element.Prefix, element.LocalName, elementNamespace);
                foreach (System.Xml.XmlAttribute attribute in orderedAttributes)
                {
                    if (string.Equals(attribute.Name, "xmlns", StringComparison.Ordinal))
                    {
                        if (!inheritedNamespaces.TryGetValue("", out var inheritedDefault) ||
                            !string.Equals(
                                inheritedDefault,
                                attribute.Value,
                                StringComparison.Ordinal))
                        {
                            writer.WriteAttributeString("xmlns", attribute.Value);
                        }
                    }
                    else if (string.Equals(attribute.Prefix, "xmlns", StringComparison.Ordinal))
                    {
                        if (!inheritedNamespaces.TryGetValue(
                                attribute.LocalName,
                                out var inheritedNamespace) ||
                            !string.Equals(
                                inheritedNamespace,
                                attribute.Value,
                                StringComparison.Ordinal))
                        {
                            writer.WriteAttributeString(
                                "xmlns", attribute.LocalName, null, attribute.Value);
                        }
                    }
                    else
                    {
                        var attributeNamespace =
                            ResolveJavaDomNamespace(
                                attribute.Prefix, attribute.NamespaceURI, namespaces);
                        writer.WriteAttributeString(
                            attribute.Prefix,
                            attribute.LocalName,
                            attributeNamespace,
                            attribute.Value);
                    }
                }
                foreach (System.Xml.XmlNode child in element.ChildNodes)
                    WriteJavaDomNode(writer, child, namespaces);
                writer.WriteEndElement();
                return;
            case System.Xml.XmlNodeType.Text:
                writer.WriteString(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.CDATA:
                writer.WriteCData(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.Whitespace:
            case System.Xml.XmlNodeType.SignificantWhitespace:
                writer.WriteWhitespace(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.Comment:
                writer.WriteComment(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.ProcessingInstruction:
                writer.WriteProcessingInstruction(node.Name, node.Value);
                return;
            case System.Xml.XmlNodeType.XmlDeclaration:
                return;
            default:
                throw new System.Xml.XmlException(
                    $"Unsupported Java DOM node type '{node.NodeType}'.");
        }
    }
    private static string ResolveJavaDomNamespace(
        string prefix,
        string namespaceUri,
        IReadOnlyDictionary<string, string> namespaces)
    {
        if (string.IsNullOrEmpty(prefix) || !string.IsNullOrEmpty(namespaceUri))
            return namespaceUri;
        return namespaces.TryGetValue(prefix, out var resolved)
            ? resolved
            : throw new System.Xml.XmlException(
                $"XML prefix '{prefix}' has no in-scope namespace declaration.");
    }
    private static (string Prefix, string LocalName) SplitXmlQualifiedName(string qualifiedName)
    {
        var separator = qualifiedName.IndexOf(':');
        return separator < 0
            ? ("", qualifiedName)
            : (qualifiedName[..separator], qualifiedName[(separator + 1)..]);
    }
    internal static System.Xml.XmlElement XmlCreateElementNs(
        System.Xml.XmlDocument document,
        string namespaceUri,
        string qualifiedName)
    {
        var (prefix, localName) = SplitXmlQualifiedName(qualifiedName);
        return document.CreateElement(prefix, localName, namespaceUri);
    }
    internal static void XmlSetAttributeNs(
        System.Xml.XmlElement element,
        string namespaceUri,
        string qualifiedName,
        string value)
    {
        var (prefix, localName) = SplitXmlQualifiedName(qualifiedName);
        var attribute = element.OwnerDocument.CreateAttribute(prefix, localName, namespaceUri);
        attribute.Value = value;
        element.Attributes.SetNamedItem(attribute);
    }
    internal static string? XmlEncoding(XmlDocument document) =>
        document.FirstChild is XmlDeclaration declaration &&
        !string.IsNullOrEmpty(declaration.Encoding)
            ? declaration.Encoding
            : null;

    internal static string? XmlInputEncoding(XmlDocument document) =>
        XmlEncoding(document);

}
