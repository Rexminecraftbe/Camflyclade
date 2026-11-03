package de.elia.cameraplugin.webmap;

import java.util.List;

/**
 * The link to one web map on the server - BlueMap, Dynmap or squaremap -:
 * it puts the public spots on it as a layer of pins of their own, and takes
 * them off again.
 */
interface MapLink {

    /** The name of the plugin of the map, as the server knows it. */
    String plugin();

    /** Sets up what the link needs once the map is there, e.g. a hook into its reloads. */
    default void start() throws ReflectiveOperationException {
    }

    /**
     * Puts these spots on the map, in place of those it showed before -
     * nothing at all when the list is empty. A map that already shows
     * exactly these may leave them as they are.
     */
    void show(List<MapSpot> spots) throws ReflectiveOperationException;

    /** Takes every pin of the plugin off the map again, and the hooks of {@link #start()}. */
    void stop() throws ReflectiveOperationException;
}
