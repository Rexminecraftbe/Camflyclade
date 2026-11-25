package de.elia.cameraplugin.webmap;

import de.elia.cameraplugin.config.ConfigReader;

/**
 * The section {@code web-map} of the config file: the public spots on
 * BlueMap, Dynmap and squaremap. See {@link de.elia.cameraplugin.config.CamSettings#webMap()}.
 */
public final class WebMapSettings {

    /** Whether the public spots go on the web maps of the server, {@code web-map.enabled}. */
    private boolean webMapEnabled;
    /** Whether the layer of the spots is switched off on the page until a visitor switches it on, {@code web-map.hidden}. */
    private boolean webMapHidden;
    /** How many likes a public spot needs to get onto the map, {@code web-map.min-likes}. */
    private int webMapMinLikes;
    /** How many views a public spot needs to get onto the map, {@code web-map.min-views}. */
    private int webMapMinViews;
    /** The address of a spot on the web map, with its placeholders, {@code web-map.link}; empty for none. */
    private String webMapLink;

    /** Whether spots can be made public at all, see {@link de.elia.cameraplugin.spot.SpotSettings#sharing()}. */
    private final boolean spotsShared;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public WebMapSettings(ConfigReader config, boolean spotsShared) {
        this.spotsShared = spotsShared;
        webMapEnabled = config.getBoolean("web-map.enabled", true);
        webMapHidden = config.getBoolean("web-map.hidden", false);
        webMapMinLikes = config.getInt("web-map.min-likes", 0, 0);
        webMapMinViews = config.getInt("web-map.min-views", 0, 0);
        webMapLink = config.getString("web-map.link", "").trim();
    }

    /**
     * Whether the public spots go on the web maps of the server,
     * {@code web-map.enabled}: only with sharing, which public spots need.
     */
    public boolean enabled() {
        return webMapEnabled && spotsShared;
    }

    /** Whether the layer of the spots starts switched off on the page of the map, {@code web-map.hidden}. */
    public boolean hidden() {
        return webMapHidden;
    }

    /** How many likes a public spot needs at least to get onto the map, {@code web-map.min-likes}. */
    public int minLikes() {
        return webMapMinLikes;
    }

    /**
     * How many views a public spot needs at least to get onto the map,
     * {@code web-map.min-views}: jumps to it, as the menu counts them.
     */
    public int minViews() {
        return webMapMinViews;
    }

    /**
     * The address of a spot on the web map, {@code web-map.link}, with
     * {@code {world}}, {@code {x}}, {@code {y}} and {@code {z}} still in it;
     * empty while there is none.
     */
    public String link() {
        return webMapLink;
    }
}
