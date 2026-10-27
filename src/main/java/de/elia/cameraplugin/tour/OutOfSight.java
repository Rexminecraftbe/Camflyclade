package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.util.logging.Level;

/**
 * Hides players from one client and leaves them in its tab list: the client
 * is told they are gone, the way it lets go of a player who flies out of its
 * range - nothing of them is drawn, neither the body, which the camera team
 * shows to its members through the invisibility, nor the outline nor the
 * camera head, and their names stay in the tab list.
 *
 * <p>{@code Player#hidePlayer} takes them out of the tab list as well, and
 * the API has no way to hide only the entity. So the packet that removes an
 * entity, {@code ClientboundRemoveEntitiesPacket}, is sent through the
 * internals of the server, see {@link PacketSender}. The server goes on
 * thinking the client sees them and sends what they do, which the client
 * leaves alone for an entity it does not know. It does send them anew when
 * it pairs them with the client once more - after a jump out of its range
 * and back, say -, so the tour sends the packet again at the end of every
 * tick.</p>
 *
 * <p>Internals are no API: any update of the game may rename them. Where the
 * packet is not found, the tour falls back on {@code hidePlayer}, and the
 * start says so once in the console.</p>
 */
final class OutOfSight {

    /** The packet that tells the client entities are gone. */
    private static final String REMOVE_PACKET = "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket";

    private final PacketSender sender;
    /** Makes the packet, from the ids of the entities. */
    private Constructor<?> removePacket;
    /** Whether all of it was found. */
    private boolean available;

    OutOfSight(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. On Paper,
     * where they should be there, the console hears once when they are not.
     *
     * @param log where to say so, or {@code null} while the tour is switched
     *            off anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            removePacket = PacketSender.internal(REMOVE_PACKET).getConstructor(int[].class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "/cam tour cannot keep the players it hides in the tab list on this version"
                        + " of the server; they are taken out of it as well: " + ex);
            }
        }
    }

    /** Whether it works on this server. */
    boolean isAvailable() {
        return available;
    }

    /**
     * Tells the client of the viewer that these entities are gone.
     *
     * @return whether the packet went out
     */
    boolean hide(Player viewer, int[] ids) {
        if (!available || ids.length == 0) {
            return false;
        }
        try {
            sender.send(viewer, removePacket.newInstance((Object) ids));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
