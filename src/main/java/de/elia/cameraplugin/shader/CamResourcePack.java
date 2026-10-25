package de.elia.cameraplugin.shader;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Gets CamFly's resource pack with the shader of {@code signal.noise: camfly}
 * to the players, the section {@code resource-pack}.
 *
 * <p>The pack is put together out of {@code signal.shader} whenever the
 * config file is read, and written to {@code plugins/CamFly/resourcepack/CamFly.zip}
 * for whoever wants to hand it out himself. With {@code send: built-in}
 * CamFly serves it itself and sends every player the address when he joins:
 * over the port of the Minecraft server with {@code port: game}, see
 * {@link GamePortServer}, or from a web server on a port of its own; with
 * {@code url} it sends the address written there; with {@code false} it
 * sends nothing and takes for granted that the players have the shader some
 * other way.</p>
 *
 * <p>Whether a player can show the shader is known only from his client: it
 * says when it has loaded the pack, or that it would not have it. Until it
 * has, the noise of a weak signal falls back to {@code signal.noise-fallback},
 * see {@link #hasShader(Player)} - an effect the client does not know would
 * show nothing at all.</p>
 *
 * <p>A client that could not download the pack says only that, not why. So
 * the console is told which address it was sent, and whether a request from
 * that player ever reached CamFly: one that never did means the address
 * cannot be reached from where the player is.</p>
 */
public final class CamResourcePack implements Listener {

    /**
     * The id the pack is sent under. Always the same, so that a new version
     * of it takes the place of the old one on the client instead of joining
     * it, and so that it can be told apart from the packs of other plugins.
     */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("CamFly resource pack".getBytes(StandardCharsets.UTF_8));

    /** Where the pack is written, under the plugin's folder. */
    private static final String FILE = "resourcepack/CamFly.zip";

    /** {@code Player#getVirtualHost()} of Paper, the address the player connected with; {@code null} on Spigot. */
    private static final Method VIRTUAL_HOST = virtualHost();

    /**
     * How many addresses {@link #answers} keeps before it starts over: enough
     * for everybody joining at once, and no more than that, whoever sends the
     * web server requests.
     */
    private static final int MAX_ANSWERS = 1000;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;
    /** The pack as it was put together last, or {@code null} while there is none to hand out. */
    private ShaderPack pack;
    /** The web server of {@code send: built-in}, or {@code null}. */
    private PackServer server;
    /** The port {@link #server} listens on. */
    private int serverPort;
    /** What hands the pack out over the port of the game, {@code port: game}, or {@code null}. */
    private GamePortServer gamePort;
    /** Why the pack cannot go out over the port of the game, or {@code null} while it can. */
    private String gamePortMissing;
    /** The players the pack was sent to, so that they can be told when it changes or is gone. */
    private final Set<UUID> offered = new HashSet<>();
    /** The players whose client said it has loaded the pack. */
    private final Set<UUID> loaded = new HashSet<>();
    /** The address each player was sent, for the console when the download fails. */
    private final Map<UUID, String> sentUrls = new HashMap<>();
    /**
     * Who asked CamFly for the pack lately, and with which status the request
     * was answered - written from the threads that answer them.
     */
    private final Map<InetAddress, Integer> answers = new ConcurrentHashMap<>();
    /** Whether the console has been told already that there is no address to send. */
    private boolean warnedAddress;
    /**
     * Everything that decides what a player is sent, as it was when the pack
     * went out last: when any of it changes, everybody is sent the pack anew.
     */
    private String sentAs = "";

    public CamResourcePack(CameraPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.log = log;
    }

    /**
     * Puts the pack together after the config file was read, starts or stops
     * the web server as {@code send} asks for, and hands a pack that has
     * changed to everybody online.
     */
    public void reload() {
        warnedAddress = false;
        if (!settings.usesCamflyShader()) {
            pack = null;
            sentAs = "";
            stopServing();
            withdraw();
            return;
        }
        ShaderPack built;
        try {
            built = ShaderPack.build(settings.getShaderLook());
            write(built);
        } catch (IOException ex) {
            log.log(Level.WARNING, "The resource pack with the shader of CamFly could not be put together: " + ex);
            pack = null;
            sentAs = "";
            stopServing();
            withdraw();
            return;
        }
        boolean changed = pack == null || !pack.sha1().equals(built.sha1());
        pack = built;
        if (settings.getPackSending() != PackSending.BUILT_IN) {
            stopServing();
        } else if (settings.isPackOnGamePort()) {
            stopServer();
            serveOnGamePort();
        } else {
            stopGamePort();
            if (changed || server == null || serverPort != settings.getPackPort()) {
                stopServer();
                startServer();
            }
        }
        if (settings.getPackSending() == PackSending.OFF) {
            sentAs = "";
            withdraw();
            return;
        }
        String now = String.join("|", pack.sha1(), settings.getPackSending().name(),
                String.valueOf(settings.getPackPort()), settings.getPackHost(),
                String.valueOf(settings.getPackHostPort()), settings.getPackUrl(),
                String.valueOf(settings.isPackRequired()));
        if (!now.equals(sentAs)) {
            sentAs = now;
            loaded.clear();
            answers.clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                send(player);
            }
        }
    }

    /** Stops handing out the pack, when the plugin is switched off. */
    public void onDisable() {
        stopServing();
    }

    /**
     * Whether this player can show the shader of CamFly: his client has loaded
     * the pack - or, with {@code send: false}, he is taken to have it.
     */
    public boolean hasShader(Player player) {
        return pack != null && (settings.getPackSending() == PackSending.OFF
                || loaded.contains(player.getUniqueId()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (pack != null && settings.getPackSending() != PackSending.OFF) {
            send(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        offered.remove(event.getPlayer().getUniqueId());
        loaded.remove(event.getPlayer().getUniqueId());
        sentUrls.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                loaded.add(id);
                answerTo(player);
            }
            case ACCEPTED, DOWNLOADED -> {
                // On its way, not there yet.
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                loaded.remove(id);
                reportFailure(player, event.getStatus());
            }
            default -> loaded.remove(id);
        }
    }

    /**
     * Tells the console why the pack did not reach a player, as far as it can
     * be told from here.
     */
    private void reportFailure(Player player, PlayerResourcePackStatusEvent.Status status) {
        String url = sentUrls.getOrDefault(player.getUniqueId(), "?");
        Integer answer = answerTo(player);
        String why;
        if (status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
            why = "the game downloaded it but could not load it - is it older than 26.3?";
        } else if (status == PlayerResourcePackStatusEvent.Status.INVALID_URL) {
            why = "the game does not take " + url + " for an address. Check resource-pack."
                    + (settings.getPackSending() == PackSending.URL ? "url" : "address") + ".";
        } else if (settings.getPackSending() == PackSending.URL) {
            why = "the game could not download it from " + url + ". Can that address be opened in a browser?";
        } else if (answer == null) {
            why = "the game could not download it from " + url + ", and no request from that player ever reached"
                    + " CamFly - that address cannot be reached from where the player is. " + (settings.isPackOnGamePort()
                    ? "Set resource-pack.address to the address the players join with. Behind BungeeCord or Velocity"
                    + " the pack needs a port of its own (resource-pack.port) or send: url."
                    : "Open or forward port " + serverPort + " like the port of the server, or set"
                    + " resource-pack.port: game.");
        } else if (answer == 200) {
            why = "CamFly sent the file from " + url + ", but the game says the download failed.";
        } else {
            why = "the request for " + url + " was answered with " + answer + ".";
        }
        log.log(Level.WARNING, "The resource pack of CamFly did not reach " + player.getName() + ": " + why);
    }

    /** How the last request from the address of this player was answered, forgotten with that. */
    private Integer answerTo(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : answers.remove(address.getAddress());
    }

    /** Notes how a request for the pack was answered; called from the threads that answer them. */
    private void answered(InetAddress from, int status) {
        if (answers.size() >= MAX_ANSWERS) {
            answers.clear();
        }
        answers.put(from, status);
    }

    /** Sends the pack to one player, under the address it can be fetched from. */
    private void send(Player player) {
        String url = url(player);
        if (url == null) {
            return;
        }
        // The address of send: url may hold a pack somebody put together
        // himself, with the shader among other things: its checksum is not
        // known here, and the client would turn down a pack that does not
        // match it.
        byte[] hash = settings.getPackSending() == PackSending.BUILT_IN ? pack.sha1Bytes() : null;
        String prompt = messages.isMessageEnabled("resource-pack-prompt")
                ? messages.getMessage("resource-pack-prompt")
                : "";
        player.addResourcePack(PACK_ID, url, hash, prompt.isEmpty() ? null : prompt, settings.isPackRequired());
        offered.add(player.getUniqueId());
        sentUrls.put(player.getUniqueId(), url);
    }

    /** Takes the pack off everybody it was sent to, now that it is not wanted any more. */
    private void withdraw() {
        for (UUID id : offered) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.removeResourcePack(PACK_ID);
            }
        }
        offered.clear();
        loaded.clear();
        sentUrls.clear();
    }

    /**
     * Where this player fetches the pack: the address of {@code send: url},
     * or with {@code built-in} the address in {@code resource-pack.address},
     * or else the one the player connected with, or else {@code server-ip} of
     * the server. The port is the one after that address if it names one,
     * else that of the web server - or with {@code port: game} the one the
     * player connected to, and else that of the server.
     *
     * @return the address, or {@code null} when there is none to give
     */
    private String url(Player player) {
        if (settings.getPackSending() == PackSending.URL) {
            String url = settings.getPackUrl();
            if (url.isEmpty()) {
                warnAddress("resource-pack.send is url, but resource-pack.url is empty");
                return null;
            }
            return url;
        }
        boolean onGamePort = settings.isPackOnGamePort();
        if (onGamePort ? gamePort == null : server == null) {
            warnAddress(onGamePort ? gamePortMissing : "its web server is not running, see above");
            return null;
        }
        InetSocketAddress joined = connectedWith(player);
        String host = settings.getPackHost();
        if (host.isEmpty() && joined != null) {
            host = joined.getHostString();
        }
        if (host.isEmpty()) {
            host = Bukkit.getIp();
        }
        if (host == null || host.isEmpty()) {
            warnAddress("resource-pack.address is empty, and the address players connect with is not known here"
                    + " - set it to the address of the server");
            return null;
        }
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = settings.getPackHostPort();
        if (port == 0) {
            port = !onGamePort ? serverPort : joined != null && joined.getPort() > 0 ? joined.getPort() : Bukkit.getPort();
        }
        return "http://" + host + ":" + port + PackServer.path(pack);
    }

    /** The address and port the player typed to connect, as far as the server knows them - on Paper only. */
    private static InetSocketAddress connectedWith(Player player) {
        if (VIRTUAL_HOST == null) {
            return null;
        }
        try {
            Object address = VIRTUAL_HOST.invoke(player);
            return address instanceof InetSocketAddress socket && !socket.getHostString().isEmpty() ? socket : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static Method virtualHost() {
        try {
            return Player.class.getMethod("getVirtualHost");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Tells the console once per reading of the config file why no pack goes out. */
    private void warnAddress(String reason) {
        if (!warnedAddress) {
            warnedAddress = true;
            log.log(Level.WARNING, "The resource pack with the shader of CamFly is not sent: " + reason + ".");
        }
    }

    /**
     * Hands the pack out over the port of the game, {@code port: game}: has
     * Paper show CamFly the connections of the server unless it does
     * already.
     */
    private void serveOnGamePort() {
        if (gamePort == null) {
            try {
                gamePort = GamePortServer.install(this::answered);
                gamePortMissing = null;
                log.log(Level.INFO, "The resource pack with the shader of CamFly is handed out over the port of"
                        + " the server.");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                gamePortMissing = "resource-pack.port: game needs Paper, which shows CamFly the connections of"
                        + " the server - give the pack a port of its own, such as resource-pack.port: 8164, or use"
                        + " send: url";
                warnAddress(gamePortMissing);
                return;
            }
        }
        gamePort.serve(pack);
    }

    private void stopGamePort() {
        if (gamePort != null) {
            gamePort.uninstall();
            gamePort = null;
        }
    }

    private void stopServing() {
        stopServer();
        stopGamePort();
    }

    private void startServer() {
        int port = settings.getPackPort();
        try {
            server = PackServer.start(port, pack, this::answered);
            serverPort = port;
            log.log(Level.INFO, "The resource pack with the shader of CamFly is served on port " + port + ".");
        } catch (IOException | RuntimeException | LinkageError ex) {
            server = null;
            log.log(Level.WARNING, "The web server for the resource pack of CamFly could not start on port " + port
                    + ": " + ex + ". Use another port, resource-pack.port: game, or resource-pack.send: url.");
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** Writes the pack where whoever wants to hand it out himself finds it. */
    private void write(ShaderPack built) throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve(FILE);
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.write(part, built.bytes());
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
