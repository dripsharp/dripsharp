// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Collections;
using System.Linq;
using DripSharp.SqlTrellis.Expression;
using DripSharp.SqlTrellis.Expression.Operators.Relational;

namespace DripSharp.SqlTrellis.Util.Validation
{
    /// <summary>
    /// CLR contract for Java's heterogeneous <c>Validator&lt;?&gt;</c> values.
    /// </summary>
    public interface IValidator
    {
        IDictionary<ValidationCapability, ISet<ValidationException>> getValidationErrors();
        IDictionary<ValidationCapability, ISet<ValidationException>> getValidationErrors(
            params ValidationCapability[] capabilities);
        IDictionary<ValidationCapability, ISet<ValidationException>> getValidationErrors(
            ICollection<ValidationCapability> capabilities);
        void setContext(ValidationContext context);
    }

    /// <summary>
    /// CLR contract for heterogeneous <c>AbstractValidator&lt;?&gt;</c> values.
    /// </summary>
    public interface IAbstractValidator : IValidator
    {
    }
}

namespace DripSharp.SqlTrellis
{
    internal static class SqlTrellisGenericCompatibility
    {
        internal static ExpressionList<T> CastExpressionList<T>(
            IEnumerable values)
            where T : global::DripSharp.SqlTrellis.Expression.Expression
        {
            if (values is null) return null!;
            if (values is ExpressionList<T> typed) return typed;
            var converted = values.Cast<T>().ToList();
            return values.GetType().IsGenericType
                && values.GetType().GetGenericTypeDefinition()
                    == typeof(ParenthesedExpressionList<>)
                ? new ParenthesedExpressionList<T>(converted)
                : new ExpressionList<T>(converted);
        }
    }
}
