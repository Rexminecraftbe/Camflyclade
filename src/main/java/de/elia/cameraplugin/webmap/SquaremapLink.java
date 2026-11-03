package de.elia.cameraplugin.webmap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The spots on squaremap, as a layer of their own in every world it renders:
 * an icon for each spot, with its name on hovering and its text on a click.
 *
 * <p>Checked against {@code xyz.jpenilla:squaremap-api} 1.4.3:</p>
 * <ul>
 *   <li>{@code SquaremapProvider.get()}, {@code Squaremap.getWorldIfEnabled(WorldIdentifier)},
 *       {@code Squaremap.iconRegistry()}, {@code BukkitAdapter.worldIdentifier(World)}</li>
 *   <li>{@code MapWorld.layerRegistry()}, and on a registry {@code register(Key, T)},
 *       {@code unregister(Key)}, {@code hasEntry(Key)}, {@code get(Key)}</li>
 *   <li>{@code Key.of(String)} - letters, digits, {@code .}, {@code _} and
 *       {@code -} only -, {@code Point.of(double, double)}</li>
 *   <li>{@code SimpleLayerProvider.builder(String)}, and on the builder
 *       {@code showControls(boolean)}, {@code defaultHidden(boolean)}, {@code build()};
 *       {@code SimpleLayerProvider.addMarker(Key, Marker)}</li>
 *   <li>{@code Marker.icon(Point, Key, int)}, {@code Marker.markerOptions(MarkerOptions)},
 *       {@code MarkerOptions.builder()}, {@code hoverTooltip(String)},
 *       {@code clickTooltip(String)}, {@code build()}</li>
 * </ul>
 *
 * <p>The layer is handed to squaremap ready made, and squaremap reads it
 * from threads of its own. {@code /squaremap reload} forgets it; the next
 * round of the plugin finds it gone and puts it there again.</p>
 */
final class SquaremapLink implements MapLink {

    private static final String PROVIDER = "xyz.jpenilla.squaremap.api.SquaremapProvider";
    private static final String ADAPTER = "xyz.jpenilla.squaremap.api.BukkitAdapter";
    private static final String KEY = "xyz.jpenilla.squaremap.api.Key";
    private static final String POINT = "xyz.jpenilla.squaremap.api.Point";
    private static final String LAYER = "xyz.jpenilla.squaremap.api.SimpleLayerProvider";
    private static final String MARKER = "xyz.jpenilla.squaremap.api.marker.Marker";
    private static final String OPTIONS = "xyz.jpenilla.squaremap.api.marker.MarkerOptions";

    /** How big an icon is on the map, in pixels. */
    private static final int ICON_SIZE = 16;

    private final Plugin map;
    private final String layerKey;
    private final String iconKey;
    private final String layer;
    private final boolean hidden;
    /** The layer put into each world last time, by the name of the world, to see whether it is still there. */
    private final Map<String, Object> shown = new HashMap<>();
    /** The spots shown last time. */
    private List<MapSpot> last;

    SquaremapLink(Plugin map, String id, String layer, boolean hidden) {
        this.map = map;
        // squaremap takes no ":" in a key.
        String base = id.replace(':', '_');
        this.layerKey = base;
        this.iconKey = base + "_spot";
        this.layer = layer;
        this.hidden = hidden;
    }

    @Override
    public String plugin() {
        return map.getName();
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Calls.load(map.getClass().getClassLoader(), name);
    }

    private Object key(String key) throws ReflectiveOperationException {
        return Calls.callStatic(type(KEY), "of", key);
    }

    @Override
    public void show(List<MapSpot> spots) throws ReflectiveOperationException {
        if (!map.isEnabled()) {
            return;
        }
        Object squaremap = Calls.callStatic(type(PROVIDER), "get");
        if (spots.equals(last) && stillThere(squaremap)) {
            return;
        }
        Object icons = Calls.call(squaremap, "iconRegistry");
        registerIcon(icons, iconKey, new Color(0x3F, 0xB7, 0xD9));
        Object layerId = key(layerKey);
        Map<String, Object> now = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            Object registry = layerRegistry(squaremap, world);
            if (registry == null) {
                continue;
            }
            List<MapSpot> here = new ArrayList<>();
            for (MapSpot spot : spots) {
                if (spot.world().equals(world.getName())) {
                    here.add(spot);
                }
            }
            if (Boolean.TRUE.equals(Calls.call(registry, "hasEntry", layerId))) {
                Calls.call(registry, "unregister", layerId);
            }
            if (here.isEmpty()) {
                continue;
            }
            Object builder = Calls.callStatic(type(LAYER), "builder", layer);
            Calls.call(builder, "showControls", Boolean.TRUE);
            Calls.call(builder, "defaultHidden", hidden);
            Object provider = Calls.call(builder, "build");
            for (MapSpot spot : here) {
                Object point = Calls.callStatic(type(POINT), "of", spot.x(), spot.z());
                Object marker = Calls.callStatic(type(MARKER), "icon", point, key(iconKey),
                        ICON_SIZE);
                Object options = Calls.callStatic(type(OPTIONS), "builder");
                Calls.call(options, "hoverTooltip", escape(spot.label()));
                Calls.call(options, "clickTooltip", spot.detail());
                Calls.call(marker, "markerOptions", Calls.call(options, "build"));
                Calls.call(provider, "addMarker", key(spot.id().replace(':', '_')), marker);
            }
            Calls.call(registry, "register", layerId, provider);
            now.put(world.getName(), provider);
        }
        shown.clear();
        shown.putAll(now);
        last = List.copyOf(spots);
    }

    /** Whether the layers put there last time are all still in their worlds: not after a reload of squaremap. */
    private boolean stillThere(Object squaremap) throws ReflectiveOperationException {
        Object layerId = key(layerKey);
        for (World world : Bukkit.getWorlds()) {
            Object ours = shown.get(world.getName());
            Object registry = ours == null ? null : layerRegistry(squaremap, world);
            if (ours != null && (registry == null || !Boolean.TRUE.equals(Calls.call(registry, "hasEntry", layerId))
                    || Calls.call(registry, "get", layerId) != ours)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void stop() throws ReflectiveOperationException {
        if (map.isEnabled()) {
            Object squaremap = Calls.callStatic(type(PROVIDER), "get");
            Object layerId = key(layerKey);
            for (World world : Bukkit.getWorlds()) {
                Object registry = layerRegistry(squaremap, world);
                if (registry != null && Boolean.TRUE.equals(Calls.call(registry, "hasEntry", layerId))) {
                    Calls.call(registry, "unregister", layerId);
                }
            }
            Object icons = Calls.call(squaremap, "iconRegistry");
            Object id = key(iconKey);
            if (Boolean.TRUE.equals(Calls.call(icons, "hasEntry", id))) {
                Calls.call(icons, "unregister", id);
            }
        }
        shown.clear();
        last = null;
    }

    /** The layers of a world squaremap renders, or {@code null} for one it leaves out. */
    private Object layerRegistry(Object squaremap, World world) throws ReflectiveOperationException {
        Object identifier = Calls.callStatic(type(ADAPTER), "worldIdentifier", world);
        Object found = Calls.call(squaremap, "getWorldIfEnabled", identifier);
        if (!(found instanceof Optional<?> optional) || optional.isEmpty()) {
            return null;
        }
        return Calls.call(optional.get(), "layerRegistry");
    }

    /** Hands squaremap an icon of the plugin, once: a round pin in this colour. */
    private void registerIcon(Object icons, String name, Color color) throws ReflectiveOperationException {
        Object id = key(name);
        if (!Boolean.TRUE.equals(Calls.call(icons, "hasEntry", id))) {
            Calls.call(icons, "register", id, pin(color));
        }
    }

    /**
     * A round pin, drawn here rather than shipped as a file: a filled circle
     * with a dark rim and a light dot like the lens of a camera.
     */
    static BufferedImage pin(Color color) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(color);
            graphics.fillOval(1, 1, ICON_SIZE - 2, ICON_SIZE - 2);
            graphics.setColor(new Color(0x20, 0x20, 0x20));
            graphics.setStroke(new BasicStroke(1.5f));
            graphics.drawOval(1, 1, ICON_SIZE - 3, ICON_SIZE - 3);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(ICON_SIZE / 2 - 3, ICON_SIZE / 2 - 3, 6, 6);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** The name on hovering is shown as HTML as well. */
    private static String escape(String text) {
        return CamWebMap.html(text);
    }
}
