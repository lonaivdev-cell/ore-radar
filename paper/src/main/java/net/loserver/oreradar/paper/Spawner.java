package net.loserver.oreradar.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.RegionAccessor;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * {@code World#spawn} with a setup callback that runs before the entity is added to the world, so
 * nobody ever sees it unconfigured (an outline must be hidden from everyone but its owner before
 * the first spawn packet goes out).
 *
 * <p>The callback's type changed under us: Paper 1.20.1 takes {@code org.bukkit.util.Consumer},
 * 1.20.2 switched to {@code java.util.function.Consumer} and later releases dropped the old
 * overload. A plugin compiled against 1.20.1 cannot name a method that exists on both, so the
 * overload is looked up once at start.
 */
final class Spawner {
    private static final Method SPAWN;
    /** The 1.20.1 callback interface when that is the only one; null when java.util.function.Consumer works. */
    private static final Class<?> LEGACY_CONSUMER;

    static {
        Method spawn;
        Class<?> legacy = null;
        try {
            spawn = RegionAccessor.class.getMethod("spawn", Location.class, Class.class, Consumer.class);
        } catch (NoSuchMethodException modernMissing) {
            try {
                legacy = Class.forName("org.bukkit.util.Consumer");
                spawn = RegionAccessor.class.getMethod("spawn", Location.class, Class.class, legacy);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        SPAWN = spawn;
        LEGACY_CONSUMER = legacy;
    }

    private Spawner() {}

    static <T extends Entity> T spawn(World world, Location at, Class<T> type, Consumer<T> setup) {
        Object callback = LEGACY_CONSUMER == null ? setup : legacyCallback(setup);
        try {
            return type.cast(SPAWN.invoke(world, at, type, callback));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Object legacyCallback(Consumer<T> setup) {
        return Proxy.newProxyInstance(Spawner.class.getClassLoader(), new Class<?>[] {LEGACY_CONSUMER}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "accept" -> {
                    setup.accept((T) args[0]);
                    return null;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "OreRadar spawn callback";
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        });
    }
}
