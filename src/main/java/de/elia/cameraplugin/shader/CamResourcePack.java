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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gets CamFly's resource pack with the shader of {@code signal.noise: camfly}
 * to the players, the section {@code resource-pack}.
 *
 * <p>The pack is put together out of {@code signal.shader} whenever the
 * config file is read, and written to {@code plugins/CamFly/resourcepack/CamFly.zip}
 * for whoever wants to hand it out himself. With {@code send: built-in}
 * CamFly serves it from a web server of its own and sends every player the
 * address when he joins; with {@code url} it sends the address written
 * there; with {@code false} it sends nothing and takes for granted that the
 * players have the shader some other way.</p>
 *
 * <p>Whether a player can show the shader is known only from his client: it
 * says when it has loaded the pack, or that it would not have it. Until it
 * has, the noise of a weak signal falls back to {@code signal.noise-fallback},
 * see {@link #hasShader(Player)} - an effect the client does not know would
 * show nothing at all.</p>
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
    /** The players the pack was sent to, so that they can be told when it changes or is gone. */
    private final Set<UUID> offered = new HashSet<>();
    /** The players whose client said it has loaded the pack. */
    private final Set<UUID> loaded = new HashSet<>();
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
            stopServer();
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
            stopServer();
            withdraw();
            return;
        }
        boolean changed = pack == null || !pack.sha1().equals(built.sha1());
        pack = built;
        if (settings.getPackSending() == PackSending.BUILT_IN) {
            if (changed || server == null || serverPort != settings.getPackPort()) {
                stopServer();
                startServer();
            }
        } else {
            stopServer();
        }
        if (settings.getPackSending() == PackSending.OFF) {
            sentAs = "";
            withdraw();
            return;
        }
        String now = String.join("|", pack.sha1(), settings.getPackSending().name(),
                String.valueOf(settings.getPackPort()), settings.getPackAddress(), settings.getPackUrl(),
                String.valueOf(settings.isPackRequired()));
        if (!now.equals(sentAs)) {
            sentAs = now;
            loaded.clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                send(player);
            }
        }
    }

    /** Stops the web server, when the plugin is switched off. */
    public void onDisable() {
        stopServer();
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(id);
            case ACCEPTED, DOWNLOADED -> {
                // On its way, not there yet.
            }
            default -> loaded.remove(id);
        }
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
    }

    /**
     * Where this player fetches the pack: the address of {@code send: url},
     * or that of the web server of {@code built-in} - under
     * {@code resource-pack.address}, or else the address he connected with,
     * or else {@code server-ip} of the server.
     *
     * @return the address, or {@code null} when there is none to give him
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
        if (server == null) {
            warnAddress("its web server is not running, see above");
            return null;
        }
        String host = settings.getPackAddress();
        if (host.isEmpty()) {
            host = connectedWith(player);
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
        return "http://" + host + ":" + serverPort + PackServer.path(pack);
    }

    /** The address the player typed to connect, as far as the server knows it - on Paper only. */
    private static String connectedWith(Player player) {
        if (VIRTUAL_HOST == null) {
            return "";
        }
        try {
            Object address = VIRTUAL_HOST.invoke(player);
            return address instanceof InetSocketAddress socket ? socket.getHostString() : "";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "";
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

    private void startServer() {
        int port = settings.getPackPort();
        try {
            server = PackServer.start(port, pack);
            serverPort = port;
            log.log(Level.INFO, "The resource pack with the shader of CamFly is served on port " + port + ".");
        } catch (IOException | RuntimeException | LinkageError ex) {
            server = null;
            log.log(Level.WARNING, "The web server for the resource pack of CamFly could not start on port " + port
                    + ": " + ex + ". Use another port, or resource-pack.send: url.");
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
