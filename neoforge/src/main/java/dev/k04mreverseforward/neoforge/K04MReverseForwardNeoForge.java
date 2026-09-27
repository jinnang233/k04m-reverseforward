package dev.k04mreverseforward.neoforge;

import dev.k04mreverseforward.ReverseForward;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@Mod(K04MReverseForwardNeoForge.MOD_ID)
public final class K04MReverseForwardNeoForge {
    static final String MOD_ID = "k04m_reverse_forward";

    public K04MReverseForwardNeoForge() {
        ReverseForward.initialize();
    }

    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void registerCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(ReverseForward.commands());
        }

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            ReverseForward.tick();
        }

        @SubscribeEvent
        public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
            ReverseForward.disconnect();
        }
    }
}
