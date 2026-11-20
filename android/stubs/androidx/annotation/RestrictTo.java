package androidx.annotation;

/** Stand-in for the AndroidX annotation the WireGuard library is marked with (no effect at run time). */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
public @interface RestrictTo {
    Scope[] value();
    enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }
}
