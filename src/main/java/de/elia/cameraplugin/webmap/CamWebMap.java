package de.elia.cameraplugin.webmap;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

/**
 * The public spots on the web map of the server, {@code web-map}: BlueMap,
 * Dynmap and squaremap, whichever of them is there. Each gets a layer of its
 * own - it can be switched off on the page of the map - with a pin for every
 * public spot; a click on it says whose it is, how often it was liked and
 * looked at, what it shows of its own, where it is and the command that
 * jumps there.
 *
 * <p>Only public spots, {@code /cam spot share <spot> all}: what is private,
 * or shared with single players, is for them alone, and the page of a map is
 * often open to anybody on the internet, not only to the players. Nothing
 * else of camera mode gets there either - no camera, no body.</p>
 *
 * <p>The pins follow the spots: one that becomes public appears, one that is
 * made private, taken down or deleted is gone, one that moves moves along -
 * the round after the change, see {@link #changed()}. The likes and views on
 * them are brought up to date every {@value #ROUND_SECONDS} seconds. A map that starts later than the plugin,
 * or anew, gets them as soon as it is there.</p>
 *
 * <p>The plugin is not built against any of the maps, see {@link Calls}: a
 * server without them needs nothing of theirs. Should a new version of a map
 * have changed what is called, the console says so once, and that map simply
 * gets no spots until the plugin is brought up to date - everything else
 * goes on.</p>
 */
public final class CamWebMap implements Listener {

    /** How often the pins are brought up to date, in seconds: their likes and views. */
    static final long ROUND_SECONDS = 30L;

    /** What the layer of the plugin is called inside each map. */
    private static final String LAYER_ID = "camfly-spots";

    /** The plugins of the maps, as the server knows them. */
    private static final String BLUEMAP = "BlueMap";
    private static final String DYNMAP = "dynmap";
    private static final String SQUAREMAP = "squaremap";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;
    /** The maps the spots are on right now. */
    private final List<MapLink> links = new ArrayList<>();
    private BukkitTask rounds;
    /** Whether a round is asked for in the next tick already. */
    private boolean queued;

    public CamWebMap(CameraPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.log = log;
    }

    /**
     * At the start and after a reload: links up with every map that is
     * there and switched on in {@code web-map}, and puts the spots on them.
     */
    public void start() {
        stop();
        if (!settings.isWebMapEnabled()) {
            return;
        }
        for (Plugin map : Bukkit.getPluginManager().getPlugins()) {
            if (map.isEnabled()) {
                link(map);
            }
        }
        rounds = Bukkit.getScheduler().runTaskTimer(plugin, this::round, ROUND_SECONDS * 20L, ROUND_SECONDS * 20L);
        changed();
    }

    /** Takes the spots off every map, at the end and before a reload. */
    public void stop() {
        if (rounds != null) {
            rounds.cancel();
            rounds = null;
        }
        for (MapLink link : links) {
            try {
                link.stop();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                log.log(Level.WARNING, "The spots could not be taken off " + link.plugin() + ": " + ex);
            }
        }
        links.clear();
    }

    /**
     * Something about the public spots changed - one became public or
     * private, moved, was liked, deleted, taken down -: the maps hear about
     * it in the next tick, once for everything that changes in this one.
     */
    public void changed() {
        if (queued || links.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        queued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            queued = false;
            round();
        });
    }

    /** A map that starts after the plugin, or anew, is linked up and gets the spots. */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (rounds != null && link(event.getPlugin())) {
            changed();
        }
    }

    /** A map that stops is no longer called. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        links.removeIf(link -> link.plugin().equals(event.getPlugin().getName()));
    }

    /**
     * Links up with a plugin if it is one of the maps and switched on in
     * {@code web-map}.
     *
     * @return whether it is linked up now
     */
    private boolean link(Plugin map) {
        String name = map.getName();
        for (MapLink link : links) {
            if (link.plugin().equals(name)) {
                return false;
            }
        }
        String layer = ChatColor.stripColor(messages.getMessage("web-map-layer"));
        boolean hidden = settings.isWebMapHidden();
        MapLink link;
        if (name.equals(BLUEMAP) && settings.isWebMapBlueMap()) {
            link = new BlueMapLink(plugin, map, LAYER_ID, layer, hidden, this::changed);
        } else if (name.equals(DYNMAP) && settings.isWebMapDynmap()) {
            link = new DynmapLink(map, LAYER_ID, layer, hidden);
        } else if (name.equals(SQUAREMAP) && settings.isWebMapSquaremap()) {
            link = new SquaremapLink(map, LAYER_ID, layer, hidden);
        } else {
            return false;
        }
        try {
            link.start();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            broken(link, ex);
            return false;
        }
        links.add(link);
        log.log(Level.INFO, "The public spots are shown on " + name + ".");
        return true;
    }

    /** Puts the spots as they are now on every map. */
    private void round() {
        if (links.isEmpty()) {
            return;
        }
        List<MapSpot> spots = collect();
        for (Iterator<MapLink> it = links.iterator(); it.hasNext(); ) {
            MapLink link = it.next();
            try {
                link.show(spots);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                it.remove();
                broken(link, ex);
            }
        }
    }

    /** Says once that a map could not be given the spots, and why; it gets none from now on. */
    private void broken(MapLink link, Throwable reason) {
        log.log(Level.WARNING, "The public spots cannot be shown on " + link.plugin()
                + " - its version may not fit this one of CamFly. It gets no spots until /cam reload: " + reason);
    }

    /**
     * The public spots as the maps show them: none while sharing is off or
     * the admin page keeps everybody from looking at public spots, and only
     * those liked at least {@code web-map.min-likes} times, looked at at
     * least {@code web-map.min-views} times and lying in a world that is
     * there.
     */
    private List<MapSpot> collect() {
        CamSpots spots = plugin.getSpots();
        SpotBans.Ban everybody = plugin.getSpotModeration().everybodyBan();
        if (!settings.isViewpointsSharing()
                || everybody != null && everybody.stage(SpotBans.Kind.PUBLIC) == SpotBans.Stage.NO_LOOK) {
            return List.of();
        }
        List<MapSpot> list = new ArrayList<>();
        for (Share share : spots.publicSpots()) {
            Spot spot = share.getSpot();
            if (share.likes() < settings.getWebMapMinLikes()
                    || spots.views(share.getOwner(), spot) < settings.getWebMapMinViews()
                    || Bukkit.getWorld(spot.world()) == null) {
                continue;
            }
            String label = ChatColor.stripColor(fill(messages.getMessage("web-map-label"), share, spots, false));
            list.add(new MapSpot("camfly-" + share.getOwner() + "-" + spot.key(), spot.world(),
                    spot.x(), spot.y(), spot.z(), label, detail(share, spots)));
        }
        return list;
    }

    /** What a click on a pin shows, as HTML: {@code web-map-detail} with its parts. */
    private String detail(Share share, CamSpots spots) {
        String look = spots.describe(share.getSpot().look());
        String text = fill(messages.getMessage("web-map-detail"), share, spots, true)
                .replace("{look}", look.isEmpty() ? ""
                        : text("web-map-detail-look", share, spots).replace("{look}", html(strip(look))));
        return strip(text);
    }

    /** A part of {@code web-map-detail}, filled in. */
    private String text(String key, Share share, CamSpots spots) {
        return fill(messages.getMessage(key), share, spots, true);
    }

    /**
     * Fills in what a text of the map says about a spot. In the HTML of a
     * click the names are written so that no sign in them counts as HTML -
     * a spot or a player cannot have one anyway, but the text should not
     * hang on that.
     */
    private String fill(String text, Share share, CamSpots spots, boolean asHtml) {
        Spot spot = share.getSpot();
        String owner = asHtml ? html(share.getOwnerName()) : share.getOwnerName();
        String name = asHtml ? html(spot.name()) : spot.name();
        return text.replace("{spot}", name)
                .replace("{owner}", owner)
                .replace("{likes}", String.valueOf(share.likes()))
                .replace("{views}", String.valueOf(spots.views(share.getOwner(), spot)))
                .replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                .replace("{world}", asHtml ? html(spot.world()) : spot.world())
                .replace("{command}", "/cam spot " + (asHtml ? html(share.label()) : share.label()));
    }

    /** A text of the language file without its colours: a web page has none of those. */
    private static String strip(String text) {
        String stripped = ChatColor.stripColor(text);
        return stripped == null ? "" : stripped;
    }

    /** Writes a text so that no sign in it counts as HTML. */
    static String html(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The address of a spot on the web map for the button of
     * {@code /cam spot list}, from {@code web-map.link}, or {@code null}
     * while there is none.
     */
    public String linkTo(Spot spot) {
        String link = settings.getWebMapLink();
        if (!settings.isWebMapEnabled() || link.isEmpty()) {
            return null;
        }
        return link.replace("{world}", spot.world())
                .replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())));
    }
}
