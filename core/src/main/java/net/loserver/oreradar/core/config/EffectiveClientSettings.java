package net.loserver.oreradar.core.config;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.net.ConfigPacket;

/**
 * The client's settings capped by the server's (DESIGN §6): what the client actually does. A
 * feature is on only if both sides allow it; limits are the smaller of the two.
 *
 * @param probeChunks the outline reach this client asks for, within the server's
 * @param distance    show the distance readout (the HUD's text next to the arrow)
 * @param height      show the height difference in that readout
 */
public record EffectiveClientSettings(ActiveFrom activeFrom, int probeChunks, int maxOutlines,
                                      boolean outlines, boolean outlinesThroughWalls, boolean arrow, boolean distance,
                                      boolean height, boolean beep, boolean energy) {

    public static EffectiveClientSettings of(ClientConfig client, ConfigPacket.Limits server) {
        boolean distance = client.arrowEnabled() && client.arrowShowDistance() && server.distance();
        return new EffectiveClientSettings(
                ActiveFrom.min(client.activeFrom(), server.activeFromMax()),
                clampProbe(client.probeChunks(), server.probeChunks()),
                Math.min(client.maxOutlines(), server.maxMarkers()),
                client.outlinesEnabled() && server.outlines() && server.maxMarkers() > 0,
                server.outlinesThroughWalls(),
                client.arrowEnabled() && server.arrow(),
                distance,
                distance && client.arrowShowHeight(),
                client.beepEnabled() && server.beep(),
                server.energy());
    }

    /** The client's probe reach (-1 = server max) capped by the server's. */
    public static int clampProbe(int clientProbe, int serverProbe) {
        return clientProbe < 0 ? serverProbe : Math.min(clientProbe, serverProbe);
    }
}
