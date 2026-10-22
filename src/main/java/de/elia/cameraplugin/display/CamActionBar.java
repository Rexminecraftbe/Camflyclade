package de.elia.cameraplugin.display;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.KeybindComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The line above the hotbar while camera mode runs and right after it, {@code action-bar}.
 *
 * <p>While camera mode runs it says either that camera mode is on, or - with
 * {@code display: distance} - how far away the body is and which way it lies.
 * The latter starts with the first for {@code on-duration} as well, so that the
 * player is told camera mode has started at all - and how to end it: with the
 * key of {@code exit-key} while it is on, and with {@code /cam}. With
 * {@code display: false} that start is all there is.</p>
 */
public final class CamActionBar {

    /**
     * How often the fixed line {@code actionbar-on} is sent again, in ticks.
     * The client lets a line fade after three seconds.
     */
    private static final long TEXT_INTERVAL = 40L;

    /**
     * How often the distance to the body is sent, in ticks. It changes with
     * every step, and the client keeps a line up for three seconds anyway.
     */
    private static final long DISTANCE_INTERVAL = 4L;

    /** Where {@code actionbar-hint} names the key that ends camera mode. */
    private static final String KEY = "{key}";

    /**
     * The key that swaps the hands, as the client names it. Not
     * {@code Keybinds.SWAP_HANDS} of the chat library: that one says
     * {@code key.swapHands}, a name the client does not know, and it would
     * show those very words instead of the key.
     */
    private static final String SWAP_HANDS_KEY = "key.swapOffhand";

    /** How long a line of {@link #flash} keeps the regular one away, in milliseconds. */
    private static final long FLASH_MILLIS = 1500L;

    /**
     * How long a line of {@link #warn} keeps the regular one away, in
     * milliseconds: as long as the client shows a line, fading included.
     */
    private static final long WARN_MILLIS = 3000L;

    /**
     * The direction of the body as seen by the player, clockwise from straight
     * ahead in steps of 45 degrees.
     */
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198",
            "\u2193", "\u2199", "\u2190", "\u2196"};

    /** In place of an arrow when the body is steeply above or below, or right here. */
    private static final String ABOVE = "\u25B2";
    private static final String BELOW = "\u25BC";
    private static final String HERE = "\u25CF";

    /** How close the body counts as right here, in blocks: a sphere around it. */
    private static final double HERE_RADIUS = 2.0;

    /**
     * From how steep the body counts as above or below: 60 degrees, as the
     * tangent. Steeper than that, which way it lies to the side hardly
     * matters any more - looking down finds it - and an arrow to the side
     * would swing round with every turn of the head.
     */
    private static final double STEEP = Math.tan(Math.toRadians(60.0));

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> offMessageTasks = new HashMap<>();
    /** Until when a line of {@link #flash} stands, as {@link System#currentTimeMillis()} counts. */
    private final Map<UUID, Long> flashUntil = new HashMap<>();

    public CamActionBar(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    public void startActionBar(Player player) {
        if (!settings.isActionBarEnabled()) return;
        stopActionBar(player);
        BukkitRunnable off = offMessageTasks.remove(player.getUniqueId());
        if (off != null) off.cancel();
        ActionBarDisplay display = settings.getActionBarDisplay();
        boolean distance = display == ActionBarDisplay.DISTANCE;
        boolean onText = messages.isMessageEnabled("actionbar-on");
        if (!distance && !onText) return;
        long onTicks = settings.getActionBarOnDuration();
        boolean startOnly = display == ActionBarDisplay.START;
        // Only the start line, and that for no time: nothing to show at all.
        if (startOnly && onTicks <= 0) return;
        long interval = distance ? DISTANCE_INTERVAL : TEXT_INTERVAL;
        BukkitRunnable task = new BukkitRunnable() {
            private long ticks = 0;

            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                long now = ticks;
                ticks += interval;
                if (startOnly && now > 0 && now + TEXT_INTERVAL > onTicks) {
                    // The line sent last stands until on-duration is over,
                    // and then the client lets it fade by itself.
                    this.cancel();
                    actionBarTasks.remove(player.getUniqueId(), this);
                    return;
                }
                if (flashUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
                    return;
                }
                BaseComponent[] line;
                if (!distance) {
                    line = onLine();
                } else if (onText && now < onTicks) {
                    // Sent again before the client lets it fade, as long as
                    // on-duration asks for it.
                    line = now % TEXT_INTERVAL == 0 ? onLine() : null;
                } else {
                    String text = distanceLine(player);
                    line = text == null ? null : TextComponent.fromLegacyText(text);
                }
                if (line != null) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, line);
                }
            }
        };
        task.runTaskTimer(plugin, 0L, interval);
        actionBarTasks.put(player.getUniqueId(), task);
    }

    /**
     * The line {@code actionbar-on}, with how to end camera mode in place of
     * {@code {hint}}: {@code actionbar-hint} while the exit key is on, see
     * {@link de.elia.cameraplugin.session.ExitKey}, and
     * {@code actionbar-hint-command} while it is off - {@code /cam} ends it
     * either way.
     */
    private BaseComponent[] onLine() {
        String key = settings.isExitKeyEnabled() ? "actionbar-hint" : "actionbar-hint-command";
        String hint = messages.isMessageEnabled(key) ? messages.getMessage(key) : "";
        return withKey(messages.getMessage("actionbar-on").replace("{hint}", hint));
    }

    /**
     * Turns a text with colour codes into components, every {@code {key}} in
     * it into the key that swaps the hands.
     *
     * <p>As a keybind and not as the letter F: the client fills it in with
     * the key the player has bound it to, so the hint is right for somebody
     * who changed it as well. It carries the colour the text had up to there,
     * and the text after it goes on in that colour unless it names another
     * one.</p>
     */
    @SuppressWarnings("deprecation")
    private static BaseComponent[] withKey(String text) {
        List<BaseComponent> parts = new ArrayList<>();
        String colors = "";
        int from = 0;
        int at;
        while ((at = text.indexOf(KEY, from)) >= 0) {
            String piece = colors + text.substring(from, at);
            parts.addAll(Arrays.asList(TextComponent.fromLegacyText(piece)));
            colors = ChatColor.getLastColors(piece);
            KeybindComponent key = new KeybindComponent(SWAP_HANDS_KEY);
            BaseComponent[] style = TextComponent.fromLegacyText(colors + " ");
            key.copyFormatting(style[style.length - 1]);
            parts.add(key);
            from = at + KEY.length();
        }
        parts.addAll(Arrays.asList(TextComponent.fromLegacyText(colors + text.substring(from))));
        return parts.toArray(new BaseComponent[0]);
    }

    /**
     * Shows a line for a moment in place of the regular one, such as the new
     * speed after a turn of the mouse wheel. Shown even with
     * {@code action-bar.enabled: false}: it answers something the player has
     * just done, and its own switch in {@code message-settings} turns it off.
     */
    public void flash(Player player, String text) {
        show(player, text, FLASH_MILLIS);
    }

    /**
     * Shows a warning in place of the regular line, such as the end coming
     * close or something after the body. It stands as long as the client
     * shows a line at all, longer than {@link #flash}: it comes unasked, and
     * the player may be looking elsewhere. Shown even with
     * {@code action-bar.enabled: false}, as its own switches turn it off.
     */
    public void warn(Player player, String text) {
        show(player, text, WARN_MILLIS);
    }

    private void show(Player player, String text, long millis) {
        if (text.isEmpty()) return;
        flashUntil.put(player.getUniqueId(), System.currentTimeMillis() + millis);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    /**
     * The line of {@code display: distance}: how far it is to the body and
     * which way it lies. In a world the body is not in, the portal he came out
     * of takes its place, the same spot {@code max-distance} is measured from.
     *
     * @return the line, or {@code null} when its switch is off or nothing can
     *         be measured against
     */
    private String distanceLine(Player player) {
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) return null;
        Location at = player.getLocation();
        Location anchor = plugin.getMovementGuard().distanceAnchor(data, at);
        if (anchor == null) return null;
        boolean atBody = anchor.getWorld().equals(data.getBody().getWorld());
        String key = atBody ? "actionbar-distance" : "actionbar-distance-portal";
        if (!messages.isMessageEnabled(key)) return null;
        double dx = anchor.getX() - at.getX();
        double dy = anchor.getY() - at.getY();
        double dz = anchor.getZ() - at.getZ();
        long blocks = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return messages.getMessage(key)
                .replace("{distance}", String.valueOf(blocks))
                .replace("{arrow}", arrow(dx, dy, dz, at.getYaw()));
    }

    /**
     * Which way the spot lies, seen from where the player looks: an arrow in
     * one of eight directions, or above and below when it lies more than 60
     * degrees up or down, or a dot inside the sphere of
     * {@link #HERE_RADIUS} around it.
     *
     * @param dx  from the player to the spot, east positive
     * @param dy  from the player to the spot, up positive
     * @param dz  from the player to the spot, south positive
     * @param yaw where the player looks, as the game counts it: 0 to the
     *            south, 90 to the west, growing clockwise
     */
    private static String arrow(double dx, double dy, double dz, float yaw) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat * flat + dy * dy <= HERE_RADIUS * HERE_RADIUS) return HERE;
        if (Math.abs(dy) > flat * STEEP) return dy > 0 ? ABOVE : BELOW;
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        int sector = Math.floorMod(Math.round((towards - yaw) / 45.0), ARROWS.length);
        return ARROWS[sector];
    }

    public void stopActionBar(Player player) {
        flashUntil.remove(player.getUniqueId());
        BukkitRunnable task = actionBarTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Ends the line of a running camera mode and shows the one saying it has
     * ended - the latter not while the plugin is being switched off.
     *
     * <p>With {@code actionbar-off} switched off the first line is cleared
     * instead of replaced: the client keeps a line up for up to three seconds,
     * and it would go on saying that camera mode is on after it has ended.</p>
     */
    public void showActionBarOffMessage(Player player) {
        boolean onLineShown = actionBarTasks.containsKey(player.getUniqueId());
        stopActionBar(player);
        if (!settings.isActionBarEnabled() || plugin.isShuttingDown()) return;
        BukkitRunnable existing = offMessageTasks.remove(player.getUniqueId());
        if (existing != null) existing.cancel();
        if (!messages.isMessageEnabled("actionbar-off")) {
            if (onLineShown) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(""));
            }
            return;
        }

        BukkitRunnable task = new BukkitRunnable() {
            private int ticks = 0;


            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                if (ticks >= settings.getActionBarOffDuration()) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(messages.getMessage("actionbar-off")));
                ticks++;
            }
        };

        task.runTaskTimer(plugin, 0L, 1L);
        offMessageTasks.put(player.getUniqueId(), task);
    }

    public void onDisable() {
        for (BukkitRunnable task : actionBarTasks.values()) {
            task.cancel();
        }
        actionBarTasks.clear();
        for (BukkitRunnable task : offMessageTasks.values()) {
            task.cancel();
        }
        offMessageTasks.clear();
        flashUntil.clear();
    }
}
