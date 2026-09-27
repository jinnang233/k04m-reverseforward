package dev.k04mreverseforward;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.krypt04mcg.api.Krypt04McgApi;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

public final class ReverseForward {
    public static final String CONTROL_CHANNEL = "k04m_reverse_forward:control";
    public static final String SOCKET_CHANNEL = "k04m_reverse_forward:tunnel";
    private static final ForwardingManager MANAGER = new ForwardingManager();
    private static boolean initialized;

    private ReverseForward() {}

    public static synchronized void initialize(Path configDirectory) {
        if (initialized) return;
        MANAGER.load(configDirectory);
        Krypt04McgApi.registerReceiver(CONTROL_CHANNEL, MANAGER::receiveControl);
        Krypt04McgApi.registerSocketReceiver(SOCKET_CHANNEL, MANAGER::receiveSocket);
        initialized = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <S> LiteralArgumentBuilder<S> commands() {
        return (LiteralArgumentBuilder<S>) commandsRaw();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LiteralArgumentBuilder commandsRaw() {
        return literal("k04mrf")
                .then(literal("register")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("listenPort", IntegerArgumentType.integer(1, 65535))
                                        .then(argument("targetPort", IntegerArgumentType.integer(1, 65535))
                                                .executes(context -> MANAGER.register(
                                                        StringArgumentType.getString(context, "name"),
                                                        IntegerArgumentType.getInteger(context, "listenPort"),
                                                        IntegerArgumentType.getInteger(context, "targetPort")))))))
                .then(literal("invite")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("player", StringArgumentType.word())
                                        .executes(context -> MANAGER.invite(
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "player"))))))
                .then(literal("accept")
                        .then(argument("invitationId", StringArgumentType.word())
                                .executes(context -> MANAGER.accept(
                                        StringArgumentType.getString(context, "invitationId")))
                                .then(argument("targetPort", IntegerArgumentType.integer(1, 65535))
                                        .executes(context -> MANAGER.accept(
                                                StringArgumentType.getString(context, "invitationId"),
                                                IntegerArgumentType.getInteger(context, "targetPort"))))))
                .then(literal("deny")
                        .then(argument("invitationId", StringArgumentType.word())
                                .executes(context -> MANAGER.deny(
                                        StringArgumentType.getString(context, "invitationId")))))
                .then(literal("start")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> MANAGER.start(StringArgumentType.getString(context, "name")))))
                .then(literal("stop")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> MANAGER.stop(StringArgumentType.getString(context, "name")))))
                .then(literal("remove")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> MANAGER.remove(StringArgumentType.getString(context, "name")))))
                .then(literal("revoke")
                        .then(argument("routeId", StringArgumentType.word())
                                .executes(context -> MANAGER.revoke(StringArgumentType.getString(context, "routeId")))))
                .then(literal("list").executes(context -> MANAGER.list()))
                .then(literal("invitations").executes(context -> MANAGER.listInvitations()))
                .then(literal("help").executes(context -> help()))
                .executes(context -> help());
    }

    private static int help() {
        message("Commands: register, invite, accept, deny, start, stop, remove, revoke, list, invitations");
        message("Use /k04mrf register <name> <listenPort> <targetPort>, then /k04mrf invite <name> <player>.");
        message("The invited player may override the proposed target with /k04mrf accept <invitationId> <targetPort>.");
        return 1;
    }

    public static void tick() {
        MANAGER.tick();
    }

    public static void disconnect() {
        MANAGER.disconnect();
    }

    static void message(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        if (!client.isSameThread()) {
            client.execute(() -> message(text));
            return;
        }
        if (client.gui != null) client.gui.hud.getChat().addClientSystemMessage(Component.literal("[K04MRF] " + text));
    }
}
