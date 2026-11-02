package net.loserver.oreradar.core.scan;

import java.util.Objects;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.config.EffectiveClientSettings;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.net.ClientPrefs;

/**
 * How the server scans for one player and what it may send them: the server's limits combined
 * with the player's prefs (hello/prefs packets), each capped by the other (DESIGN §5, §6).
 *
 * @param maxMarkers markers to keep and send; 0 when outlines are off for this player
 */
public record ScanSettings(ActiveFrom activeFrom, int probeChunks, int maxMarkers, boolean sendMarkers, boolean sendNearest,
                           int precisionBlocks) {
    public ScanSettings {
        Objects.requireNonNull(activeFrom, "activeFrom");
        if (probeChunks < 0 || maxMarkers < 0 || precisionBlocks < 1) throw new IllegalArgumentException("bad scan settings");
    }

    /** @param prefs the player's prefs, or {@link ClientPrefs#DEFAULT} for a client that never said hello */
    public static ScanSettings of(ServerConfig server, ClientPrefs prefs) {
        boolean markers = server.outlinesEnabled() && prefs.wantsOutlines() && server.maxMarkers() > 0;
        boolean nearest = ((server.arrowEnabled() || server.arrowShowDistance()) && prefs.wantsArrow())
                || (server.beepEnabled() && prefs.wantsBeep());
        return new ScanSettings(
                ActiveFrom.min(prefs.activeFrom(), server.activeFromMax()),
                EffectiveClientSettings.clampProbe(prefs.probeChunks(), server.probeChunks()),
                markers ? server.maxMarkers() : 0,
                markers,
                nearest,
                server.arrowPrecisionBlocks());
    }
}
