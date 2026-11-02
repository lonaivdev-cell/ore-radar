package net.loserver.oreradar.core.designation;

import java.util.Objects;

/**
 * One entry of a block (or item) list in Vein Mining syntax: {@code namespace:path} names one
 * block, {@code #namespace:path} a tag. A missing namespace means {@code minecraft}, as in
 * Minecraft's own resource locations; the characters allowed are Minecraft's too.
 */
public record Designation(boolean tag, String namespace, String path) {
    public static final int MAX_LENGTH = 256;

    public Designation {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            throw new IllegalArgumentException("invalid id " + namespace + ":" + path);
        }
    }

    /** Parses an entry; null if it is malformed. Surrounding whitespace is ignored. */
    public static Designation parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return null;
        boolean tag = s.charAt(0) == '#';
        String[] id = splitId(tag ? s.substring(1) : s);
        return id == null ? null : new Designation(tag, id[0], id[1]);
    }

    public static Designation block(String id) {
        return of(false, id);
    }

    public static Designation tag(String id) {
        return of(true, id);
    }

    private static Designation of(boolean tag, String id) {
        String[] parts = splitId(id);
        if (parts == null) throw new IllegalArgumentException("invalid id " + id);
        return new Designation(tag, parts[0], parts[1]);
    }

    /** {@code namespace:path}, without the '#'. */
    public String id() {
        return namespace + ":" + path;
    }

    /** The entry as written in configs and target specs. */
    @Override
    public String toString() {
        return tag ? "#" + id() : id();
    }

    /** Splits "ns:path" (or "path", meaning minecraft:path) into its parts; null if invalid. */
    public static String[] splitId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_LENGTH) return null;
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        return isValidNamespace(ns) && isValidPath(path) ? new String[] {ns, path} : null;
    }

    /** True for a full "ns:path" or bare "path" resource location. */
    public static boolean isValidId(String id) {
        return splitId(id) != null;
    }

    public static boolean isValidNamespace(String ns) {
        if (ns.isEmpty()) return false;
        for (int i = 0; i < ns.length(); i++) {
            char c = ns.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    public static boolean isValidPath(String path) {
        if (path.isEmpty()) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/')) return false;
        }
        return true;
    }
}
