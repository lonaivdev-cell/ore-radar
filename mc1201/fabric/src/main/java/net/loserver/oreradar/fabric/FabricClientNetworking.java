package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.net.RadarProtocol;

/** The client half of {@link FabricNetworking}: server-to-client payloads go to the {@link ClientBridge}. */
final class FabricClientNetworking {
    private FabricClientNetworking() {}

    static void registerClientReceivers() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() != RadarProtocol.Direction.SERVER_TO_CLIENT) continue;
            ClientPlayNetworking.registerGlobalReceiver(FabricNetworking.id(channel), (client, handler, buffer, sender) -> {
                byte[] payload = FabricNetworking.bytes(buffer);
                client.execute(() -> ClientBridge.get().receive(channel, payload));
            });
        }
    }
}
