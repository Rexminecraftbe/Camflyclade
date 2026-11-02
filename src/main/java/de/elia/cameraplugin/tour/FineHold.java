package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Holds a guest who sees through the guide's eyes turned between the ticks
 * too, {@code follow.fine-hold}.
 *
 * <p>His mouse still turns him on his client, at every frame, and the items
 * in his hands sway after it, see {@link CamTour}'s {@code holdTurned}. The
 * tour turns him back every tick - in between they sway, and snap back: they
 * tremble while he moves the mouse. Turned back far more often, they hardly
 * do. But Paper sends a packet that does not come from the main thread only
 * with the next tick, so this one is written straight to the channel of his
 * connection, through the internals of the server, see {@link PacketSender}.
 * His client answers every turn with a packet of its own - that is why it can
 * be switched off.</p>
 *
 * <p>Only the main thread holds a guest, every tick anew; a hold that is not
 * renewed lapses by itself, so a guest the tour lets go of - however - is
 * never turned for long after. Internals are no API: when a name is gone in an
 * update of the game, the start says so once in the console, and guests are
 * held every tick only, as before.</p>
 */
final class FineHold {

    /** How often a held guest is turned back, in milliseconds. */
    private static final long PERIOD_MILLIS = 8L;

    /** How long a hold lasts without being renewed, in milliseconds - a few ticks. */
    private static final long LAPSE_NANOS = TimeUnit.MILLISECONDS.toNanos(150L);

    /** The packet that turns a player on his own client: yaw, whether relative, pitch, whether relative. */
    private static final String ROTATION_PACKET = "net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket";

    /** The connection of a player of the server, under it its channel. */
    private static final String COMMON_LISTENER = "net.minecraft.server.network.ServerCommonPacketListenerImpl";

    /** Writes to a channel of Netty, from any thread. */
    private static final String CHANNEL_INVOKER = "io.netty.channel.ChannelOutboundInvoker";

    /** One guest held: the packet that turns him back, and where it goes, while it is still his game. */
    private record Hold(Object packet, Object listener, Object connection, long until) {
    }

    private final PacketSender sender;
    /** Makes the packet: yaw, relative, pitch, relative. */
    private Constructor<?> rotationPacket;
    /** The network connection under a player's connection. */
    private Field connection;
    /** What a network connection answers to now: his game, or something else between. */
    private Method packetListener;
    /** The channel of a network connection. */
    private Field channel;
    /** Writes to it and sends at once. */
    private Method writeAndFlush;
    /** Whether all of it was found. */
    private boolean available;
    /** The guests held, by their ids. */
    private final Map<UUID, Hold> held = new ConcurrentHashMap<>();
    /** Turns them back, while anyone is held; {@code null} before. */
    private ScheduledExecutorService turner;

    FineHold(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches the fine hold off; on Paper, where it should be there,
     * the console hears about it once.
     *
     * @param log where to say so, or {@code null} while it is switched off
     *            anyway
     */
    void load(ConsoleLog log) {
        available = false;
        held.clear();
        try {
            sender.load();
            ClassLoader server = Player.class.getClassLoader();
            rotationPacket = PacketSender.internal(ROTATION_PACKET)
                    .getConstructor(float.class, boolean.class, float.class, boolean.class);
            connection = PacketSender.internal(COMMON_LISTENER).getField("connection");
            packetListener = connection.getType().getMethod("getPacketListener");
            channel = connection.getType().getField("channel");
            writeAndFlush = Class.forName(CHANNEL_INVOKER, false, server).getMethod("writeAndFlush", Object.class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow are held turned every tick only on this version of"
                        + " the server, the items in their hands tremble a little while they move the mouse: " + ex);
            }
        }
    }

    /**
     * Holds the guest turned where he is, until the next tick renews it or
     * lets him go. Only from the main thread.
     */
    void hold(Player guest, float yaw, float pitch) {
        if (!available) {
            return;
        }
        try {
            Object listener = sender.listenerOf(guest);
            held.put(guest.getUniqueId(), new Hold(rotationPacket.newInstance(yaw, false, pitch, false), listener,
                    connection.get(listener), System.nanoTime() + LAPSE_NANOS));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            held.remove(guest.getUniqueId());
            return;
        }
        if (turner == null) {
            turner = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "CamFly follow hold");
                thread.setDaemon(true);
                return thread;
            });
            turner.scheduleAtFixedRate(this::turnBack, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Lets the guest go at once. */
    void release(Player guest) {
        held.remove(guest.getUniqueId());
    }

    /** When the plugin is switched off or reloaded: nobody is held, and nothing turns them any more. */
    void stop() {
        held.clear();
        if (turner != null) {
            turner.shutdownNow();
            turner = null;
        }
    }

    /** Turns every guest held back, on the thread of its own. */
    private void turnBack() {
        long now = System.nanoTime();
        for (Map.Entry<UUID, Hold> entry : held.entrySet()) {
            Hold hold = entry.getValue();
            if (now - hold.until() > 0L) {
                held.remove(entry.getKey(), hold);
                continue;
            }
            try {
                // Only while the connection is still at his game: between
                // two (a change of the game's settings, say) the packet would
                // not fit.
                if (packetListener.invoke(hold.connection()) == hold.listener()) {
                    writeAndFlush.invoke(channel.get(hold.connection()), hold.packet());
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Not sent: the next tick turns him back.
            }
        }
    }
}
