package net.loserver.oreradar.platform;

import java.util.ServiceLoader;

/**
 * Loader-specific implementations of common interfaces. Each loader module lists its classes in
 * {@code META-INF/services/<interface name>}, and {@link ServiceLoader} finds them at run time.
 */
public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    /** Loads the implementation of {@code service} that the running loader ships. */
    public static <T> T load(Class<T> service) {
        // Ask the loader that defined the mod's classes, which sees its service files, rather than
        // whatever the thread's context loader happens to be.
        return ServiceLoader.load(service, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation of " + service.getName()
                        + " on this loader; check its META-INF/services file"));
    }
}
