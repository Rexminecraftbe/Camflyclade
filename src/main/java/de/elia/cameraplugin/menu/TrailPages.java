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
 * flights of each player, to show them - and with
 * {@link CamTrail#DELETE_PERMISSION} to delete them, one by one or all of a
 * player at once.
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
        List<CamTrail.Recorded> recorded = new ArrayList<>(trail.recorded());
        recorded.sort(Comparator.comparing(CamTrail.Recorded::name, String.CASE_INSENSITIVE_ORDER));
        if (menu.adminPages.playerSort(player) == PlayerSort.LAST_FLIGHT) {
            // The flights of a player are kept newest first; one still going
            // is the newest of all.
            recorded.sort(Comparator.comparing(CamTrail.Recorded::flying).reversed()
                    .thenComparing(Comparator.comparingLong(CamTrail.Recorded::newest).reversed()));
        } else if (menu.adminPages.playerSort(player) == PlayerSort.NEAR) {
            // As in the other lists of players: whoever is as far stays by name.
            Map<String, Double> far = new HashMap<>();
            for (CamTrail.Recorded each : recorded) {
                far.put(each.name(), distance(player, Bukkit.getPlayerExact(each.name())));
            }
            recorded.sort(Comparator.comparingDouble(each -> far.get(each.name())));
        }
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-trails-title"));
        List<Row> entries = new ArrayList<>();
        for (CamTrail.Recorded each : recorded) {
            String name = each.name();
            entries.add(new Row(name.toLowerCase(Locale.ROOT), slot -> {
                Player online = Bukkit.getPlayerExact(name);
                String label = messages.getMessage("menu-admin-player").replace("{player}", name);
                List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-trails-count")
                        .replace("{count}", String.valueOf(each.count()))
                        .replace("{date}", date(each.newest()))));
                lore.addAll(lines(menu.text(player, "menu-admin-trails-actions")));
                menu.put(page, slot, online != null ? head(online, label, lore) : item(Material.LEAD, label, lore),
                        click -> menu.later(() -> openFlights(player, name, 0, false)));
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
     * began; with {@link CamTrail#DELETE_PERMISSION} a shift-click deletes it.
     * The button in the middle of the last row shows all of them at once, the
     * one in the corner deletes all of them - after asking once more: then it
     * keeps them, and the one next to it deletes them.
     *
     * @param deleting whether it asks whether to delete all of them
     */
    void openFlights(Player player, String name, int pageNumber, boolean deleting) {
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
        boolean deletes = trail.mayDelete(player);
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-flights-title").replace("{player}", name));
        int[] shown = {pageNumber};
        List<Row> entries = new ArrayList<>();
        for (CamTrail.Flight flight : flights) {
            entries.add(new Row(flight.startedAt(), slot -> {
                String when = date(flight.startedAt());
                List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-flight-lore")
                        .replace("{date}", when)
                        .replace("{time}",
                                CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(flight.duration())))
                        .replace("{distance}", String.valueOf(Math.round(flight.distance())))
                        .replace("{from}", flight.from())
                        .replace("{to}", flight.to())));
                if (flight.flying()) {
                    lore.addAll(lines(messages.getMessage("menu-admin-flight-flying")));
                }
                // One still going cannot be deleted yet.
                boolean deletable = deletes && !flight.flying();
                lore.addAll(lines(menu.text(player, deletable ? "menu-admin-flight-actions-delete"
                        : "menu-admin-flight-actions")));
                String label = messages.getMessage("menu-admin-flight")
                        .replace("{number}", String.valueOf(flight.number())).replace("{date}", when);
                Consumer<ClickType> action = click -> {
                    if (click.isShiftClick()) {
                        if (deletable) {
                            menu.later(() -> {
                                trail.deleteFlight(player, name, flight.startedAt());
                                openFlights(player, name, shown[0], false);
                            });
                        }
                    } else if (click.isRightClick()) {
                        menu.act(player, () -> trail.showFlight(player, name, flight.startedAt(), true));
                    } else if (click.isLeftClick()) {
                        menu.act(player, () -> trail.showFlight(player, name, flight.startedAt(), false));
                    }
                };
                menu.put(page, slot, flight.flying() ? Material.ENDER_EYE : Material.PAPER, label, lore, action);
                List<Pick> picks = new ArrayList<>(List.of(menu.pick("menu-pick-flight-show", action, ClickType.LEFT),
                        menu.pick("menu-pick-flight-go", action, ClickType.RIGHT)));
                if (deletable) {
                    picks.add(menu.pick("menu-pick-flight-delete", action, ClickType.SHIFT_LEFT));
                }
                menu.picks(player, page, slot, picks);
            }));
        }
        Listed listed = menu.list(page, "flights:" + name.toLowerCase(Locale.ROOT), entries, pageNumber);
        int number = listed.number();
        shown[0] = number;
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-trails", click -> menu.later(() -> openTrails(player, 0)));
        menu.put(page, SLOT_NEW, Material.SPYGLASS, "menu-admin-flights-all",
                click -> menu.act(player, () -> trail.command(player, new String[]{name, "all"})));
        if (deletes) {
            putDeleteAll(player, page, name, flights, number, deleting);
        }
        menu.paging(page, number, listed.pages(), n -> openFlights(player, name, n, false));
        // A flight still going grows by the second; whether it asks stays as it is until he clicks.
        menu.show(player, page, EVERY_SECOND, () -> openFlights(player, name, number, deleting));
    }

    /**
     * The button that deletes all flights of a player, in the corner of the
     * last row: first it asks once more - then it keeps them, and the button
     * next to it, shining, deletes them. One still going stays, which the
     * button says.
     */
    private void putDeleteAll(Player player, Page page, String name, List<CamTrail.Flight> flights, int number,
                              boolean deleting) {
        CamTrail trail = plugin.getTrail();
        boolean flying = flights.get(0).flying();
        int count = flying ? flights.size() - 1 : flights.size();
        if (count == 0) {
            return;
        }
        if (!deleting) {
            menu.put(page, SLOT_FLIGHTS_DELETE, Material.BARRIER, messages.getMessage("menu-admin-flights-delete"),
                    lines(messages.getMessage("menu-admin-flights-delete-lore")),
                    click -> menu.later(() -> openFlights(player, name, number, true)));
            return;
        }
        menu.put(page, SLOT_FLIGHTS_DELETE, Material.BARRIER, messages.getMessage("menu-admin-flights-delete-keep"),
                lines(messages.getMessage("menu-admin-flights-delete-keep-lore")),
                click -> menu.later(() -> openFlights(player, name, number, false)));
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-flights-delete-confirm-lore")
                .replace("{count}", String.valueOf(count)).replace("{player}", name)));
        if (flying) {
            lore.addAll(lines(messages.getMessage("menu-admin-flights-delete-flying")));
        }
        menu.put(page, SLOT_FLIGHTS_DELETE_CONFIRM, shine(item(Material.LAVA_BUCKET,
                messages.getMessage("menu-admin-flights-delete-confirm"), lore), true), click -> {
                    // The second click of a double click on the button next to it is no second thought.
                    if (click == ClickType.DOUBLE_CLICK) {
                        return;
                    }
                    menu.later(() -> {
                        trail.deleteAll(player, name);
                        openFlights(player, name, 0, false);
                    });
                });
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
