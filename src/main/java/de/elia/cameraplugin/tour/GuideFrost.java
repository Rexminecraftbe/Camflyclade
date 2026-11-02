package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * The frost of a guide out of camera mode, on the screens of his guests,
 * {@code follow.mirror-frost}: stuck in powder snow, he sees the icy rim
 * round his screen grow - and so do they, see {@link GuideMirror}.
 *
 * <p>A client draws the rim after the frost of its own player, which the
 * server sends with the rest of the player's data. Bukkit only sets the frost
 * on the server, where it would freeze the guest himself, so the data goes
 * through the internals of the server, see {@link PacketSender}, to his client
 * alone: there his frost is the guide's, on the server it stays his.</p>
 *
 * <p>Internals are no API: when a name is gone in an update of the game, the
 * guests no longer see the guide's frost - the start says so once in the
 * console - and nothing else changes.</p>
 */
final class GuideFrost {

    /** The packet with some of an entity's data: its number and the values. */
    private static final String DATA_PACKET = "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket";

    /** One value of an entity's data. */
    private static final String DATA_VALUE = "net.minecraft.network.syncher.SynchedEntityData$DataValue";

    /** What names one value in an entity's data. */
    private static final String ACCESSOR = "net.minecraft.network.syncher.EntityDataAccessor";

    /** An entity of the server, whose data includes its frost. */
    private static final String ENTITY = "net.minecraft.world.entity.Entity";

    /** The accessor of the frost in an entity's data. */
    private static final String TICKS_FROZEN = "DATA_TICKS_FROZEN";

    private final PacketSender sender;
    /** Makes the packet: the number of the entity, the values. */
    private Constructor<?> dataPacket;
    /** Makes one value, from the accessor and what it holds. */
    private Method value;
    /** The accessor of the frost. */
    private Object ticksFrozen;
    /** Whether all of it was found. */
    private boolean available;

    GuideFrost(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches the frost off; on Paper, where it should be there, the
     * console hears about it once.
     *
     * @param log where to say so, or {@code null} while the frost is
     *            switched off anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            Class<?> data = PacketSender.internal(DATA_VALUE);
            ticksFrozen = PacketSender.internal(ENTITY).getField(TICKS_FROZEN).get(null);
            value = data.getMethod("create", PacketSender.internal(ACCESSOR), Object.class);
            dataPacket = PacketSender.internal(DATA_PACKET).getConstructor(int.class, List.class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow do not see their leader's frost on this version of"
                        + " the server: " + ex);
            }
        }
    }

    /** Whether the frost works on this server. */
    boolean isAvailable() {
        return available;
    }

    /**
     * Shows the guest this frost as his own, on his client only.
     *
     * @return whether the packet went out
     */
    boolean show(Player guest, int ticks) {
        if (!available) {
            return false;
        }
        try {
            Object frost = value.invoke(null, ticksFrozen, ticks);
            sender.send(guest, dataPacket.newInstance(guest.getEntityId(), List.of(frost)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
