package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.body.PlayerSkins;
import de.elia.cameraplugin.spot.Spot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.List;

/**
 * The items of {@link CamMenu}: the buttons with their names and lines, the
 * heads of players, how Bedrock is to show them, and what a button says in a
 * window of Bedrock. Nothing in here keeps anything.
 */
final class MenuItems {

    /** How long a line under a button of a window of Bedrock may be, in letters, before it is cut. */
    static final int FORM_LINE = 40;

    private MenuItems() {
    }

    /**
     * What a slot holds, as far as a click on it goes: the kind of item and
     * its name - empty for nothing. What is written under it may change
     * without the click meaning anything else, a countdown or a distance.
     */
    static String identity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        return item.getType().name() + "|" + displayName(item);
    }

    /** The name an item of the menu was given, or an empty one. */
    static String displayName(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
    }

    /**
     * The head of a player online, with a name and lines of the menu - a
     * plain one while the server has no skin of his, see {@link PlayerSkins}.
     */
    static ItemStack head(Player owner, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            PlayerSkins.putOn(skull, owner);
            head.setItemMeta(skull);
        }
        return labelled(head, name, lore);
    }

    /**
     * What a button of a window of Bedrock says: the name of the item, and
     * under it the first of its lines, cut short where it is long. Without
     * colours - Bedrock draws the buttons light, and most colours of the menu
     * are made for a dark window.
     */
    static String formLabel(ItemStack item) {
        String label = ChatColor.stripColor(displayName(item));
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : null;
        if (lore != null && !lore.isEmpty()) {
            String first = ChatColor.stripColor(lore.get(0)).trim();
            if (first.length() > FORM_LINE) {
                first = first.substring(0, FORM_LINE - 1).trim() + "\u2026";
            }
            if (!first.isEmpty()) {
                label += "\n" + first;
            }
        }
        return label;
    }

    /** The name of an item and its lines, as a text of a window of Bedrock, with their colours. */
    static String itemText(ItemStack item) {
        StringBuilder text = new StringBuilder(displayName(item));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String line : meta.getLore()) {
                text.append("\n").append(line);
            }
        }
        return text.toString();
    }

    /**
     * An item as a page for Bedrock shows it: a filled map as an empty one.
     * Bedrock names a filled map by the map it shows - "Unknown Map" for one
     * without, which every map of the menu is -, and never by the name the
     * menu gave it; an empty map keeps its name. Its name, its lines and its
     * shine go along. So do the maps of the structures 26.3 brings, the icons
     * a spot may have: whether Bedrock shows their name or "Unknown Map" was
     * never tried. A switch, {@code bedrock.map-icons}, let them be themselves
     * to try it; it was taken out, the empty map shows the name for sure.
     */
    static ItemStack forBedrock(ItemStack item) {
        if (item == null || item.getType() == Material.MAP || !item.getType().name().endsWith("_MAP")) {
            return item;
        }
        ItemStack map = new ItemStack(Material.MAP, item.getAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            map.setItemMeta(Bukkit.getItemFactory().asMetaFor(meta, Material.MAP));
        }
        return map;
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        return labelled(new ItemStack(material), name, lore);
    }

    /** An item with a name and lines of the menu in place of its own. */
    static ItemStack labelled(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Lets an item shine like an enchanted one, for what is switched on or holds now. */
    static ItemStack shine(ItemStack item, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (glint && meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A text of the language file as lines under an item, {@code \n} beginning a new one; none for an empty one. */
    static List<String> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.split("\n"));
    }

    /** {@code {x}}, {@code {y}}, {@code {z}} and {@code {world}} of a spot. */
    static String place(String text, Spot spot) {
        return text.replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                .replace("{world}", spot.world());
    }
}
