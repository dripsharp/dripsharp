// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for the JDBC metadata contracts used by
// translated Java libraries. The adapters retain JDBC's cursor and one-based
// metadata API while delegating provider behavior to System.Data.Common.
#nullable enable

using System;
using System.Collections.Generic;
using System.Data;
using System.Data.Common;
using System.Globalization;

namespace DripSharp.Runtime;

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaDatabaseMetaData
{
    private readonly DbConnection connection;

    internal JavaDatabaseMetaData(DbConnection connection) =>
        this.connection = connection ?? throw new ArgumentNullException(nameof(connection));

    internal JavaResultSet GetTables(
        string? catalog,
        string? schemaPattern,
        string? tableNamePattern,
        string[]? types)
    {
        var restrictions = new[] { catalog, schemaPattern, tableNamePattern, null };
        return new JavaResultSet(connection.GetSchema("Tables", restrictions), types);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaResultSet : IDisposable
{
    private readonly DataTable table;
    private readonly HashSet<string>? tableTypes;
    private int rowIndex = -1;

    internal JavaResultSet(DataTable table, string[]? types)
    {
        this.table = table ?? throw new ArgumentNullException(nameof(table));
        tableTypes = types is { Length: > 0 }
            ? new HashSet<string>(types, StringComparer.OrdinalIgnoreCase)
            : null;
    }

    internal bool Next()
    {
        while (++rowIndex < table.Rows.Count)
        {
            if (tableTypes is null || !table.Columns.Contains("TABLE_TYPE")) return true;
            var tableType = Convert.ToString(
                table.Rows[rowIndex]["TABLE_TYPE"], CultureInfo.InvariantCulture);
            if (tableType is not null && tableTypes.Contains(tableType)) return true;
        }
        return false;
    }

    internal string GetString(string columnLabel)
    {
        ArgumentNullException.ThrowIfNull(columnLabel);
        if (rowIndex < 0 || rowIndex >= table.Rows.Count)
            throw new InvalidOperationException("The result-set cursor is not on a row.");

        var column = FindColumn(columnLabel);
        if (column is null)
            throw new IndexOutOfRangeException($"Unknown result-set column '{columnLabel}'.");
        var value = table.Rows[rowIndex][column];
        return value is null or DBNull
            ? null!
            : Convert.ToString(value, CultureInfo.InvariantCulture)!;
    }

    public void Dispose() => table.Dispose();

    private DataColumn? FindColumn(string label)
    {
        if (table.Columns.Contains(label)) return table.Columns[label];
        var aliases = label switch
        {
            "TABLE_CAT" => new[] { "TABLE_CATALOG" },
            "TABLE_SCHEM" => new[] { "TABLE_SCHEMA", "TABLE_OWNER" },
            _ => Array.Empty<string>()
        };
        foreach (var alias in aliases)
            if (table.Columns.Contains(alias)) return table.Columns[alias];
        return null;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaResultSetMetaData
{
    private readonly string[] columnLabels;

    internal JavaResultSetMetaData(DbCommand command)
    {
        ArgumentNullException.ThrowIfNull(command);
        using var reader = command.ExecuteReader(CommandBehavior.SchemaOnly);
        using var schema = reader.GetSchemaTable();
        if (schema is null)
        {
            columnLabels = Array.Empty<string>();
            return;
        }

        columnLabels = new string[schema.Rows.Count];
        for (var index = 0; index < schema.Rows.Count; index++)
            columnLabels[index] = Convert.ToString(
                schema.Rows[index]["ColumnName"], CultureInfo.InvariantCulture) ?? string.Empty;
    }

    internal int ColumnCount => columnLabels.Length;

    internal string GetColumnLabel(int column)
    {
        if (column < 1 || column > columnLabels.Length)
            throw new ArgumentOutOfRangeException(nameof(column));
        return columnLabels[column - 1];
    }
}

static partial class JavaCompat
{
    internal static DbCommand PrepareStatement(DbConnection connection, string sql)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(sql);
        var command = connection.CreateCommand();
        command.CommandText = sql;
        return command;
    }

    internal static JavaDatabaseMetaData GetDatabaseMetaData(DbConnection connection) =>
        new(connection);

    internal static JavaResultSetMetaData PreparedStatementGetMetaData(DbCommand command) =>
        new(command);
}
