package tim4it.login.eko.util;

/**
 * A convenience class to represent name-value pairs - holds two objects - serialized
 */
public record Pair<T1, T2>(T1 first, T2 second) {
    public static <T1, T2> Pair<T1, T2> of(T1 first, T2 second) {
        return new Pair<>(first, second);
    }

    public Pair<T1, T2> withFirst(T1 first) {
        return new Pair<>(first, second);
    }

    public Pair<T1, T2> withSecond(T2 second) {
        return new Pair<>(first, second);
    }
}
