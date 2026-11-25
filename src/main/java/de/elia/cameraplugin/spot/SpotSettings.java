package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The section {@code viewpoints} of the config file, for {@code /cam spot}:
 * how many spots a player keeps, whether they can be shared, their guestbook,
 * their icons and options. See {@link CamSettings#spots()}.
 */
public final class SpotSettings {

    /**
     * The most spots {@code viewpoints.max-per-player} lets each player keep,
     * short of no limit at all, 0. {@code /cam spot list} sends a line for
     * each of them, and more than this no longer fits into the chat a player
     * scrolls back through.
     */
    private static final int MAX_VIEWPOINTS = 100;

    /** The longest {@code viewpoints.views-cooldown-minutes} accepts: a day. */
    private static final int MAX_VIEWS_COOLDOWN_MINUTES = 1440;

    /**
     * What {@code viewpoints.icons} offers when the file lists nothing: the
     * filled map and the empty one, and the maps 26.3 brings - each as its
     * own item -, by their keys there. A server offers only what it knows.
     */
    private static final List<String> DEFAULT_SPOT_ICONS = List.of("filled_map", "map", "ocean_monument_map",
            "woodland_mansion_map", "buried_trial_chambers_map", "jungle_pyramid_map", "swamp_hut_map",
            "desert_village_map", "plains_village_map", "savanna_village_map", "snowy_village_map",
            "taiga_village_map", "buried_treasure_map", "buried_ancient_city_map", "buried_mineshaft_map",
            "desert_pyramid_map", "abandoned_camp_map", "warm_ocean_ruins_map");

    /** The longest {@code viewpoints.guestbook-interval-minutes} accepts: a day. */
    private static final int MAX_GUESTBOOK_INTERVAL_MINUTES = 1440;

    /** Whether {@code /cam spot} keeps spots to jump to, {@code viewpoints.enabled}. */
    private boolean viewpointsEnabled;
    /** How many spots each player may keep, {@code viewpoints.max-per-player}. */
    private int viewpointsMax;
    /** Where the spots are kept, {@code storage}. */
    private StorageMode viewpointsStorage;
    /** Whether a player may share his spots with others, {@code viewpoints.sharing}. */
    private boolean viewpointsSharing;
    /** Whether the owner of a spot hears how often it was looked at and liked, {@code viewpoints.guestbook}. */
    private boolean viewpointsGuestbook;
    /** How often the guestbook tells the owners online what came, {@code viewpoints.guestbook-interval-minutes}. */
    private int guestbookIntervalMinutes;
    /** How close a player out of camera mode has to be to a spot to jump there, {@code viewpoints.start-distance}. */
    private double viewpointsStartDistance;
    /** Whether a spot is saved only in camera mode, {@code viewpoints.set-in-cam-only}. */
    private boolean viewpointsSetInCamOnly;
    /** The icons a spot may have, {@code viewpoints.icons}: keys of items without the namespace. */
    private List<String> viewpointsIcons = DEFAULT_SPOT_ICONS;
    /** What the owner may do on the page of the options, {@code viewpoints.options.<what>}. */
    private boolean spotOptionRename;
    private boolean spotOptionIcon;
    private boolean spotOptionShare;
    private boolean spotOptionDelete;
    private boolean spotOptionMove;
    /** How long the same player at the same spot is no new view, {@code viewpoints.views-cooldown-minutes}. */
    private int viewsCooldownMinutes;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public SpotSettings(ConfigReader config) {
        viewpointsEnabled = config.getBoolean("viewpoints.enabled", true);
        viewpointsMax = config.getInt("viewpoints.max-per-player", 10, 0, MAX_VIEWPOINTS);
        viewpointsStorage = SettingValues.storage(config, "viewpoints.storage");
        viewpointsSharing = config.getBoolean("viewpoints.sharing", true);
        viewpointsGuestbook = config.getBoolean("viewpoints.guestbook", true);
        guestbookIntervalMinutes = config.getInt("viewpoints.guestbook-interval-minutes", 10, 1,
                MAX_GUESTBOOK_INTERVAL_MINUTES);
        viewpointsStartDistance = config.getDouble("viewpoints.start-distance", 16.0, 0.0, CamSettings.MAX_START_DISTANCE);
        viewpointsSetInCamOnly = config.getBoolean("viewpoints.set-in-cam-only", true);
        viewpointsIcons = readSpotIcons(config);
        spotOptionRename = config.getBoolean("viewpoints.options.rename", true);
        spotOptionIcon = config.getBoolean("viewpoints.options.icon", true);
        spotOptionShare = config.getBoolean("viewpoints.options.share", true);
        spotOptionDelete = config.getBoolean("viewpoints.options.delete", true);
        spotOptionMove = config.getBoolean("viewpoints.options.move", false);
        viewsCooldownMinutes = config.getInt("viewpoints.views-cooldown-minutes", 10, 0, MAX_VIEWS_COOLDOWN_MINUTES);
    }

    /**
     * {@code viewpoints.icons}: the keys of items, with or without
     * {@code minecraft:}. Whether the server knows one is not asked here - an
     * item of a later version is no mistake on an earlier one.
     */
    private static List<String> readSpotIcons(ConfigReader config) {
        List<String> icons = new ArrayList<>();
        for (String entry : config.getStringList("viewpoints.icons", DEFAULT_SPOT_ICONS)) {
            String key = entry.trim().toLowerCase(Locale.ROOT);
            if (key.startsWith("minecraft:")) {
                key = key.substring("minecraft:".length());
            }
            if (!key.isEmpty() && !icons.contains(key)) {
                icons.add(key);
            }
        }
        return Collections.unmodifiableList(icons);
    }

    public boolean enabled() {
        return viewpointsEnabled;
    }

    /** How many spots each player may keep, {@code viewpoints.max-per-player}; 0 for no limit. */
    public int max() {
        return viewpointsMax;
    }

    /** Whether there is a limit to how many spots a player may keep. */
    public boolean limited() {
        return viewpointsMax > 0;
    }

    public StorageMode storage() {
        return viewpointsStorage;
    }

    /** Whether spots may be shared, which only goes while there are spots at all. */
    public boolean sharing() {
        return viewpointsEnabled && viewpointsSharing;
    }

    /**
     * Whether the owner of a spot hears who looked at it and liked it -
     * which only others do, so only while spots may be shared.
     */
    public boolean guestbook() {
        return sharing() && viewpointsGuestbook;
    }

    public int guestbookIntervalMinutes() {
        return guestbookIntervalMinutes;
    }

    /**
     * Whether a spot is saved only in camera mode, {@code viewpoints.set-in-cam-only}:
     * where the camera is, not where the player stands.
     */
    public boolean setInCamOnly() {
        return viewpointsSetInCamOnly;
    }

    /**
     * The icons a spot may have, {@code viewpoints.icons}, in their order:
     * keys of items without the namespace, in small letters - also those this
     * server does not know, which {@link de.elia.cameraplugin.spot.CamSpots#icons}
     * leaves out.
     */
    public List<String> icons() {
        return viewpointsIcons;
    }

    /** Whether the owner may rename a spot on its page of options, {@code viewpoints.options.rename}. */
    public boolean optionRename() {
        return spotOptionRename;
    }

    /** Whether the owner may give a spot another icon there, {@code viewpoints.options.icon}. */
    public boolean optionIcon() {
        return spotOptionIcon;
    }

    /** Whether the owner may share a spot there, or make it public, {@code viewpoints.options.share}. */
    public boolean optionShare() {
        return spotOptionShare && sharing();
    }

    /** Whether the owner may delete a spot there, {@code viewpoints.options.delete}. */
    public boolean optionDelete() {
        return spotOptionDelete;
    }

    /**
     * Whether the owner may move a spot there, to where his camera is,
     * {@code viewpoints.options.move} - off unless switched on.
     */
    public boolean optionMove() {
        return spotOptionMove;
    }

    /**
     * How close, in blocks, a player out of camera mode has to be to a spot
     * to jump there, which starts camera mode for him; 0 while only who is in
     * camera mode may jump.
     */
    public double startDistance() {
        return viewpointsStartDistance;
    }

    /**
     * How long, in minutes, the same player at the same spot does not count
     * as a view again; 0 while every jump counts.
     */
    public int viewsCooldownMinutes() {
        return viewsCooldownMinutes;
    }
}
