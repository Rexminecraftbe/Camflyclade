package de.elia.cameraplugin.tour;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sends a packet of the game to one player, through the internals of the
 * server, under the names Paper runs with since 1.20.5: Mojang's. What the
 * tour needs of them and the API does not offer, see {@link GuideView} and
 * {@link OutOfSight}, goes out this way.
 */
final class PacketSender {

    /** The player of CraftBukkit, whose {@code getHandle()} is the player of the server. */
    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";

    /** What the connection sends. */
    private static final String PACKET = "net.minecraft.network.protocol.Packet";

    /** The player of the server, from a Bukkit player. */
    private Method handle;
    /** The connection of the player of the server. */
    private Field connection;
    /** Sends a packet over that connection. */
    private Method send;

    /**
     * Looks the internals up, at the start and after a reload.
     *
     * @throws ReflectiveOperationException when one of them is not there
     */
    void load() throws ReflectiveOperationException {
        ClassLoader server = Player.class.getClassLoader();
        handle = Class.forName(CRAFT_PLAYER, false, server).getMethod("getHandle");
        connection = handle.getReturnType().getField("connection");
        send = connection.getType().getMethod("send", Class.forName(PACKET, false, server));
    }

    /** The class of the internals by that name, out of the server. */
    static Class<?> internal(String name) throws ClassNotFoundException {
        return Class.forName(name, false, Player.class.getClassLoader());
    }

    /** The player of the server behind a Bukkit player. */
    Object handleOf(Player player) throws ReflectiveOperationException {
        return handle.invoke(player);
    }

    /** The connection of the player of the server behind a Bukkit player: what {@link #send} sends over. */
    Object listenerOf(Player player) throws ReflectiveOperationException {
        return connection.get(handleOf(player));
    }

    /** Sends the packet to the player. */
    void send(Player to, Object packet) throws ReflectiveOperationException {
        send.invoke(listenerOf(to), packet);
    }

    /** Whether the server is Paper, where the internals go by Mojang's names. */
    static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.ServerBuildInfo");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
