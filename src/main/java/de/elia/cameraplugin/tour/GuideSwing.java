package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.util.logging.Level;

/**
 * The swing of a guide out of camera mode, on the screens of his guests,
 * {@code follow.mirror-swing}: when he swings his arm - hits, digs, places a
 * block -, each guest sees the arm that holds the guide's item swing, see
 * {@link GuideMirror}.
 *
 * <p>The game has a packet for a swing, {@code ClientboundAnimatePacket},
 * which tells a client that an entity swings its arm. Given the guest's own
 * player, his client swings the arm it draws in front of him. The API only
 * swings an arm for everybody, so the packet is sent through the internals of
 * the server, see {@link PacketSender}, to the guest alone.</p>
 *
 * <p>His client then tells the server it swung, as after a click of his own.
 * The server lets nobody see that - nobody sees a guest who follows closely -,
 * and camera mode turns down the click that comes with it, as any other.
 * Only the arm swings: how the item sinks after a hit and comes back up,
 * a client works out only for hits of its own.</p>
 *
 * <p>Internals are no API: when a name is gone in an update of the game, the
 * guests no longer see the guide swing - the start says so once in the
 * console - and nothing else changes.</p>
 */
final class GuideSwing {

    /** The packet that tells the client an entity swings its arm. */
    private static final String ANIMATE_PACKET = "net.minecraft.network.protocol.game.ClientboundAnimatePacket";

    /** What the packet takes: an entity of the server, and what it does. */
    private static final String ENTITY = "net.minecraft.world.entity.Entity";

    /** What the packet calls a swing of the hand, and of the other hand. */
    private static final int MAIN_HAND = 0;
    private static final int OFF_HAND = 3;

    private final PacketSender sender;
    /** Makes the packet, from the entity and what it does. */
    private Constructor<?> animatePacket;
    /** Whether all of it was found. */
    private boolean available;

    GuideSwing(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches the swing off; on Paper, where it should be there, the
     * console hears about it once.
     *
     * @param log where to say so, or {@code null} while the swing is
     *            switched off anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            animatePacket = PacketSender.internal(ANIMATE_PACKET)
                    .getConstructor(PacketSender.internal(ENTITY), int.class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow do not see their leader swing on this version of the"
                        + " server: " + ex);
            }
        }
    }

    /** Whether the swing works on this server. */
    boolean isAvailable() {
        return available;
    }

    /** Swings the guest's arm on his own client: the arm of the item in his hand, or of the one in his other hand. */
    void swing(Player guest, boolean offHand) {
        if (!available) {
            return;
        }
        try {
            sender.send(guest, animatePacket.newInstance(sender.handleOf(guest), offHand ? OFF_HAND : MAIN_HAND));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Not sent: he does not see this one swing.
        }
    }
}
