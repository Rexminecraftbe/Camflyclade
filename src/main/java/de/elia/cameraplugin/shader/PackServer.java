package de.elia.cameraplugin.shader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A web server of its own for {@code resource-pack.send: built-in}: it hands
 * out the resource pack of CamFly and nothing else.
 *
 * <p>One file under one path, {@code /CamFly-<sha1>.zip} - with the SHA-1
 * in the name a client never takes an old copy for the new one. Everything
 * else is answered with 404, and nothing but GET and HEAD is answered at
 * all. Two threads are enough: a pack is fetched once per player and
 * version of the pack, and the client keeps it.</p>
 */
final class PackServer {

    private final HttpServer server;
    private final ExecutorService threads;

    private PackServer(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    /**
     * Starts serving the pack on the port, on every address of the machine.
     *
     * @throws IOException when the port cannot be had, taken by something
     *                     else say
     */
    static PackServer start(int port, ShaderPack pack) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        String path = path(pack);
        byte[] bytes = pack.bytes();
        server.createContext("/", exchange -> answer(exchange, path, bytes));
        ExecutorService threads = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "CamFly resource pack");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(threads);
        server.start();
        return new PackServer(server, threads);
    }

    /** The path the pack is served under. */
    static String path(ShaderPack pack) {
        return "/CamFly-" + pack.sha1() + ".zip";
    }

    void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    private static void answer(HttpExchange exchange, String path, byte[] bytes) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!exchange.getRequestURI().getPath().equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        }
    }
}
