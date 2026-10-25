package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spots, {@code /cam spot}: places the camera jumps to, each looking a way of
 * its own - for a build looked at from the same spot over and over.
 *
 * <p>A spot is saved where the player is, on foot or as a camera, so only a
 * place he has been to himself becomes one. The camera jumps to it only
 * where it would be allowed to fly: in the world of its body, within
 * {@code max-distance} - under {@code max-distance-mode: signal} where the
 * signal reaches -, outside the areas {@code cam-area} keeps it out of, out of
 * shut lava, water and powder snow and not into a block that has been put
 * there since. The jump saves the way there and nothing else.</p>
 *
 * <p>Where they are kept is {@code viewpoints.storage}: in the file of the
 * player, or in memory only, which {@code /cam reload} and a restart
 * forget.</p>
 */
public final class CamSpots {

    /** What a name of a spot is made of: letters, digits, - and _, one to sixteen of them. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    /** The words after {@code /cam spot} that are no spot. */
    private static final Set<String> WORDS = Set.of("set", "delete", "list");

    /**
     * How far the box of the camera is drawn in on every side before it is
     * tested against the blocks: a camera that hung on a slab or against a
     * wall touched them, it did not stand in them.
     */
    private static final double TOUCH_MARGIN = 1.0E-3;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    /** The spots of each player, by {@link Spot#key()}, on {@code storage: memory}. */
    private final Map<UUID, Map<String, Spot>> inMemory = new HashMap<>();

    public CamSpots(CameraPlugin plugin, PlayerStore store) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
    }

    /** Whether {@code /cam spot} is there at all. */
    public boolean isEnabled() {
        return settings.isViewpointsEnabled();
    }

    private boolean inFile() {
        return settings.getViewpointsStorage() == StorageMode.FILE;
    }

    /** The spots of a player, by {@link Spot#key()}, in the order he saved them; not to be changed. */
    private Map<String, Spot> spotsOf(Player player) {
        if (inFile()) {
            return store.get(player.getUniqueId()).getSpots();
        }
        return Collections.unmodifiableMap(inMemory.getOrDefault(player.getUniqueId(), Map.of()));
    }

    /** The names of his spots, for the tab completion. */
    public List<String> names(Player player) {
        List<String> names = new ArrayList<>();
        for (Spot spot : spotsOf(player).values()) {
            names.add(spot.name());
        }
        return names;
    }

    /** {@code /cam spot}, with what follows it. */
    public void command(Player player, String[] args) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(player, "spot-off");
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list(player);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && first.equals("set")) {
            set(player, args[1]);
        } else if (args.length == 2 && first.equals("delete")) {
            delete(player, args[1]);
        } else if (args.length == 1 && !WORDS.contains(first)) {
            jump(player, args[0]);
        } else {
            messages.sendConfiguredMessage(player, "spot-usage");
        }
    }

    /** Saves the spot where he is, looking the way he looks. */
    private void set(Player player, String name) {
        if (!NAME.matcher(name).matches() || WORDS.contains(Spot.key(name))) {
            messages.sendConfiguredMessage(player, "spot-bad-name");
            return;
        }
        Map<String, Spot> spots = spotsOf(player);
        boolean moved = spots.containsKey(Spot.key(name));
        if (!moved && spots.size() >= settings.getViewpointsMax()) {
            messages.sendConfiguredMessage(player, "spot-full", "{max}",
                    String.valueOf(settings.getViewpointsMax()));
            return;
        }
        // Under the name he gave it now, capitals and all.
        Spot spot = Spot.at(name, player.getLocation());
        if (inFile()) {
            store.get(player.getUniqueId()).putSpot(player.getName(), spot);
            store.save(player.getUniqueId());
        } else {
            inMemory.computeIfAbsent(player.getUniqueId(), id -> new LinkedHashMap<>()).put(spot.key(), spot);
        }
        messages.sendConfiguredMessage(player, moved ? "spot-moved" : "spot-saved", "{spot}", name);
    }

    private void delete(Player player, String name) {
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (inFile()) {
            PlayerRecord record = store.get(player.getUniqueId());
            record.removeSpot(spot.key());
            store.save(player.getUniqueId());
        } else {
            inMemory.get(player.getUniqueId()).remove(spot.key());
        }
        messages.sendConfiguredMessage(player, "spot-deleted", "{spot}", spot.name());
    }

    /**
     * Lists his spots, each with a button that jumps to it and one that puts
     * the command to delete it into his chat line: deleting takes one key
     * more than a click, so that a slip of the mouse loses nothing.
     */
    private void list(Player player) {
        Map<String, Spot> spots = spotsOf(player);
        if (spots.isEmpty()) {
            messages.sendConfiguredMessage(player, "spot-list-empty");
            return;
        }
        messages.sendConfiguredMessage(player, "spot-list-header", "{count}", String.valueOf(spots.size()),
                "{max}", String.valueOf(settings.getViewpointsMax()));
        if (!messages.isMessageEnabled("spot-list-entry")) {
            return;
        }
        for (Spot spot : spots.values()) {
            String text = messages.getMessage("spot-list-entry")
                    .replace("{spot}", spot.name())
                    .replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                    .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                    .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                    .replace("{world}", spot.world());
            List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
            line.addAll(button("spot-button-go", spot.name(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot " + spot.name())));
            line.addAll(button("spot-button-delete", spot.name(),
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cam spot delete " + spot.name())));
            player.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        }
    }

    /**
     * One button behind a line of the list: its text, and on hovering the
     * text of the same name with {@code -hover} after it. Nothing when the
     * text is switched off or empty.
     */
    private List<BaseComponent> button(String key, String spot, ClickEvent click) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{spot}", spot);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{spot}", spot))));
        List<BaseComponent> parts = new ArrayList<>();
        parts.add(new TextComponent(" "));
        for (BaseComponent part : TextComponent.fromLegacyText(text)) {
            part.setHoverEvent(hover);
            part.setClickEvent(click);
            parts.add(part);
        }
        return parts;
    }

    /** Puts the camera down at one of his spots, if it may be there. */
    private void jump(Player player, String name) {
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (data == null) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        Location target = spot.toLocation();
        if (target == null || !target.getWorld().equals(data.getBody().getWorld())) {
            messages.sendConfiguredMessage(player, "spot-other-world", "{spot}", spot.name());
            return;
        }
        if (!mayGo(player, data, spot, target)) {
            return;
        }
        if (!plugin.getMovementGuard().jumpTo(player, data, target)) {
            return;
        }
        settings.getSpotSound().play(player);
        if (messages.isMessageEnabled("spot-jumped")) {
            plugin.getActionBar().flash(player, messages.getMessage("spot-jumped").replace("{spot}", spot.name()));
        }
    }

    /**
     * Whether the camera may be put down at the spot, in the world of its
     * body: everything that would hold it back in flight.
     *
     * @return whether it may; if not, he has been told why
     */
    private boolean mayGo(Player player, CameraData data, Spot spot, Location target) {
        if (plugin.getMovementGuard().beyondFlightDistance(data, target)) {
            messages.sendConfiguredMessage(player, "spot-too-far", "{spot}", spot.name(),
                    "{distance}", String.valueOf(settings.getMaxDistance()));
            return false;
        }
        if (settings.usesSignal() && !plugin.getSignal().reaches(player, data, target)) {
            messages.sendConfiguredMessage(player, "spot-no-signal", "{spot}", spot.name());
            return false;
        }
        String area = settings.keepsOutOfAreas() ? settings.getCamAreaRules().forbiddenArea(target) : null;
        if (area != null) {
            messages.sendConfiguredMessage(player, "spot-area", "{spot}", spot.name(), "{area}", area);
            return false;
        }
        FlightMedium medium = FlightMedium.reachedInto(player, settings, null, target);
        if (medium != null) {
            messages.sendMessage(player, medium.getFlightMessage());
            return false;
        }
        if (blocked(player, target)) {
            messages.sendConfiguredMessage(player, "spot-blocked", "{spot}", spot.name());
            return false;
        }
        return true;
    }

    /**
     * Whether the camera would be stuck in a block at the spot - in one built
     * there since it was saved. A camera does not fly through blocks, and the
     * jump is no way through them either.
     */
    private static boolean blocked(Player player, Location feet) {
        double half = player.getWidth() / 2.0 - TOUCH_MARGIN;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + TOUCH_MARGIN, feet.getZ() - half,
                feet.getX() + half, feet.getY() + player.getHeight() - TOUCH_MARGIN, feet.getZ() + half);
        World world = feet.getWorld();
        for (int x = Location.locToBlock(box.getMinX()); x <= Location.locToBlock(box.getMaxX()); x++) {
            for (int y = Location.locToBlock(box.getMinY()); y <= Location.locToBlock(box.getMaxY()); y++) {
                for (int z = Location.locToBlock(box.getMinZ()); z <= Location.locToBlock(box.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(box)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** After a reload: on {@code storage: memory} every spot is forgotten. */
    public void onReload() {
        inMemory.clear();
    }
}
