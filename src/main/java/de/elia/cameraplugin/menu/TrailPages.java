package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import de.elia.cameraplugin.trail.CamTrail;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static de.elia.cameraplugin.menu.CamMenu.*;
import static de.elia.cameraplugin.menu.MenuItems.*;
import static de.elia.cameraplugin.menu.MenuSorts.*;

/**
 * The admin pages of {@code /cam menu} about {@code /cam trail}: the
 * flights of each player, to show them.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class TrailPages {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    TrailPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The players whose flights {@code /cam trail} keeps: a click shows his
     * flights. The last button hides a line that is shown: a water bucket
     * then, with which flight it is and how long it stays yet - or that it
     * stays until it is hidden -, an empty one while none is shown.
     */
    void openTrails(Player player, int pageNumber) {
        CamTrail trail = plugin.getTrail();
        if (!trail.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        List<String> names = new ArrayList<>(trail.recordedNames());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (menu.adminPages.playerSort(player) == PlayerSort.LAST_FLIGHT) {
            // The flights of a player are kept newest first; one still going
            // is the newest of all.
            names.sort(Comparator.comparing((String name) -> !trail.flightsOf(name).isEmpty()
                            && trail.flightsOf(name).get(0).flying()).reversed()
                    .thenComparing(Comparator.comparingLong((String name) -> trail.flightsOf(name).isEmpty() ? 0L
                            : trail.flightsOf(name).get(0).startedAt()).reversed()));
        } else if (menu.adminPages.playerSort(player) == PlayerSort.NEAR) {
            // As in the other lists of players: whoever is as far stays by name.
            Map<String, Double> far = new HashMap<>();
            for (String name : names) {
                far.put(name, distance(player, Bukkit.getPlayerExact(name)));
            }
            names.sort(Comparator.comparingDouble(far::get));
        }
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-trails-title"));
        List<Row> entries = new ArrayList<>();
        for (String name : names) {
            entries.add(new Row(name.toLowerCase(Locale.ROOT), slot -> {
                List<CamTrail.Flight> flights = trail.flightsOf(name);
                Player online = Bukkit.getPlayerExact(name);
                String label = messages.getMessage("menu-admin-player").replace("{player}", name);
                List<String> lore = new ArrayList<>();
                if (!flights.isEmpty()) {
                    lore.addAll(lines(messages.getMessage("menu-admin-trails-count")
                            .replace("{count}", String.valueOf(flights.size()))
                            .replace("{date}", date(flights.get(0).startedAt()))));
                }
                lore.addAll(lines(menu.text(player, "menu-admin-trails-actions")));
                menu.put(page, slot, online != null ? head(online, label, lore) : item(Material.LEAD, label, lore),
                        click -> menu.later(() -> openFlights(player, name, 0)));
            }));
        }
        Listed listed = menu.list(page, "trails:" + menu.adminPages.playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> menu.adminPages.openAdmin(player)));
        CamTrail.Shown shown = trail.shownTo(player);
        List<String> hideLore = new ArrayList<>();
        if (shown == null) {
            hideLore.addAll(lines(messages.getMessage("menu-admin-trails-hide-none")));
        } else {
            if (shown.all()) {
                hideLore.addAll(lines(messages.getMessage("menu-admin-trails-hide-all")
                        .replace("{player}", shown.player())));
            } else if (shown.number() > 0) {
                hideLore.addAll(lines(messages.getMessage("menu-admin-trails-hide-flight")
                        .replace("{number}", String.valueOf(shown.number())).replace("{player}", shown.player())));
            }
            hideLore.addAll(lines(shown.untilHidden() ? messages.getMessage("menu-admin-trails-hide-forever")
                    : messages.getMessage("menu-admin-trails-hide-left")
                            .replace("{time}", CamTimeLimit.formatDuration(shown.secondsLeft()))));
        }
        // The page is drawn anew every second: the bucket empties by itself once the line is gone.
        menu.put(page, SLOT_NEW, shown == null ? Material.BUCKET : Material.WATER_BUCKET,
                messages.getMessage("menu-admin-trails-hide"), hideLore,
                click -> menu.act(player, () -> trail.hideFromMenu(player)));
        menu.adminPages.playerSortButton(player, page, () -> openTrails(player, 0));
        menu.paging(page, number, listed.pages(), n -> openTrails(player, n));
        menu.show(player, page, EVERY_SECOND, () -> openTrails(player, number));
    }

    /**
     * The flights kept of one player, the newest first: under each when it
     * began, how long and how far it went, where it began and ended. A
     * left-click shows it; a right-click shows it and takes him to where it
     * began. The last button shows all of them at once.
     */
    void openFlights(Player player, String name, int pageNumber) {
        CamTrail trail = plugin.getTrail();
        if (!trail.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        List<CamTrail.Flight> flights = trail.flightsOf(name);
        if (flights.isEmpty()) {
            openTrails(player, 0);
            return;
        }
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-flights-title").replace("{player}", name));
        List<Row> entries = new ArrayList<>();
        for (CamTrail.Flight flight : flights) {
            entries.add(new Row(flight.startedAt(), slot -> {
                String when = date(flight.startedAt());
                List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-flight-lore")
                        .replace("{date}", when)
                        .replace("{time}",
                                CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(flight.duration())))
                        .replace("{distance}", String.valueOf(Math.round(flight.distance())))
                        .replace("{from}", trail.place(flight.start()))
                        .replace("{to}", trail.place(flight.last()))));
                if (flight.flying()) {
                    lore.addAll(lines(messages.getMessage("menu-admin-flight-flying")));
                }
                lore.addAll(lines(menu.text(player, "menu-admin-flight-actions")));
                String label = messages.getMessage("menu-admin-flight")
                        .replace("{number}", String.valueOf(flight.number())).replace("{date}", when);
                Consumer<ClickType> action = click -> {
                    if (click.isRightClick()) {
                        menu.act(player, () -> trail.showFlight(player, name, flight.startedAt(), true));
                    } else if (click.isLeftClick()) {
                        menu.act(player, () -> trail.showFlight(player, name, flight.startedAt(), false));
                    }
                };
                menu.put(page, slot, flight.flying() ? Material.ENDER_EYE : Material.PAPER, label, lore, action);
                menu.picks(player, page, slot, List.of(menu.pick("menu-pick-flight-show", action, ClickType.LEFT),
                        menu.pick("menu-pick-flight-go", action, ClickType.RIGHT)));
            }));
        }
        Listed listed = menu.list(page, "flights:" + name.toLowerCase(Locale.ROOT), entries, pageNumber);
        int number = listed.number();
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-trails", click -> menu.later(() -> openTrails(player, 0)));
        menu.put(page, SLOT_NEW, Material.SPYGLASS, "menu-admin-flights-all",
                click -> menu.act(player, () -> trail.command(player, new String[]{name, "all"})));
        menu.paging(page, number, listed.pages(), n -> openFlights(player, name, n));
        // A flight still going grows by the second.
        menu.show(player, page, EVERY_SECOND, () -> openFlights(player, name, number));
    }

    /**
     * A moment as {@code menu-admin-flight-date} writes it, in the time zone
     * of the server. A pattern that is none falls back on year, month, day,
     * hour and minute.
     */
    String date(long millis) {
        ZonedDateTime when = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        try {
            return DateTimeFormatter.ofPattern(messages.getMessage("menu-admin-flight-date")).format(when);
        } catch (IllegalArgumentException ex) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(when);
        }
    }
}
