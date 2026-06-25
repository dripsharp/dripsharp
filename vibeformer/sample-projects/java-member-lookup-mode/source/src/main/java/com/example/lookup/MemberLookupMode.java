package com.example.lookup;

public enum MemberLookupMode {
    /** Lookup of a local member in the lexical scope. */
    IMPLICIT_LOCAL,

    /** Lookup of a non-local member in the lexical scope. */
    IMPLICIT_LEXICAL,

    /** Member lookup whose implicit receiver is the base module. */
    IMPLICIT_BASE,

    /** Member lookup whose implicit receiver is this. */
    IMPLICIT_THIS,

    /** Member lookup with explicit receiver, such as foo.bar. */
    EXPLICIT_RECEIVER
}
