package dev.k04mreverseforward.fabric;

import dev.k04mreverseforward.ReverseForward;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class K04MReverseForwardFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ReverseForward.initialize();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ReverseForward.commands()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> ReverseForward.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ReverseForward.disconnect());
    }
}
