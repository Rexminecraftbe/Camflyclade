package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * The guide's view on a tour: the client of a guest draws the world through
 * the eyes of the guide, the way spectator mode shows another player - and
 * just as smoothly, as the client itself follows how the guide moves and
 * turns.
 *
 * <p>The game has a packet for it, {@code ClientboundSetCameraPacket}, but
 * neither Bukkit nor Paper lets a plugin send it outside spectator mode -
 * {@code Player#setSpectatorTarget} asks for spectator mode, and that is no
 * fair camera: it flies through walls and teleports to any player. So the
 * packet is sent through the internals of the server, by reflection, under
 * the names Paper runs with since 1.20.5: Mojang's.</p>
 *
 * <p>Internals are no API: any update of the game may rename them. That is
 * why all of it is in this class, and why nothing depends on it: when a name
 * is gone, the guide's view is simply not there - the start says so once in
 * the console -, and guests look round freely, as they do on Spigot, whose
 * internals have other names.</p>
 */
final class GuideView {

    /** The player of CraftBukkit, whose {@code getHandle()} is the player of the server. */
    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";

    /** The packet that tells the client which entity it sees through. */
    private static final String CAMERA_PACKET = "net.minecraft.network.protocol.game.ClientboundSetCameraPacket";

    /** What the packet takes: an entity of the server. */
    private static final String ENTITY = "net.minecraft.world.entity.Entity";

    /** What the connection sends. */
    private static final String PACKET = "net.minecraft.network.protocol.Packet";

    /** The player of the server, from a Bukkit player. */
    private Method handle;
    /** The connection of the player of the server. */
    private Field connection;
    /** Sends a packet over that connection. */
    private Method send;
    /** Makes the packet, from the entity to see through. */
    private Constructor<?> cameraPacket;
    /** Whether all of it was found. */
    private boolean available;

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches the guide's view off; on Paper, where it should be
     * there, the console hears about it once.
     *
     * @param log where to say so, or {@code null} while the guide's view is
     *            switched off anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            ClassLoader server = Player.class.getClassLoader();
            Class<?> craftPlayer = Class.forName(CRAFT_PLAYER, false, server);
            handle = craftPlayer.getMethod("getHandle");
            connection = handle.getReturnType().getField("connection");
            send = connection.getType().getMethod("send", Class.forName(PACKET, false, server));
            cameraPacket = Class.forName(CAMERA_PACKET, false, server)
                    .getConstructor(Class.forName(ENTITY, false, server));
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && isPaper()) {
                log.log(Level.WARNING, "The guide's view of /cam tour does not work on this version of the server,"
                        + " guests look round freely: " + ex);
            }
        }
    }

    /** Whether the server is Paper, where the internals go by Mojang's names. */
    private static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.ServerBuildInfo");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /** Whether the guide's view works on this server. */
    boolean isAvailable() {
        return available;
    }

    /**
     * Lets the client of the guest see through the eyes of another player -
     * or, given the guest himself, through his own again.
     *
     * @return whether the packet went out
     */
    boolean show(Player guest, Player through) {
        if (!available) {
            return false;
        }
        try {
            Object packet = cameraPacket.newInstance(handle.invoke(through));
            send.invoke(connection.get(handle.invoke(guest)), packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
