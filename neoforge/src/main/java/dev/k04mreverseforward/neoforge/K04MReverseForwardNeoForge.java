package dev.k04mreverseforward.neoforge;

import dev.k04mreverseforward.ReverseForward;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@Mod(value = K04MReverseForwardNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class K04MReverseForwardNeoForge {
    static final String MOD_ID = "k04m_reverse_forward";

    /**
     * Creates a k04 m reverse forward neo forge with the supplied dependencies and initial state.
     *
     * @param modBus the mod bus supplied to this operation
     */
    public K04MReverseForwardNeoForge(IEventBus modBus) {
        modBus.addListener(K04MReverseForwardNeoForge::clientSetup);
    }

    /**
     * Performs the client setup operation for the k04 m reverse forward neo forge.
     *
     * @param event the event supplied to this operation
     */
    private static void clientSetup(FMLClientSetupEvent event) {
        // Mod construction happens before the Minecraft client exists.
        event.enqueueWork(() -> ReverseForward.initialize(FMLPaths.CONFIGDIR.get()));
    }

    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        /**
         * Registers commands for the k04 m reverse forward neo forge.
         *
         * @param event the event supplied to this operation
         */
        @SubscribeEvent
        public static void registerCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(ReverseForward.commands());
        }

        /**
         * Performs the client tick operation for the k04 m reverse forward neo forge.
         *
         * @param event the event supplied to this operation
         */
        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            ReverseForward.tick();
        }

        /**
         * Performs the disconnect operation for the k04 m reverse forward neo forge.
         *
         * @param event the event supplied to this operation
         */
        @SubscribeEvent
        public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
            ReverseForward.disconnect();
        }
    }
}
