package de.elia.cameraplugin.webmap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The spots on BlueMap, as a marker set of their own on every map of a
 * world: a point of interest for each spot, with its text on a click.
 *
 * <p>Checked against {@code de.bluecolored:bluemap-api} 2.8.2:</p>
 * <ul>
 *   <li>{@code BlueMapAPI.onEnable(Consumer)}, {@code unregisterListener(Consumer)},
 *       {@code getInstance()} - BlueMap is ready only some time after its plugin
 *       starts, and starts anew with {@code /bluemap reload}, which forgets every
 *       marker set a plugin put there. The hook asks for the spots again each
 *       time.</li>
 *   <li>{@code BlueMapAPI.getWorld(Object)} with the world of the server,
 *       {@code BlueMapWorld.getMaps()}, {@code BlueMapMap.getMarkerSets()}</li>
 *   <li>{@code new MarkerSet(String label, boolean toggleable, boolean defaultHidden)},
 *       {@code MarkerSet.getMarkers()}</li>
 *   <li>{@code POIMarker.builder()}, and on the builder {@code label(String)},
 *       {@code position(double, double, double)}, {@code detail(String)},
 *       {@code build()}</li>
 * </ul>
 *
 * <p>A new marker set takes the place of the old one in one go, so the web
 * page never catches the set half filled. BlueMap reads it from threads of
 * its own; the maps of its interface are made for that.</p>
 */
final class BlueMapLink implements MapLink {

    private static final String API = "de.bluecolored.bluemap.api.BlueMapAPI";
    private static final String MARKER_SET = "de.bluecolored.bluemap.api.markers.MarkerSet";
    private static final String POI_MARKER = "de.bluecolored.bluemap.api.markers.POIMarker";

    private final Plugin map;
    private final String setId;
    private final String layer;
    private final boolean hidden;
    /** The hook BlueMap calls each time it is ready, see {@link #start()}. */
    private final Consumer<Object> onEnable;
    /** The marker set put on each map last time, by the id of the map, to see whether it is still there. */
    private final Map<String, Object> shown = new HashMap<>();
    /** The spots shown last time. */
    private List<MapSpot> last;

    /**
     * @param owner  this plugin, which the hook hands its work to
     * @param map    the plugin of BlueMap
     * @param again  asks this plugin to show the spots again, on the main thread
     */
    BlueMapLink(Plugin owner, Plugin map, String setId, String layer, boolean hidden, Runnable again) {
        this.map = map;
        this.setId = setId;
        this.layer = layer;
        this.hidden = hidden;
        // BlueMap calls it on a thread of its own: the spots are read on the
        // main thread.
        this.onEnable = api -> {
            if (owner.isEnabled()) {
                Bukkit.getScheduler().runTask(owner, again);
            }
        };
    }

    @Override
    public String plugin() {
        return map.getName();
    }

    private Class<?> api() throws ClassNotFoundException {
        return Calls.load(map.getClass().getClassLoader(), API);
    }

    @Override
    public void start() throws ReflectiveOperationException {
        // Called at once when BlueMap is ready already, otherwise once it is.
        Calls.callStatic(api(), "onEnable", onEnable);
    }

    @Override
    public void show(List<MapSpot> spots) throws ReflectiveOperationException {
        Object api = instance();
        if (api == null) {
            // Not ready yet: the hook of start() comes once it is.
            return;
        }
        if (spots.equals(last) && stillThere(api)) {
            return;
        }
        ClassLoader loader = map.getClass().getClassLoader();
        Class<?> markerSetType = Calls.load(loader, MARKER_SET);
        Class<?> poiType = Calls.load(loader, POI_MARKER);
        Map<String, Object> now = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            List<MapSpot> here = new ArrayList<>();
            for (MapSpot spot : spots) {
                if (spot.world().equals(world.getName())) {
                    here.add(spot);
                }
            }
            for (Object blueMap : mapsOf(api, world)) {
                Map<String, Object> sets = markerSets(blueMap);
                if (here.isEmpty()) {
                    sets.remove(setId);
                    continue;
                }
                Object set = Calls.create(markerSetType, layer, Boolean.TRUE, hidden);
                Map<String, Object> markers = markersOf(set);
                for (MapSpot spot : here) {
                    Object builder = Calls.callStatic(poiType, "builder");
                    Calls.call(builder, "label", spot.label());
                    Calls.call(builder, "position", spot.x(), spot.y(), spot.z());
                    Calls.call(builder, "detail", spot.detail());
                    markers.put(spot.id(), Calls.call(builder, "build"));
                }
                sets.put(setId, set);
                now.put((String) Calls.call(blueMap, "getId"), set);
            }
        }
        shown.clear();
        shown.putAll(now);
        last = List.copyOf(spots);
    }

    /** Whether the marker sets put there last time are all still on their maps: not after a reload of BlueMap. */
    private boolean stillThere(Object api) throws ReflectiveOperationException {
        for (World world : Bukkit.getWorlds()) {
            for (Object blueMap : mapsOf(api, world)) {
                Object ours = shown.get((String) Calls.call(blueMap, "getId"));
                if (ours != null && markerSets(blueMap).get(setId) != ours) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void stop() throws ReflectiveOperationException {
        Calls.callStatic(api(), "unregisterListener", onEnable);
        Object api = instance();
        if (api != null) {
            for (World world : Bukkit.getWorlds()) {
                for (Object blueMap : mapsOf(api, world)) {
                    markerSets(blueMap).remove(setId);
                }
            }
        }
        shown.clear();
        last = null;
    }

    /** BlueMap while it is ready, or {@code null}. */
    private Object instance() throws ReflectiveOperationException {
        Object instance = Calls.callStatic(api(), "getInstance");
        return instance instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    /** The maps BlueMap renders of a world of the server; none for a world it leaves out. */
    private static List<Object> mapsOf(Object api, World world) throws ReflectiveOperationException {
        Object found = Calls.call(api, "getWorld", world);
        if (!(found instanceof Optional<?> optional) || optional.isEmpty()) {
            return List.of();
        }
        Object maps = Calls.call(optional.get(), "getMaps");
        return maps instanceof Iterable<?> iterable ? copy(iterable) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> markerSets(Object blueMap) throws ReflectiveOperationException {
        return (Map<String, Object>) Calls.call(blueMap, "getMarkerSets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> markersOf(Object set) throws ReflectiveOperationException {
        return (Map<String, Object>) Calls.call(set, "getMarkers");
    }

    private static List<Object> copy(Iterable<?> items) {
        List<Object> list = new ArrayList<>();
        for (Object item : items) {
            list.add(item);
        }
        return list;
    }
}
