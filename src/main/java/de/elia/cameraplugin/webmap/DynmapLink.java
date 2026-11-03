package de.elia.cameraplugin.webmap;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The spots on Dynmap, as a marker set of their own: a marker with the
 * camera icon for each spot, and its text on a click.
 *
 * <p>Checked against {@code us.dynmap:DynmapCoreAPI} and {@code dynmap-api}
 * 3.8; the plugin itself is the {@code DynmapAPI}:</p>
 * <ul>
 *   <li>{@code markerAPIInitialized()}, {@code getMarkerAPI()}</li>
 *   <li>{@code MarkerAPI.getMarkerSet(String)},
 *       {@code createMarkerSet(String id, String label, Set icons, boolean persistent)},
 *       {@code getMarkerIcon(String)}</li>
 *   <li>{@code MarkerSet.setMarkerSetLabel(String)}, {@code setHideByDefault(boolean)},
 *       {@code getMarkers()}, {@code deleteMarkerSet()},
 *       {@code createMarker(String id, String label, boolean markup, String world,
 *       double x, double y, double z, MarkerIcon icon, boolean persistent)}</li>
 *   <li>{@code Marker.setDescription(String)}, {@code deleteMarker()}</li>
 * </ul>
 *
 * <p>The set is not kept by Dynmap - it is not persistent -, so it is gone
 * after a restart of Dynmap, and the plugin puts it there again when Dynmap
 * starts. Dynmap wants to be called on the main thread, which is where this
 * runs.</p>
 */
final class DynmapLink implements MapLink {

    /** The icon of a spot, one Dynmap brings along. */
    private static final String ICON = "camera";
    /** What Dynmap shows when it is missing. */
    private static final String FALLBACK_ICON = "default";

    private final Plugin map;
    private final String setId;
    private final String layer;
    private final boolean hidden;
    /** The marker set filled last time, to see whether it is still there. */
    private Object shownSet;
    /** The spots shown last time. */
    private List<MapSpot> last;

    DynmapLink(Plugin map, String setId, String layer, boolean hidden) {
        this.map = map;
        this.setId = setId;
        this.layer = layer;
        this.hidden = hidden;
    }

    @Override
    public String plugin() {
        return map.getName();
    }

    @Override
    public void show(List<MapSpot> spots) throws ReflectiveOperationException {
        Object api = markerApi();
        if (api == null) {
            return;
        }
        Object set = Calls.call(api, "getMarkerSet", setId);
        if (spots.equals(last) && set != null && set == shownSet) {
            return;
        }
        if (set == null) {
            set = Calls.call(api, "createMarkerSet", setId, layer, null, Boolean.FALSE);
            if (set == null) {
                return;
            }
        }
        Calls.call(set, "setMarkerSetLabel", layer);
        Calls.call(set, "setHideByDefault", hidden);
        Object markers = Calls.call(set, "getMarkers");
        if (markers instanceof Collection<?> old) {
            for (Object marker : new ArrayList<>(old)) {
                Calls.call(marker, "deleteMarker");
            }
        }
        Object icon = icon(api, ICON);
        for (MapSpot spot : spots) {
            Object marker = Calls.call(set, "createMarker", spot.id(), spot.label(), Boolean.FALSE, spot.world(),
                    spot.x(), spot.y(), spot.z(), icon, Boolean.FALSE);
            if (marker != null) {
                Calls.call(marker, "setDescription", spot.detail());
            }
        }
        shownSet = set;
        last = List.copyOf(spots);
    }

    @Override
    public void stop() throws ReflectiveOperationException {
        Object api = markerApi();
        Object set = api == null ? null : Calls.call(api, "getMarkerSet", setId);
        if (set != null) {
            Calls.call(set, "deleteMarkerSet");
        }
        shownSet = null;
        last = null;
    }

    /** The markers of Dynmap, once they are ready, or {@code null}. */
    private Object markerApi() throws ReflectiveOperationException {
        if (!map.isEnabled() || !Boolean.TRUE.equals(Calls.call(map, "markerAPIInitialized"))) {
            return null;
        }
        return Calls.call(map, "getMarkerAPI");
    }

    /** An icon Dynmap brings along, or its default one where it lacks it. */
    private static Object icon(Object api, String name) throws ReflectiveOperationException {
        Object icon = Calls.call(api, "getMarkerIcon", name);
        return icon != null ? icon : Calls.call(api, "getMarkerIcon", FALLBACK_ICON);
    }
}
