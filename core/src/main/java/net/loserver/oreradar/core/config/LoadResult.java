package net.loserver.oreradar.core.config;

import java.util.List;

/**
 * The outcome of loading a config file.
 *
 * @param config      the settings to use: the file's, or the previous ones if it could not be read
 * @param warnings    human-readable problems: clamped values, unknown keys, a parse error with its
 *                    line and column. Log each one; none of them stops the game
 * @param fileWritten the file was created, completed with missing keys, or migrated
 * @param failed      the file could not be read or parsed; {@code config} is the previous one
 */
public record LoadResult<C>(C config, List<String> warnings, boolean fileWritten, boolean failed) {
    public LoadResult {
        warnings = List.copyOf(warnings);
    }
}
