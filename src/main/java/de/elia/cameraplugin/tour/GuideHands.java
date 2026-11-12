package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * The guide's hands as the server sends them to a guest, changed on their way
 * to him: what the guests do not see of the guide's items - the count, the
 * enchantments, the wear, see {@link GuideMirror} - never reaches his client.
 *
 * <p>The server sends everybody who sees the guide what is in the guide's
 * hands, whenever it changes, and all of it whenever a client gets to know
 * the guide anew. Seen through the guide's eyes, that fills the hotbar a
 * guest's client draws. Sent once more right after, as the guests are shown
 * them, the real ones would still be there for a moment now and then - the
 * client may draw a picture in between. So the packets are changed before
 * they leave: a handler of the plugin's own sits in the guest's connection,
 * right before the server turns the packets into bytes, and takes the guide's
 * hands out of them as they are and puts them back as the guests are shown
 * them. Nothing else of what goes to the guest, and nothing of what goes to
 * anybody else, is touched. Once the guest is off the tour, the handler is
 * taken out again.</p>
 *
 * <p>The connection is Netty's, which the API does not offer, nor do the
 * packets: all of it goes through the internals of the server, see
 * {@link PacketSender}, and the handler is a {@link Proxy} of Netty's
 * interface. Internals are no API: when a name is gone in an update of the
 * game, the guide's hands go out to the guests as shown only after the
 * server's, at the end of the tick, see {@link GuideMirror} - the start says
 * so once in the console -, and nothing else changes.</p>
 *
 * <p>The handler runs on the thread of the connection, not the server's: what
 * it reads of the tour, the guide's number, is kept where that thread sees
 * every change.</p>
 */
final class GuideHands {

    /** The name of the plugin's handler in a connection. */
    private static final String NAME = "camfly_guide_hands";

    /**
     * The handler that turns packets into bytes: the plugin's goes right
     * before it, where the packets still are packets - and those the server
     * bundles, single ones.
     */
    private static final String ENCODER = "encoder";

    /** The packet with what is in an entity's hands and on its body. */
    private static final String EQUIPMENT_PACKET = "net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket";

    /** Packets the client handles all at once - all of an entity it gets to know, say. */
    private static final String BUNDLE = "net.minecraft.network.protocol.BundlePacket";

    /** Such a bundle, as the server sends it in the game. */
    private static final String BUNDLE_PACKET = "net.minecraft.network.protocol.game.ClientboundBundlePacket";

    /** The listener of a player's connection, which holds the connection itself. */
    private static final String LISTENER = "net.minecraft.server.network.ServerCommonPacketListenerImpl";

    /** A slot and its item, in the equipment packet. */
    private static final String PAIR = "com.mojang.datafixers.util.Pair";

    /** Turns items of the server into items of the API and back. */
    private static final String CRAFT_ITEM_STACK = "org.bukkit.craftbukkit.inventory.CraftItemStack";

    /** An item of the server. */
    private static final String ITEM_STACK = "net.minecraft.world.item.ItemStack";

    /** What Netty hands the packets on their way out to. */
    private static final String OUTBOUND = "io.netty.channel.ChannelOutboundHandler";

    /** Any handler of Netty, what the connection takes. */
    private static final String HANDLER = "io.netty.channel.ChannelHandler";

    /** Where a handler is in the connection, which passes the packets on. */
    private static final String CONTEXT = "io.netty.channel.ChannelHandlerContext";

    /** The hands, by the names of the server's slots. */
    private static final Set<String> HANDS = Set.of("MAINHAND", "OFFHAND");

    private final PacketSender sender;
    /** The handler of each guest whose packets are changed, by his id. Of the server's thread only. */
    private final Map<UUID, Watch> watches = new HashMap<>();
    /** The guests whose connection did not take the handler: tried no more until they are off the tour. */
    private final Set<UUID> refused = new HashSet<>();

    private Class<?> equipmentPacket;
    private Method entity;
    private Method slots;
    private Constructor<?> equipment;
    private Class<?> bundlePacket;
    private Method subPackets;
    private Constructor<?> bundle;
    private Method first;
    private Method second;
    private Method pairOf;
    private Method asBukkit;
    private Method asServer;
    /** From the listener of a player's connection to the connection, and on to Netty's channel. */
    private Field connection;
    private Field channel;
    private Method pipeline;
    private Method get;
    private Method addAfter;
    private Method remove;
    /** Netty's interface the handler is a proxy of. */
    private Class<?> outbound;
    /** Where each of its methods passes on to, in the handler's place in the connection. */
    private Map<Method, Method> passOn;
    /** Passes on an exception, where the handler has no method to pass it on with. */
    private Method passOnException;
    /** Whether all of it was found. */
    private volatile boolean available;

    GuideHands(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches the changing off; on Paper, where it should be there,
     * the console hears about it once.
     *
     * @param log where to say so, or {@code null} while the guests see all
     *            of the guide's items anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            equipmentPacket = PacketSender.internal(EQUIPMENT_PACKET);
            entity = equipmentPacket.getMethod("getEntity");
            slots = equipmentPacket.getMethod("getSlots");
            equipment = equipmentPacket.getConstructor(int.class, List.class);
            subPackets = PacketSender.internal(BUNDLE).getMethod("subPackets");
            bundlePacket = PacketSender.internal(BUNDLE_PACKET);
            bundle = bundlePacket.getConstructor(Iterable.class);
            Class<?> pair = PacketSender.internal(PAIR);
            first = pair.getMethod("getFirst");
            second = pair.getMethod("getSecond");
            pairOf = pair.getMethod("of", Object.class, Object.class);
            Class<?> craftItem = PacketSender.internal(CRAFT_ITEM_STACK);
            asBukkit = craftItem.getMethod("asBukkitCopy", PacketSender.internal(ITEM_STACK));
            asServer = craftItem.getMethod("asNMSCopy", ItemStack.class);
            connection = PacketSender.internal(LISTENER).getField("connection");
            channel = connection.getType().getField("channel");
            pipeline = channel.getType().getMethod("pipeline");
            Class<?> handler = PacketSender.internal(HANDLER);
            Class<?> pipelineType = pipeline.getReturnType();
            get = pipelineType.getMethod("get", String.class);
            addAfter = pipelineType.getMethod("addAfter", String.class, String.class, handler);
            remove = pipelineType.getMethod("remove", String.class);
            outbound = PacketSender.internal(OUTBOUND);
            Class<?> context = PacketSender.internal(CONTEXT);
            Map<Method, Method> to = new HashMap<>();
            for (Method method : outbound.getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 0 || types[0] != context) {
                    continue;
                }
                try {
                    to.put(method, context.getMethod(method.getName(), Arrays.copyOfRange(types, 1, types.length)));
                } catch (NoSuchMethodException ex) {
                    // handlerAdded, handlerRemoved, exceptionCaught: see Watch.
                }
            }
            if (to.keySet().stream().noneMatch(method -> method.getName().equals("write"))) {
                throw new NoSuchMethodException(OUTBOUND + ".write");
            }
            passOn = to;
            passOnException = context.getMethod("fireExceptionCaught", Throwable.class);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow may see what is hidden of their leader's items for a"
                        + " moment on this version of the server: " + ex);
            }
        }
    }

    /**
     * Has the guide's hands changed on their way to this guest from now on,
     * by {@code shown} - or, already so, only to the guide by this number.
     *
     * @param guide the number of the guide's entity
     * @param shown the item as the guests are shown it; the same item where
     *              nothing of it is hidden. Called on the connection's thread.
     * @return whether they are changed on their way: {@code false} where that
     *         does not work here
     */
    boolean watch(Player guest, int guide, UnaryOperator<ItemStack> shown) {
        if (!available || refused.contains(guest.getUniqueId())) {
            return false;
        }
        Watch watch = watches.get(guest.getUniqueId());
        if (watch != null) {
            watch.guide = guide;
            return true;
        }
        try {
            Object line = pipeline.invoke(channel.get(connection.get(sender.listenerOf(guest))));
            // One left over by the plugin before a reload: out first.
            if (get.invoke(line, NAME) != null) {
                remove.invoke(line, NAME);
            }
            watch = new Watch(line, guide, shown);
            watch.handler = Proxy.newProxyInstance(outbound.getClassLoader(), new Class<?>[] {outbound}, watch);
            addAfter.invoke(line, ENCODER, NAME, watch.handler);
            watches.put(guest.getUniqueId(), watch);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            refused.add(guest.getUniqueId());
            return false;
        }
    }

    /** Takes the handler out of this guest's connection again: what goes to him goes as it is. */
    void unwatch(Player guest) {
        Watch watch = watches.remove(guest.getUniqueId());
        refused.remove(guest.getUniqueId());
        if (watch == null) {
            return;
        }
        watch.guide = -1;
        try {
            if (get.invoke(watch.line, NAME) == watch.handler) {
                remove.invoke(watch.line, NAME);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Gone with the connection, or never to change anything again.
        }
    }

    /** A guest who left the server: his connection is gone, and the handler with it. */
    void forget(UUID guest) {
        Watch watch = watches.remove(guest);
        refused.remove(guest);
        if (watch != null) {
            watch.guide = -1;
        }
    }

    /**
     * The handler in one guest's connection: Netty's methods, each passed on
     * to the next handler as it came - only the packets on their way out
     * changed, where they hold the guide's hands.
     */
    private final class Watch implements InvocationHandler {

        /** The guest's connection, Netty's pipeline. */
        final Object line;
        /** The proxy that is the handler there. */
        Object handler;
        /** The number of the guide's entity, -1 once nothing is to change any more. */
        volatile int guide;
        final UnaryOperator<ItemStack> shown;

        Watch(Object line, int guide, UnaryOperator<ItemStack> shown) {
            this.line = line;
            this.guide = guide;
            this.shown = shown;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> NAME;
                };
            }
            Method to = passOn.get(method);
            try {
                if (to == null) {
                    if (method.getName().equals("exceptionCaught")) {
                        passOnException.invoke(args[0], args[1]);
                    }
                    return null;
                }
                Object[] rest = Arrays.copyOfRange(args, 1, args.length);
                if (method.getName().equals("write") && rest.length > 0) {
                    rest[0] = changed(rest[0]);
                }
                to.invoke(args[0], rest);
                return null;
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        /** The packet as it goes on: with the guide's hands as shown, where it holds them. */
        private Object changed(Object packet) {
            int id = guide;
            if (id < 0) {
                return packet;
            }
            try {
                if (equipmentPacket.isInstance(packet)) {
                    return hands(packet, id);
                }
                if (bundlePacket.isInstance(packet)) {
                    List<Object> packets = new ArrayList<>();
                    boolean changed = false;
                    for (Object one : (Iterable<?>) subPackets.invoke(packet)) {
                        Object now = equipmentPacket.isInstance(one) ? hands(one, id) : one;
                        changed |= now != one;
                        packets.add(now);
                    }
                    return changed ? bundle.newInstance(packets) : packet;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Goes as it is rather than not at all.
            }
            return packet;
        }

        /** An equipment packet with the guide's hands as shown, if it is the guide's and anything is hidden. */
        private Object hands(Object packet, int id) throws ReflectiveOperationException {
            if ((int) entity.invoke(packet) != id) {
                return packet;
            }
            List<?> all = (List<?>) slots.invoke(packet);
            List<Object> now = new ArrayList<>(all.size());
            boolean changed = false;
            for (Object pair : all) {
                Object slot = first.invoke(pair);
                if (slot instanceof Enum<?> hand && HANDS.contains(hand.name())) {
                    ItemStack item = (ItemStack) asBukkit.invoke(null, second.invoke(pair));
                    ItemStack shownItem = shown.apply(item);
                    if (shownItem != item) {
                        pair = pairOf.invoke(null, slot, asServer.invoke(null, shownItem));
                        changed = true;
                    }
                }
                now.add(pair);
            }
            return changed ? equipment.newInstance(id, now) : packet;
        }
    }
}
