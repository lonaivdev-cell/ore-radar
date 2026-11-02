package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.network.RadarPayload;

/** The client half of {@link FabricNetworking}: server-to-client payloads go to the {@link ClientBridge}. */
final class FabricClientNetworking {
    private FabricClientNetworking() {}

    /** Fabric runs these on the client thread. */
    static void registerClientReceivers() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() != RadarProtocol.Direction.SERVER_TO_CLIENT) continue;
            ClientPlayNetworking.registerGlobalReceiver(RadarPayload.type(channel),
                    (payload, context) -> ClientBridge.get().receive(channel, payload.bytes()));
        }
    }

    /** A server without Ore Radar drops a payload it does not know. */
    static void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        ClientPlayNetworking.send(new RadarPayload(channel, payload));
    }
}
