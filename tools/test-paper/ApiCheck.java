import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks that every server API member the plugin's bytecode references exists, with the same
 * descriptor and the same class-or-interface kind, in another server's API: the binary
 * compatibility a plugin compiled against Paper 1.20.1 needs on a later Paper.
 *
 *   java tools/test-paper/ApiCheck.java <plugin.jar> <api jar or dir of jars>...
 *
 * Only references from net/loserver/oreradar/paper/ classes to org.bukkit, io.papermc, net.kyori,
 * org.joml and com.destroystokyo are checked. Prints each unresolved reference with the classes
 * that use it; exit 1 if there is any.
 */
public final class ApiCheck {
    private static final String[] API_PREFIXES = {"org/bukkit/", "io/papermc/", "net/kyori/", "org/joml/", "com/destroystokyo/"};

    record Ref(int kind, String owner, String name, String descriptor) {
        @Override
        public String toString() {
            return switch (kind) {
                case 7 -> "class " + owner.replace('/', '.');
                case 9 -> "field " + owner.replace('/', '.') + "." + name + " " + descriptor;
                case 11 -> "interface method " + owner.replace('/', '.') + "." + name + descriptor;
                default -> "method " + owner.replace('/', '.') + "." + name + descriptor;
            };
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java ApiCheck.java <plugin.jar> <api jar or dir>...");
            System.exit(2);
        }
        List<URL> urls = new ArrayList<>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), urls);
        java.util.Map<Ref, Set<String>> refs = new java.util.TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        try (JarFile jar = new JarFile(args[0])) {
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (!e.getName().startsWith("net/loserver/oreradar/paper/") || !e.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    for (Ref r : readRefs(in)) {
                        if (isApi(r.owner())) refs.computeIfAbsent(r, k -> new TreeSet<>()).add(e.getName());
                    }
                }
            }
        }
        int bad = 0;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            for (var entry : refs.entrySet()) {
                String problem = resolve(loader, entry.getKey());
                if (problem == null) continue;
                bad++;
                System.out.println("UNRESOLVED " + entry.getKey() + ": " + problem);
                for (String user : entry.getValue()) System.out.println("    used by " + user);
            }
        }
        System.out.println(refs.size() + " API references checked against " + urls.size() + " jars, " + bad + " unresolved");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static void collect(File f, List<URL> urls) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) collect(c, urls);
        } else if (f.getName().endsWith(".jar")) {
            urls.add(f.toURI().toURL());
        }
    }

    private static boolean isApi(String owner) {
        String o = owner.startsWith("[") ? owner.replaceAll("^\\[+L?", "") : owner;
        for (String p : API_PREFIXES) if (o.startsWith(p)) return true;
        return false;
    }

    /** The class, field and method references in a class file's constant pool. */
    private static List<Ref> readRefs(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[][] links = new int[count][];
        int[] tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> links[i] = new int[] {in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> links[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> links[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        List<Ref> out = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 7) {
                out.add(new Ref(7, (String) pool[links[i][0]], "", ""));
            } else if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                String owner = (String) pool[links[links[i][0]][0]];
                int[] nat = links[links[i][1]];
                out.add(new Ref(tags[i], owner, (String) pool[nat[0]], (String) pool[nat[1]]));
            }
        }
        return out;
    }

    /** Null if the reference resolves as the JVM would resolve it; else why not. */
    private static String resolve(ClassLoader loader, Ref r) {
        Class<?> owner;
        try {
            owner = Class.forName(r.owner().replace('/', '.'), false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return "class missing (" + e + ")";
        }
        if (r.kind() == 7) return null;
        if (r.kind() == 10 && owner.isInterface()) return "is an interface here (IncompatibleClassChangeError)";
        if (r.kind() == 11 && !owner.isInterface()) return "is a class here (IncompatibleClassChangeError)";
        if (r.kind() == 9) {
            for (Class<?> c : hierarchy(owner)) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getName().equals(r.name()) && descriptor(f.getType()).equals(r.descriptor())) return null;
                }
            }
            return "no such field";
        }
        if (r.name().equals("<init>")) {
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
                if (("(" + params(c.getParameterTypes()) + ")V").equals(r.descriptor())) return null;
            }
            return "no such constructor";
        }
        for (Class<?> c : hierarchy(owner)) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(r.name()) && ("(" + params(m.getParameterTypes()) + ")" + descriptor(m.getReturnType())).equals(r.descriptor())) {
                    return null;
                }
            }
        }
        // Signature-polymorphic and inherited Object methods on interfaces.
        for (Method m : Object.class.getDeclaredMethods()) {
            if (m.getName().equals(r.name()) && ("(" + params(m.getParameterTypes()) + ")" + descriptor(m.getReturnType())).equals(r.descriptor())) return null;
        }
        return "no such method";
    }

    private static List<Class<?>> hierarchy(Class<?> start) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c)) continue;
            out.add(c);
            if (c.getSuperclass() != null) todo.add(c.getSuperclass());
            for (Class<?> i : c.getInterfaces()) todo.add(i);
        }
        return out;
    }

    private static String params(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> t : types) sb.append(descriptor(t));
        return sb.toString();
    }

    private static String descriptor(Class<?> t) {
        if (t.isArray()) return t.getName().replace('.', '/');
        if (t == void.class) return "V";
        if (t == boolean.class) return "Z";
        if (t == byte.class) return "B";
        if (t == char.class) return "C";
        if (t == short.class) return "S";
        if (t == int.class) return "I";
        if (t == long.class) return "J";
        if (t == float.class) return "F";
        if (t == double.class) return "D";
        return "L" + t.getName().replace('.', '/') + ";";
    }
}
