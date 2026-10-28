package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * The swing of a guide out of camera mode, on the screens of his guests,
 * {@code follow.mirror-swing}: when he swings his arm - hits, digs -, each
 * guest sees the arm that holds the guide's item swing, see
 * {@link GuideMirror}.
 *
 * <p>The game tells a client that an entity swings its arm with a packet.
 * Given the guest's own player, his client swings the arm it draws in front of
 * him. The API only swings an arm for everybody, so the packet is sent through
 * the internals of the server, see {@link PacketSender}, to the guest alone.
 * Which packet that is, the game changed: up to 26.2 it is
 * {@code ClientboundAnimatePacket} with a number for the hand - which since
 * 26.3 means something else, the first one waking a sleeper -, from 26.3 on
 * {@code ClientboundSwingAnimationPacket}, with the hand and how the item in
 * it swings. Whichever the server has, is used; the numbers are read from the
 * server, never assumed.</p>
 *
 * <p>Up to 26.2, his client then tells the server it swung, as after a click
 * of his own. The server lets nobody see that - nobody sees a guest who
 * follows closely -, and camera mode turns down the click that comes with it,
 * as any other. Only the arm swings: how the item sinks after a hit and comes
 * back up, a client works out only for hits of its own.</p>
 *
 * <p>Internals are no API: when a name is gone in an update of the game, the
 * guests no longer see the guide swing - the start says so once in the
 * console - and nothing else changes.</p>
 */
final class GuideSwing {

    /** The packet of a swing since 26.3: the entity, the hand and how it swings. */
    private static final String SWING_PACKET = "net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket";

    /** The packet of a swing up to 26.2: the entity and a number for what it does. */
    private static final String ANIMATE_PACKET = "net.minecraft.network.protocol.game.ClientboundAnimatePacket";

    /** What both packets take: an entity of the server. */
    private static final String ENTITY = "net.minecraft.world.entity.Entity";

    /** A living entity of the server, whose hands hold items. */
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";

    /** The hand that swings, since 26.3. */
    private static final String HAND = "net.minecraft.world.InteractionHand";

    /** How the item in a hand swings, since 26.3. */
    private static final String SWING_ANIMATION = "net.minecraft.world.item.component.SwingAnimation";

    private final PacketSender sender;
    /** Makes the packet of 26.3 on: entity, hand, how it swings; {@code null} up to 26.2. */
    private Constructor<?> swingPacket;
    /** The two hands of 26.3 on. */
    private Object mainHand;
    private Object offHand;
    /** The item a living entity holds in a hand, and how it swings at a hit and otherwise. */
    private Method itemInHand;
    private Method attackAnimation;
    private Method interactAnimation;
    /** Makes the packet up to 26.2: entity, number. */
    private Constructor<?> animatePacket;
    /** Its numbers for a swing of the hand and of the other hand. */
    private int mainHandAction;
    private int offHandAction;
    /** Whether all of it was found, one way or the other. */
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
        swingPacket = null;
        animatePacket = null;
        try {
            sender.load();
            Class<?> entity = PacketSender.internal(ENTITY);
            if (hasClass(SWING_PACKET)) {
                Class<?> hand = PacketSender.internal(HAND);
                swingPacket = PacketSender.internal(SWING_PACKET)
                        .getConstructor(entity, hand, PacketSender.internal(SWING_ANIMATION));
                mainHand = hand.getField("MAIN_HAND").get(null);
                offHand = hand.getField("OFF_HAND").get(null);
                itemInHand = PacketSender.internal(LIVING_ENTITY).getMethod("getItemInHand", hand);
                attackAnimation = itemInHand.getReturnType().getMethod("getAttackAnimation");
                interactAnimation = itemInHand.getReturnType().getMethod("getInteractAnimation");
            } else {
                Class<?> packet = PacketSender.internal(ANIMATE_PACKET);
                animatePacket = packet.getConstructor(entity, int.class);
                mainHandAction = packet.getField("SWING_MAIN_HAND").getInt(null);
                offHandAction = packet.getField("SWING_OFF_HAND").getInt(null);
            }
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow do not see their leader swing on this version of the"
                        + " server: " + ex);
            }
        }
    }

    private static boolean hasClass(String name) {
        try {
            PacketSender.internal(name);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /** Whether the swing works on this server. */
    boolean isAvailable() {
        return available;
    }

    /**
     * Swings the guest's arm on his own client as the guide swung his: the
     * arm of the item in his hand, or of the one in his other hand - since
     * 26.3 the way the guide's item swings.
     */
    void swing(Player guest, Player guide, boolean offHand) {
        if (!available) {
            return;
        }
        try {
            Object packet;
            if (swingPacket != null) {
                Object hand = offHand ? this.offHand : mainHand;
                Object item = itemInHand.invoke(sender.handleOf(guide), hand);
                Object animation = (offHand ? interactAnimation : attackAnimation).invoke(item);
                packet = swingPacket.newInstance(sender.handleOf(guest), hand, animation);
            } else {
                packet = animatePacket.newInstance(sender.handleOf(guest), offHand ? offHandAction : mainHandAction);
            }
            sender.send(guest, packet);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Not sent: he does not see this one swing.
        }
    }
}
