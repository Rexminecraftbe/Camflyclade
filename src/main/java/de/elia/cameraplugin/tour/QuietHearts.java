package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * Puts hearts on the screen of one client without a hit: for a guest of a
 * guide out of camera mode, whose client is shown the guide's hearts as his
 * own when the tour starts and his own again when it ends, see
 * {@link GuideMirror}.
 *
 * <p>The hearts {@code sendHealthUpdate} sends, a client holds against those
 * it showed before. Fewer, it takes them for a hit and plays it - the screen
 * jolts, the hearts flash -, more, it lets the hearts flash white. Only the
 * very first it is sent after joining or respawning it takes as they are. The
 * packet has no way to say otherwise. The hearts in the rest of the player's
 * data, though - the data the server sends his frost with, see
 * {@link GuideFrost} -, the client simply takes. So the hearts go to the
 * guest's client that way first, through the internals of the server, see
 * {@link PacketSender}, and {@code sendHealthUpdate} with the same hearts
 * right after it changes nothing the client could take for a hit. On the
 * server, the guest's health stays his.</p>
 *
 * <p>Internals are no API: when a name is gone in an update of the game, the
 * hearts go out with {@code sendHealthUpdate} alone - the start says so once
 * in the console - and the guest sees a hit where his hearts change.</p>
 */
final class QuietHearts {

    /** The packet with some of an entity's data: its number and the values. */
    private static final String DATA_PACKET = "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket";

    /** One value of an entity's data. */
    private static final String DATA_VALUE = "net.minecraft.network.syncher.SynchedEntityData$DataValue";

    /** What names one value in an entity's data. */
    private static final String ACCESSOR = "net.minecraft.network.syncher.EntityDataAccessor";

    /** A living entity of the server, whose data includes its health. */
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";

    /** The accessor of the health in a living entity's data. */
    private static final String HEALTH = "DATA_HEALTH_ID";

    private final PacketSender sender;
    /** Makes the packet: the number of the entity, the values. */
    private Constructor<?> dataPacket;
    /** Makes one value, from the accessor and what it holds. */
    private Method value;
    /** The accessor of the health. */
    private Object health;
    /** Whether all of it was found. */
    private boolean available;

    QuietHearts(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. On Paper,
     * where they should be there, the console hears once when they are not.
     *
     * @param log where to say so, or {@code null} while the guests are not
     *            shown their guide's hearts anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            // Paper makes the accessor public, the game keeps it private.
            Field field = PacketSender.internal(LIVING_ENTITY).getDeclaredField(HEALTH);
            field.setAccessible(true);
            health = field.get(null);
            value = PacketSender.internal(DATA_VALUE).getMethod("create", PacketSender.internal(ACCESSOR),
                    Object.class);
            dataPacket = PacketSender.internal(DATA_PACKET).getConstructor(int.class, List.class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow see a hit when they are shown their leader's hearts"
                        + " and when they get their own back, on this version of the server: " + ex);
            }
        }
    }

    /**
     * Shows the guest these hearts as his own, on his client only, without a
     * hit. Sent with {@code sendHealthUpdate} right after, the same hearts
     * change nothing there.
     *
     * @return whether the packet went out
     */
    boolean show(Player guest, double hearts) {
        if (!available) {
            return false;
        }
        try {
            // A float, as the health is one: anything else would only fail on its way out.
            Object data = value.invoke(null, health, (float) hearts);
            sender.send(guest, dataPacket.newInstance(guest.getEntityId(), List.of(data)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
