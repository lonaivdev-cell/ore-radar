package net.loserver.oreradar.core.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Whole-file writes that never leave a half-written file behind (a crash mid-write keeps the old one). */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        // A sibling, so the rename stays on one file system; created with the default permissions.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Copies file to the first free name among {@code <name>.bak}, {@code <name>.bak.1}, ... and
     * returns that path, so an older backup is never overwritten.
     */
    public static Path backup(Path file) throws IOException {
        Path target = freeSibling(file, ".bak");
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    /**
     * Renames file to the first free name among {@code <name><suffix>}, {@code <name><suffix>.1},
     * ... and returns that path: a file that cannot be read is set aside for the user rather than
     * overwritten by the next save.
     */
    public static Path moveAside(Path file, String suffix) throws IOException {
        Path target = freeSibling(file, suffix);
        Files.move(file, target);
        return target;
    }

    private static Path freeSibling(Path file, String suffix) {
        String name = file.getFileName() + suffix;
        Path target = file.resolveSibling(name);
        for (int i = 1; Files.exists(target); i++) target = file.resolveSibling(name + "." + i);
        return target;
    }
}
