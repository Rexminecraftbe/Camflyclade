package de.elia.cameraplugin.shader;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.ObjIntConsumer;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Hands out the resource pack over the port of the Minecraft server itself,
 * for {@code resource-pack.port: game}: whoever reaches the server reaches
 * the pack as well, and no other port has to be opened or forwarded for it.
 *
 * <p>Every new connection to the server is looked at here first. A Minecraft
 * client opens with the length of its first packet, a web request with
 * {@code GET } or {@code HEAD }: such a request is answered here and closed
 * before the game sees a byte of it, the way {@link PackServer} answers it.
 * Anything else goes on to the game untouched, and the look is taken out of
 * its way - after a byte or two, as no packet length starts like a web
 * request.</p>
 *
 * <p>Paper hands every new connection to {@code ChannelInitializeListenerHolder},
 * the way ViaVersion gets at them. It is no part of Paper's API and is reached
 * by reflection; Spigot has nothing of the kind.</p>
 */
final class GamePortServer {

    /** The name of the look in the pipeline of a connection. */
    private static final String HANDLER = "camfly_resource_pack";

    /** Under which key the look is registered with Paper. */
    private static final String KEY = "camfly:resource_pack";

    /**
     * The handler of the game that the look goes in front of: the answer to
     * the server list ping of old clients, or where there is none, the one
     * that cuts the packets apart. Whatever stands before them - Paper's
     * reader of the proxy protocol, say - has had its turn by then.
     */
    private static final String[] BEFORE = {"legacy_query", "splitter"};

    /** How a web request starts that is answered here. */
    private static final byte[][] STARTS = {"GET ".getBytes(US_ASCII), "HEAD ".getBytes(US_ASCII)};

    /** How the head of a web request ends. */
    private static final byte[] HEAD_END = "\r\n\r\n".getBytes(US_ASCII);

    /** How long the head of a request may get, in bytes: a longer one is no download of a client. */
    private static final int MAX_HEAD = 8192;

    /** {@code ChannelInitializeListenerHolder#removeListener(Key)}. */
    private final Method remove;
    /** The key the look is registered under, a {@code Key} of Adventure. */
    private final Object key;
    /** Told whom a request was answered with which status, see {@link CamResourcePack}. */
    private final ObjIntConsumer<InetAddress> answered;
    /** The pack being handed out, or {@code null} while there is none. */
    private volatile ShaderPack pack;

    private GamePortServer(Method remove, Object key, ObjIntConsumer<InetAddress> answered) {
        this.remove = remove;
        this.key = key;
        this.answered = answered;
    }

    /**
     * Has Paper show CamFly every new connection from now on.
     *
     * @throws ReflectiveOperationException on a server without
     *                                      {@code ChannelInitializeListenerHolder}
     */
    static GamePortServer install(ObjIntConsumer<InetAddress> answered) throws ReflectiveOperationException {
        Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
        Class<?> listenerType = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
        Class<?> keyType = Class.forName("net.kyori.adventure.key.Key");
        Object key = keyType.getMethod("key", String.class).invoke(null, KEY);
        Method add = holder.getMethod("addListener", keyType, listenerType);
        GamePortServer server = new GamePortServer(holder.getMethod("removeListener", keyType), key, answered);
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "afterInitChannel" -> {
                        server.look((Channel) args[0]);
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "CamFly resource pack";
                    default -> null;
                });
        add.invoke(null, key, listener);
        return server;
    }

    /** Hands out this pack from now on, or none with {@code null}. */
    void serve(ShaderPack pack) {
        this.pack = pack;
    }

    /**
     * Stops looking at new connections. One that is already being looked at
     * is answered with 404 from now on, should it turn out to be a web
     * request.
     */
    void uninstall() {
        pack = null;
        try {
            remove.invoke(null, key);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Gone with the server already.
        }
    }

    /** Puts the look into the pipeline of a new connection, in front of the game. */
    private void look(Channel channel) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            for (String before : BEFORE) {
                if (pipeline.get(before) != null) {
                    pipeline.addBefore(before, HANDLER, new Look());
                    return;
                }
            }
        } catch (RuntimeException ex) {
            // A pipeline that is not the game's: no look, the game goes on as ever.
        }
    }

    /**
     * Whether the bytes so far are the start of a web request.
     *
     * @return {@code null} while there are too few of them to tell
     */
    private static Boolean webRequest(ByteBuf in) {
        boolean undecided = false;
        for (byte[] start : STARTS) {
            int length = Math.min(start.length, in.readableBytes());
            boolean same = true;
            for (int i = 0; i < length && same; i++) {
                same = in.getByte(in.readerIndex() + i) == start[i];
            }
            if (same && length == start.length) {
                return true;
            }
            undecided |= same;
        }
        return undecided ? null : false;
    }

    /** Where the head of the request ends, as an index into {@code in}, or -1 while it goes on. */
    private static int headEnd(ByteBuf in) {
        for (int i = in.readerIndex(); i + HEAD_END.length <= in.writerIndex(); i++) {
            boolean same = true;
            for (int j = 0; j < HEAD_END.length && same; j++) {
                same = in.getByte(i + j) == HEAD_END[j];
            }
            if (same) {
                return i;
            }
        }
        return -1;
    }

    /** The look at one connection, until it is clear whose it is. */
    private final class Look extends ByteToMessageDecoder {

        /** Whether the request has been answered: whatever comes after it is dropped. */
        private boolean done;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (done) {
                in.skipBytes(in.readableBytes());
                return;
            }
            Boolean web = webRequest(in);
            if (web == null) {
                return;
            }
            if (!web) {
                // The game's: what has come so far goes on to it as it came.
                ctx.pipeline().remove(this);
                return;
            }
            // The whole head is read before the answer, all of it: a
            // connection closed with something left unread is torn down
            // instead of closed, and the client may lose the answer with it.
            int end = headEnd(in);
            if (end < 0) {
                if (in.readableBytes() > MAX_HEAD) {
                    done = true;
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                }
                return;
            }
            String head = in.toString(in.readerIndex(), end - in.readerIndex(), US_ASCII);
            in.skipBytes(in.readableBytes());
            done = true;
            answer(ctx, head);
        }

        private void answer(ChannelHandlerContext ctx, String head) {
            int lineEnd = head.indexOf('\r');
            String[] request = (lineEnd < 0 ? head : head.substring(0, lineEnd)).split(" ");
            String path = request.length > 1 ? request[1] : "";
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            ShaderPack served = pack;
            boolean found = served != null && path.equals(PackServer.path(served));
            byte[] body = found && !request[0].equals("HEAD") ? served.bytes() : new byte[0];
            String reply = (found ? "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" : "HTTP/1.1 404 Not Found\r\n")
                    + "Content-Length: " + (found ? served.bytes().length : 0) + "\r\n"
                    + "Connection: close\r\n\r\n";
            ctx.writeAndFlush(Unpooled.wrappedBuffer(reply.getBytes(US_ASCII), body))
                    .addListener(ChannelFutureListener.CLOSE);
            if (ctx.channel().remoteAddress() instanceof InetSocketAddress from) {
                answered.accept(from.getAddress(), found ? 200 : 404);
            }
        }
    }
}
