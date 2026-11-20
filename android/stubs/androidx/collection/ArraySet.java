package androidx.collection;

/** Stands in for AndroidX's ArraySet, the one AndroidX class the WireGuard library uses (only `new ArraySet<>()`, then
 *  as a Set), so the launcher needs no AndroidX. */
public class ArraySet<E> extends java.util.LinkedHashSet<E> {
    public ArraySet() { super(); }
    public ArraySet(int capacity) { super(capacity); }
}
