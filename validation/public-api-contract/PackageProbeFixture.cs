namespace DripSharp.PublicApiProbeFixture;

public delegate string? Formatter<in T>(T value) where T : class;

public interface IResource<out T> : IDisposable where T : class
{
    T Value { get; }
}

public sealed class Resource<T> : IResource<T> where T : class, new()
{
    public const string Kind = "fixture";
    public Resource(T? value = null) => Value = value ?? new T();
    public T Value { get; }
    public string? Label { get; set; }
    public TResult Map<TResult>(Func<T, TResult> mapper) where TResult : notnull => mapper(Value);
    public TResult? MapNullable<TResult>(Func<T, TResult?> mapper) => mapper(Value);
    public void Dispose() { }
}
