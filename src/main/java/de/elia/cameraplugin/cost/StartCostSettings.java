package de.elia.cameraplugin.cost;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The section {@code start-cost} of the config file: what a start of camera
 * mode costs. See {@link de.elia.cameraplugin.config.CamSettings#startCost()}.
 */
public final class StartCostSettings {

    /**
     * The most of one item {@code start-cost.items} accepts: a full inventory
     * of it. More could never be paid.
     */
    private static final int MAX_COST_ITEMS = 36 * 64;

    /** Whether a start costs something, the section {@code start-cost}. */
    private boolean startCostEnabled;
    /** Experience a start costs, in levels or points. */
    private int startCostXp;
    /** Whether {@link #startCostXp} counts points rather than levels, {@code start-cost.xp-type}. */
    private boolean startCostXpPoints;
    /** Money a start costs, through Vault. */
    private double startCostMoney;
    /** Items a start costs, and how many of each, in the order of the file. */
    private final Map<Material, Integer> startCostItems = new LinkedHashMap<>();

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public StartCostSettings(ConfigReader config) {
        readStartCost(config);
    }

    /**
     * Reads the section {@code start-cost}. An item the game does not have, or
     * that cannot be carried, is reported and left out of the cost.
     */
    private void readStartCost(ConfigReader config) {
        startCostEnabled = config.getBoolean("start-cost.enabled", false);
        startCostXp = config.getInt("start-cost.xp", 0, 0);
        startCostXpPoints = config.getChoice("start-cost.xp-type", "levels", "levels", "points")
                .equalsIgnoreCase("points");
        startCostMoney = config.getDouble("start-cost.money", 0.0, 0.0);
        startCostItems.clear();
        for (Map.Entry<String, Integer> entry
                : config.getAmounts("start-cost.items", 0, MAX_COST_ITEMS).entrySet()) {
            Material material = parseItem(entry.getKey().trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
            if (material == null) {
                config.warnUnknownEntry("start-cost.items", entry.getKey());
            } else if (entry.getValue() > 0) {
                startCostItems.merge(material, entry.getValue(), Integer::sum);
            }
        }
    }

    /** The item a name stands for, or {@code null} when it is none a player can carry. */
    private static Material parseItem(String name) {
        Material material = name.isEmpty() ? null : Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        return material;
    }

    /** Whether a start costs anything at all: switched on, and something to pay. */
    public boolean costsAnything() {
        return startCostEnabled && (startCostXp > 0 || startCostMoney > 0.0 || !startCostItems.isEmpty());
    }

    /** The experience a start costs, in points or levels as {@link #costsXpPoints()} says. */
    public int xp() {
        return startCostXp;
    }

    public boolean costsXpPoints() {
        return startCostXpPoints;
    }

    public double money() {
        return startCostMoney;
    }

    /** The items a start costs and how many of each, not to be changed. */
    public Map<Material, Integer> items() {
        return Collections.unmodifiableMap(startCostItems);
    }
}
