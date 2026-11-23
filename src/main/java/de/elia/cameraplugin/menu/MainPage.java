package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.nightvision.CamNightVision;
import de.elia.cameraplugin.sky.CamSky;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.tour.CamTour;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static de.elia.cameraplugin.menu.CamMenu.*;

/**
 * The main page of {@code /cam menu}: starting and ending camera mode,
 * {@code back}, {@code resume}, the buttons to the spots and the tour, the time,
 * the weather, the night vision and the own statistics.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class MainPage {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    MainPage(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The main page: each button where {@code menu.buttons} puts it, showing
     * the item it names. Photo mode says under its name how it stands now;
     * the time, the weather and the night vision are stepped through right
     * there, see {@link #choicesButton}.
     */
    void openMain(Player player) {
        Page page = menu.page(player, MenuButton.SLOTS, messages.getMessage("menu-title"));
        boolean inCam = plugin.isInCameraMode(player);
        if (settings.isNightVisionPlayerChoice()) {
            nightVisionButton(player, page);
        }
        if (settings.isBackCommandEnabled()) {
            button(page, MenuButton.BACK, "menu-back", null, false,
                    click -> menu.act(player, () -> plugin.getBack().command(player)));
        }
        if (settings.resume().enabled()) {
            button(page, MenuButton.RESUME, "menu-resume", null, false,
                    click -> menu.act(player, () -> plugin.getResume().command(player)));
        }
        if (settings.spots().enabled()) {
            spotsButton(player, page);
        }
        if (settings.follow().enabled()) {
            tourButton(player, page);
        }
        if (settings.photo().enabled()) {
            boolean photo = plugin.getPhotoMode().isActive(player);
            button(page, MenuButton.PHOTO, "menu-photo",
                    messages.getMessage(photo ? "menu-state-on" : "menu-state-off"), photo,
                    click -> menu.act(player, () -> plugin.getPhotoMode().command(player)));
        }
        if (settings.isCamTimeEnabled()) {
            timeButton(player, page);
        }
        if (settings.isCamWeatherEnabled()) {
            weatherButton(player, page);
        }
        button(page, inCam ? MenuButton.END : MenuButton.START, inCam ? "menu-end" : "menu-start", null, false,
                click -> menu.act(player, () -> plugin.getStarter().toggle(player)));
        CamStats stats = plugin.getStats();
        if (stats.showsAnything()) {
            List<String> lore = statLines(player.getUniqueId(), player.getName());
            lore.addAll(lines(menu.text(player, settings.statistics().enabled() ? "menu-stats-actions-reset"
                    : "menu-stats-actions")));
            int slot = settings.menu().slot(MenuButton.STATS);
            Consumer<ClickType> action = click -> {
                if (click.isShiftClick() && settings.statistics().enabled()) {
                    menu.act(player, () -> stats.reset(player));
                } else {
                    menu.act(player, () -> stats.command(player, new String[0]));
                }
            };
            menu.put(page, slot, labelled(settings.menu().icon(MenuButton.STATS).create(plugin.getLogger()),
                    messages.getMessage("menu-stats"), lore), action);
            if (settings.statistics().enabled()) {
                menu.picks(player, page, slot, List.of(menu.pick("menu-pick-stats-show", action, ClickType.LEFT),
                        menu.pick("menu-pick-stats-reset", action, ClickType.SHIFT_LEFT)));
            }
        }
        if (settings.menu().adminAccess().allowsMenu() && menu.adminPages.hasAdminPage(player)) {
            button(page, MenuButton.ADMIN, "menu-admin", null, false, click -> menu.later(() -> menu.adminPages.openAdmin(player)));
        }
        menu.show(player, page, EVERY_SECOND, () -> openMain(player));
    }

    /**
     * The statistics of a player as lines under a button, without their
     * header: the name stands on the button already.
     */
    List<String> statLines(UUID id, String name) {
        List<String> lore = new ArrayList<>();
        for (CamStats.Line line : plugin.getStats().lines(id, name)) {
            String text = line.key().equals("stats-header") ? null : plugin.getStats().text(line);
            if (text != null && !text.isEmpty()) {
                lore.add(text);
            }
        }
        return lore;
    }

    /**
     * One button of the main page, with the item and in the slot
     * {@code menu.buttons} gives it: its name, the text of the same name with
     * {@code -lore} after it, and how it stands now, if it says.
     *
     * @param state how it stands now, for {@code menu-state}, or {@code null}
     * @param glint whether it shines, for something switched on
     */
    void button(Page page, MenuButton which, String key, String state, boolean glint,
                        Consumer<ClickType> action) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage(key + "-lore")));
        if (state != null) {
            lore.addAll(lines(messages.getMessage("menu-state").replace("{state}", state)));
        }
        ItemStack item = labelled(settings.menu().icon(which).create(plugin.getLogger()),
                messages.getMessage(key), lore);
        menu.put(page, settings.menu().slot(which), shine(item, glint), action);
    }

    /**
     * The button of the spots on the main page. While spots are offered to
     * him it shines, says how many, and opens on the tab they wait on.
     */
    void spotsButton(Player player, Page page) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-spots-lore")));
        int offers = plugin.getSpots().offers(player).size();
        if (offers > 0) {
            lore.addAll(lines(messages.getMessage("menu-spots-offers").replace("{count}", String.valueOf(offers))));
        }
        ItemStack item = labelled(settings.menu().icon(MenuButton.SPOTS).create(plugin.getLogger()),
                messages.getMessage("menu-spots"), lore);
        SpotTab tab = offers > 0 ? SpotTab.SHARED : SpotTab.PRIVATE;
        menu.put(page, settings.menu().slot(MenuButton.SPOTS), shine(item, offers > 0),
                click -> menu.later(() -> menu.spotPages.openSpots(player, tab, 0)));
    }

    /**
     * The button of the tour on the main page: under it how his tour stands -
     * whom he follows, or how many guests he has -, and how many
     * invitations wait for him, which make it shine.
     */
    void tourButton(Player player, Page page) {
        CamTour tour = plugin.getTour();
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-tour-lore")));
        Player guide = tour.guideOf(player);
        int guests = tour.guestsOf(player).size();
        if (guide != null) {
            lore.addAll(lines(messages.getMessage("menu-tour-following").replace("{player}", guide.getName())));
        } else if (guests > 0) {
            int max = settings.follow().maxGuests();
            lore.addAll(lines(messages.getMessage(max > 0 ? "menu-tour-guiding-max" : "menu-tour-guiding")
                    .replace("{count}", String.valueOf(guests)).replace("{max}", String.valueOf(max))));
        }
        int invited = tour.invitationsOf(player).size();
        if (invited > 0) {
            lore.addAll(lines(messages.getMessage("menu-tour-invitations").replace("{count}",
                    String.valueOf(invited))));
        }
        ItemStack item = labelled(settings.menu().icon(MenuButton.FOLLOW).create(plugin.getLogger()),
                messages.getMessage("menu-tour"), lore);
        menu.put(page, settings.menu().slot(MenuButton.FOLLOW), shine(item, invited > 0),
                click -> menu.later(() -> menu.followPage.openTour(player, 0)));
    }

    /**
     * The times of {@code /cam time}: the time of the world first, then those
     * it knows by name, in the order of the day. A time of his own in ticks
     * that has no name stands where it falls between them, so that a step
     * from it goes on to the next one of the day, or back to the one before.
     */
    Choices timeChoices(Player player) {
        Long own = plugin.getSky().ownTime(player);
        List<Choice> all = new ArrayList<>();
        all.add(new Choice("reset", messages.getMessage("menu-time-reset")));
        int now = own == null ? 0 : -1;
        for (Map.Entry<String, Long> named : CamSky.namedTimes().entrySet()) {
            long ticks = named.getValue();
            if (now < 0 && own < ticks) {
                now = all.size();
                all.add(ticksChoice(own));
            }
            if (own != null && own == ticks) {
                now = all.size();
            }
            all.add(new Choice(named.getKey(), messages.getMessage("menu-time-" + named.getKey())));
        }
        if (now < 0) {
            now = all.size();
            all.add(ticksChoice(own));
        }
        return new Choices(all, now);
    }

    /** A time without a name, in ticks: it only ever stands for the one he has, never to be stepped to. */
    Choice ticksChoice(long ticks) {
        return new Choice(null, messages.getMessage("menu-time-ticks").replace("{ticks}", String.valueOf(ticks)));
    }

    /** The weathers of {@code /cam weather}: the weather of the world first, then clear and rain. */
    Choices weatherChoices(Player player) {
        WeatherType own = plugin.getSky().ownWeather(player);
        List<Choice> all = List.of(
                new Choice("reset", messages.getMessage("menu-weather-reset")),
                new Choice("clear", messages.getMessage("menu-weather-clear")),
                new Choice("rain", messages.getMessage("menu-weather-rain")));
        return new Choices(all, own == null ? 0 : own == WeatherType.CLEAR ? 1 : 2);
    }

    /**
     * The modes of {@code /cam nightvision}: the one of the server first -
     * which one that is stands in its name -, then always, only in the dark
     * and never. While he picked none of his own, the one of the server holds.
     */
    Choices nightVisionChoices(Player player) {
        CamNightVision nightVision = plugin.getNightVision();
        String server = messages.getMessage("menu-nightvision-" + nightVisionWord(settings.getNightVisionMode()));
        List<Choice> all = List.of(
                new Choice("reset", messages.getMessage("menu-nightvision-reset").replace("{mode}", server)),
                new Choice("on", messages.getMessage("menu-nightvision-on")),
                new Choice("dark", messages.getMessage("menu-nightvision-dark")),
                new Choice("off", messages.getMessage("menu-nightvision-off")));
        String now = nightVision.hasOwnMode(player) ? nightVisionWord(nightVision.ownModeFor(player)) : "reset";
        int at = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).word().equals(now)) {
                at = i;
            }
        }
        return new Choices(all, at);
    }

    /** The button of the night vision on the main page; a click picks the next mode, a right-click the one before. */
    void nightVisionButton(Player player, Page page) {
        choicesButton(player, page, MenuButton.NIGHT_VISION, "menu-nightvision", "{mode}",
                () -> nightVisionChoices(player), plugin.getNightVision().guideStandsIn(player), word -> {
                    plugin.getNightVision().command(player, new String[]{word});
                    nightVisionButton(player, page);
                });
    }

    /** The button of the time of day on the main page; a click picks the next time, a right-click the one before. */
    void timeButton(Player player, Page page) {
        choicesButton(player, page, MenuButton.TIME, "menu-time", "{time}", () -> timeChoices(player),
                plugin.getSky().guideStandsIn(player, true), word -> {
            plugin.getSky().timeCommand(player, new String[]{word});
            timeButton(player, page);
        });
    }

    /** The button of the weather on the main page; a click picks the next weather, a right-click the one before. */
    void weatherButton(Player player, Page page) {
        choicesButton(player, page, MenuButton.WEATHER, "menu-weather", "{weather}", () -> weatherChoices(player),
                plugin.getSky().guideStandsIn(player, false), word -> {
            plugin.getSky().weatherCommand(player, new String[]{word});
            weatherButton(player, page);
        });
    }

    /**
     * A button of the main page that steps through choices, the way the
     * button that sorts a list does: the choice that holds now in its name,
     * under it the text with {@code -lore} after the key, every choice - the
     * one that holds now marked, the others greyed out - and how to step. It
     * shines while the choice is not the first one, that of the world or of
     * the server.
     *
     * <p>The menu stays open: the click does what the command does, with its
     * messages, and the button is put in anew in the window he has open, as
     * it stands then. A second click in quick succession sends a double
     * click after it as well - the window is still the same one -, which
     * steps no further.</p>
     *
     * <p>While the guide he follows shows him his in its place, the choices
     * are his own, which hold again after the tour, and the button says so.</p>
     *
     * <p>On Bedrock every choice is offered to be picked straight away, see
     * {@link CamMenu#openPicks}, the one that holds now marked; once picked, the
     * menu closes.</p>
     *
     * @param fill    the placeholder in the name that the choice of now takes
     * @param fresh   the choices as they stand when asked: drawn now, and asked
     *                again for a step, a tick after the click
     * @param guide   whether the guide he follows shows him his in place of the choice
     * @param choose  takes a choice by the word of its command, a tick after the click
     */
    void choicesButton(Player player, Page page, MenuButton which, String key, String fill,
                               Supplier<Choices> fresh, boolean guide, Consumer<String> choose) {
        Choices choices = fresh.get();
        List<String> lore = new ArrayList<>(lines(messages.getMessage(key + "-lore")));
        if (guide) {
            lore.addAll(lines(messages.getMessage("menu-own-later")));
        }
        for (int i = 0; i < choices.all().size(); i++) {
            lore.addAll(lines(messages.getMessage(i == choices.now() ? "menu-choice-option-current"
                    : "menu-choice-option").replace("{choice}", choices.all().get(i).name())));
        }
        lore.addAll(lines(menu.text(player, "menu-choice-action")));
        String name = messages.getMessage(key).replace(fill, choices.all().get(choices.now()).name());
        ItemStack item = labelled(settings.menu().icon(which).create(plugin.getLogger()), name, lore);
        int slot = settings.menu().slot(which);
        menu.put(page, slot, shine(item, choices.now() != 0), click -> {
            boolean back = click.isRightClick();
            if (back || (click.isLeftClick() && click != ClickType.DOUBLE_CLICK)) {
                menu.later(() -> {
                    if (player.isOnline()) {
                        choose.accept(fresh.get().step(back).word());
                    }
                });
            }
        });
        List<Pick> picks = new ArrayList<>();
        for (int i = 0; i < choices.all().size(); i++) {
            Choice choice = choices.all().get(i);
            // A time of his own without a name only ever stands for the one he has.
            if (choice.word() != null) {
                // Picked from a list, the choice is made: the menu closes, as after a jump.
                picks.add(new Pick(choice.name(), i == choices.now(), () -> menu.act(player,
                        () -> choose.accept(choice.word()))));
            }
        }
        menu.picks(player, page, slot, picks);
    }
}
