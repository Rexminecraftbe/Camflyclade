package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.body.PlayerSkins;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import de.elia.cameraplugin.spot.SpotGuestbook;
import de.elia.cameraplugin.spot.SpotModeration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static de.elia.cameraplugin.menu.CamMenu.*;

/**
 * The pages of the spots of {@code /cam menu}: the own ones, the public
 * ones and the ones shared with the player, each on a tab of its own, and the
 * page that shares a spot with others.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class SpotPages {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    SpotPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The spots, a page of {@value #ENTRIES} at a time, on one of three tabs:
     * all his own - the public ones among them -, the public ones of
     * everybody, and those shared with him - the offers he has not accepted
     * yet first. With sharing switched off there are only his own. The last
     * row: back, the page before, the bell of the guestbook, the tabs, the
     * order, the page after, and on the right the button for a new spot -
     * left-click private, right-click public.
     */
    void openSpots(Player player, SpotTab wanted, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        boolean sharing = settings.spots().sharing();
        SpotTab tab = sharing ? wanted : SpotTab.PRIVATE;
        SpotSort sort = spotSort(player, tab);
        Location from = nearFrom(player);
        List<Row> entries = new ArrayList<>();
        Page page = menu.page(player, LIST, messages.getMessage(sharing ? "menu-spots-title-" + tab.key
                : "menu-spots-title"));
        // A tab of the spots a restriction keeps him from looking at shows him only that.
        SpotModeration.Hold kept = plugin.getSpotModeration().lookHold(player, SpotBans.Kind.valueOf(tab.name()));
        if (kept == null) {
            switch (tab) {
                case PRIVATE -> {
                    for (Spot spot : sorted(spots.own(player), sort, own -> own, own -> "", own -> 0,
                            own -> spots.views(player.getUniqueId(), own), from)) {
                        entries.add(new Row(spot.key(), slot -> putOwnSpot(player, page, slot, spot)));
                    }
                }
                case PUBLIC -> {
                    for (Share share : sorted(spots.publicSpots(), sort, Share::getSpot, Share::getOwnerName,
                            Share::likes, share -> spots.views(share.getOwner(), share.getSpot()), from)) {
                        entries.add(new Row(shareKey(share), slot -> putPublicSpot(player, page, slot, share)));
                    }
                }
                case SHARED -> {
                    // An offer he accepts stays where it is, as one of the spots shared with him.
                    for (Share share : spots.offers(player)) {
                        entries.add(new Row(shareKey(share), slot -> putOffer(player, page, slot, share)));
                    }
                    for (Share share : sorted(spots.accepted(player), sort, Share::getSpot, Share::getOwnerName,
                            Share::likes, share -> spots.views(share.getOwner(), share.getSpot()), from)) {
                        entries.add(new Row(shareKey(share), slot -> putSharedSpot(player, page, slot, share)));
                    }
                }
            }
        }
        Listed listed = menu.list(page, "spots:" + tab.key + ":" + sort.key, entries, pageNumber);
        int number = listed.number();
        if (kept != null) {
            String key = kept.everybody() ? "menu-spots-disabled" : "menu-spots-restricted";
            menu.put(page, 22, Material.GRAY_DYE, messages.getMessage(key),
                    lines(plugin.getSpotModeration().fill(messages.getMessage(key + "-lore"), kept)), NOTHING);
        } else if (listed.empty()) {
            // {button}: the name of the button that saves a spot, for the
            // texts that cannot say where it is.
            String empty = "menu-spots-empty-" + tab.key;
            String button = ChatColor.stripColor(messages.getMessage("menu-new-spot"));
            menu.put(page, 22, Material.GRAY_DYE, messages.getMessage(empty),
                    lines(menu.text(player, empty + "-lore").replace("{button}", button)), NOTHING);
        }
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-main", click -> menu.later(() -> menu.mainPage.openMain(player)));
        if (sharing) {
            for (SpotTab other : SpotTab.values()) {
                putTab(player, page, tab, other);
            }
        }
        menu.newSpotPages.putSort(player, page, SLOT_SPOT_SORT, sort.key, tab.sorts, current -> {
            menu.spotSorts.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(SpotTab.class)).put(tab,
                    SpotSort.of(current));
            openSpots(player, tab, 0);
        });
        List<String> newLore = new ArrayList<>(lines(spotCount(player)));
        if (settings.spots().setInCamOnly()) {
            newLore.addAll(lines(messages.getMessage("menu-new-spot-cam-only")));
        }
        // With its own page, whether it is public is set there: one click starts it.
        boolean createPage = settings.spots().createPage();
        newLore.addAll(lines(menu.text(player, sharing && !createPage ? "menu-new-spot-lore-public"
                : "menu-new-spot-lore")));
        Consumer<ClickType> newSpot = createPage ? click -> menu.act(player, () -> menu.newSpotPages.startNewSpot(player))
                : click -> menu.act(player, () -> menu.askName(player, sharing && click.isRightClick()));
        menu.put(page, SLOT_SPOT_NEW, Material.WRITABLE_BOOK, messages.getMessage("menu-new-spot"), newLore, newSpot);
        if (sharing && !createPage) {
            menu.picks(player, page, SLOT_SPOT_NEW, List.of(menu.pick("menu-pick-spot-private", newSpot, ClickType.LEFT),
                    menu.pick("menu-pick-spot-public", newSpot, ClickType.RIGHT)));
        }
        if (settings.spots().guestbook()) {
            guestbookButton(player, page, () -> openSpots(player, tab, number));
        }
        menu.paging(page, number, listed.pages(), n -> openSpots(player, tab, n), SLOT_SPOT_PREVIOUS, SLOT_SPOT_NEXT);
        menu.show(player, page, EVERY_SECOND, () -> openSpots(player, tab, number));
    }

    /**
     * The bell of the guestbook: how he hears how often his spots are looked
     * at and liked, see {@link SpotGuestbook.Mode}, under its name, and under
     * it every way there is - the one he set marked, the others greyed out -
     * with how often it tells him while he plays. It shines unless he hears
     * nothing. A click takes the next way, a right-click the one before, as
     * the button that sorts a list does. While a restriction keeps him from
     * the spots of others, it says so instead, and a click does nothing.
     *
     * @param again opens the page anew, with the bell as it stands then
     */
    void guestbookButton(Player player, Page page, Runnable again) {
        SpotGuestbook guestbook = plugin.getGuestbook();
        UUID id = player.getUniqueId();
        SpotGuestbook.Mode now = guestbook.modeOf(id);
        List<SpotGuestbook.Mode> modes = List.of(SpotGuestbook.Mode.values());
        String minutes = String.valueOf(settings.spots().guestbookIntervalMinutes());
        String name = messages.getMessage("menu-guestbook").replace("{mode}", guestbookMode(now));
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-guestbook-lore")));
        if (guestbook.isLocked(id)) {
            lore.addAll(lines(messages.getMessage("menu-guestbook-locked")));
            menu.put(page, SLOT_SPOT_GUESTBOOK, item(Material.BELL, name, lore), NOTHING);
            return;
        }
        for (SpotGuestbook.Mode mode : modes) {
            lore.addAll(lines(messages.getMessage(mode == now ? "menu-guestbook-option-current"
                    : "menu-guestbook-option").replace("{mode}", guestbookMode(mode))));
        }
        lore.addAll(lines(messages.getMessage("menu-guestbook-interval").replace("{minutes}", minutes)));
        lore.addAll(lines(menu.text(player, "menu-guestbook-action")));
        menu.put(page, SLOT_SPOT_GUESTBOOK, shine(item(Material.BELL, name, lore), now != SpotGuestbook.Mode.OFF),
                click -> menu.later(() -> {
                    int step = click.isRightClick() ? modes.size() - 1 : 1;
                    guestbook.set(player, modes.get((modes.indexOf(now) + step) % modes.size()));
                    again.run();
                }));
        List<Pick> picks = new ArrayList<>();
        for (SpotGuestbook.Mode mode : modes) {
            picks.add(new Pick(guestbookMode(mode), mode == now, () -> menu.later(() -> {
                guestbook.set(player, mode);
                again.run();
            })));
        }
        menu.picks(player, page, SLOT_SPOT_GUESTBOOK, picks);
    }

    /** What a way of the guestbook is called on the bell. */
    String guestbookMode(SpotGuestbook.Mode mode) {
        return messages.getMessage("menu-guestbook-" + mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * How many spots he has, and how many more he may save under
     * {@code viewpoints.max-per-player}, for the button of a new spot - or
     * only how many he has, while there is no limit.
     */
    String spotCount(Player player) {
        int count = plugin.getSpots().own(player).size();
        if (!settings.spots().limited()) {
            return messages.getMessage("menu-new-spot-count-unlimited").replace("{count}", String.valueOf(count));
        }
        int left = Math.max(0, settings.spots().max() - count);
        return messages.getMessage(left > 0 ? "menu-new-spot-count" : "menu-new-spot-count-full")
                .replace("{count}", String.valueOf(count))
                .replace("{max}", String.valueOf(settings.spots().max()))
                .replace("{left}", String.valueOf(left));
    }

    /**
     * What "nearest first" measures from: his body, the same spot a jump is
     * measured from; out of camera mode, where he stands, which is where his
     * body will be.
     */
    Location nearFrom(Player player) {
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        return data != null ? plugin.getMovementGuard().home(data) : player.getLocation();
    }

    /**
     * The button of one tab of the spots: the one open shines and says so;
     * the tab of the shared spots counts the offers waiting on it.
     */
    void putTab(Player player, Page page, SpotTab open, SpotTab tab) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-spots-tab-" + tab.key + "-lore")));
        int offers = tab == SpotTab.SHARED ? plugin.getSpots().offers(player).size() : 0;
        if (offers > 0) {
            lore.addAll(lines(messages.getMessage("menu-spots-offers").replace("{count}", String.valueOf(offers))));
        }
        if (tab == open) {
            lore.addAll(lines(messages.getMessage("menu-state-current")));
        }
        ItemStack item = item(tab.icon, messages.getMessage("menu-spots-tab-" + tab.key), lore);
        item.setAmount(Math.max(1, Math.min(offers, item.getMaxStackSize())));
        menu.put(page, tab.slot, shine(item, tab == open), click -> menu.later(() -> openSpots(player, tab, 0)));
    }

    /**
     * One of his own spots, private or public - a public one says so, with
     * its likes: left-click jumps to it, right-click shares it or makes it
     * public, shift-click deletes it.
     */
    void putOwnSpot(Player player, Page page, int slot, Spot spot) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(player.getUniqueId(), spot);
        Share share = spots.shareOf(player, spot);
        if (share != null && share.isEveryone()) {
            lore.addAll(lines(messages.getMessage("menu-spot-public-lore")
                    .replace("{likes}", String.valueOf(share.likes()))));
        }
        if (share != null && !share.getPlayers().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-shared-lore").replace("{players}", spots.whom(share))));
        }
        if (share != null && !share.getPending().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-offered-lore")
                    .replace("{players}", spots.offeredTo(share))));
        }
        lore.addAll(reachLore());
        boolean options = settings.spots().options();
        lore.addAll(lines(menu.text(player, options ? "menu-spot-actions-options"
                : settings.spots().sharing() ? "menu-spot-actions-share" : "menu-spot-actions")));
        Consumer<ClickType> action = click -> {
            if (options) {
                if (click.isShiftClick()) {
                    // Nothing: deleting is for the page of the options.
                    return;
                }
                if (click.isRightClick()) {
                    menu.later(() -> menu.newSpotPages.openSpotOptions(player, player.getUniqueId(), player.getName(), spot.key(), false,
                            false));
                } else if (click.isLeftClick()) {
                    menu.act(player, () -> spots.jump(player, spot.name()));
                }
            } else if (click.isShiftClick()) {
                menu.later(() -> {
                    spots.delete(player, spot.name());
                    openSpots(player, SpotTab.PRIVATE, 0);
                });
            } else if (click.isRightClick() && settings.spots().sharing()) {
                menu.later(() -> openShare(player, spot.key(), 0));
            } else if (click.isLeftClick()) {
                menu.act(player, () -> spots.jump(player, spot.name()));
            }
        };
        menu.put(page, slot, CamSpots.iconOf(spot, Material.FILLED_MAP),
                messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore, action);
        List<Pick> picks = new ArrayList<>(List.of(menu.pick("menu-pick-spot-jump", action, ClickType.LEFT)));
        if (options) {
            picks.add(menu.pick("menu-pick-spot-options", action, ClickType.RIGHT));
        } else {
            if (settings.spots().sharing()) {
                picks.add(menu.pick("menu-pick-spot-share", action, ClickType.RIGHT));
            }
            picks.add(menu.pick("menu-pick-spot-delete", action, ClickType.SHIFT_LEFT));
        }
        menu.picks(player, page, slot, picks);
    }

    /**
     * A public spot. One of his own: left-click jumps to it, right-click takes
     * it offline - it stays among his own; it is deleted there. One of another
     * player: left-click jumps to it like to one of his own, in camera mode
     * and within reach, right-click likes it or no longer does; it shines
     * while he likes it.
     */
    void putPublicSpot(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        Spot spot = share.getSpot();
        boolean own = share.getOwner().equals(player.getUniqueId());
        List<String> lore = spotLore(share.getOwner(), spot);
        lore.addAll(lines(messages.getMessage("menu-spot-likes").replace("{likes}", String.valueOf(share.likes()))));
        if (own) {
            lore.addAll(reachLore());
            boolean options = settings.spots().options();
            lore.addAll(lines(menu.text(player, options ? "menu-public-own-actions-options" : "menu-public-own-actions")));
            Consumer<ClickType> action = click -> {
                if (click.isShiftClick()) {
                    // Deleting is for the tab of his own spots, or for its options, not this one.
                    return;
                }
                if (click.isRightClick() && options) {
                    menu.later(() -> menu.newSpotPages.openSpotOptions(player, player.getUniqueId(), player.getName(), spot.key(), false,
                            false));
                } else if (click.isRightClick()) {
                    menu.later(() -> {
                        spots.makePublic(player, spot, false);
                        openSpots(player, SpotTab.PUBLIC, 0);
                    });
                } else if (click.isLeftClick()) {
                    menu.act(player, () -> spots.jump(player, spot.name()));
                }
            };
            menu.put(page, slot, CamSpots.iconOf(spot, Material.FILLED_MAP),
                    messages.getMessage("menu-public-own").replace("{spot}", spot.name()), lore, action);
            menu.picks(player, page, slot, List.of(menu.pick("menu-pick-spot-jump", action, ClickType.LEFT),
                    menu.pick(options ? "menu-pick-spot-options" : "menu-pick-spot-offline", action, ClickType.RIGHT)));
            return;
        }
        boolean liked = share.isLikedBy(player.getUniqueId());
        if (liked) {
            lore.addAll(lines(messages.getMessage("menu-spot-liked")));
        }
        lore.addAll(reachLore());
        lore.addAll(lines(menu.text(player, liked ? "menu-public-spot-actions-unlike"
                : "menu-public-spot-actions")));
        String name = messages.getMessage("menu-shared-spot").replace("{owner}", share.getOwnerName())
                .replace("{spot}", spot.name());
        Consumer<ClickType> action = click -> {
            if (click.isShiftClick()) {
                // Nothing: a slip of the shift key neither jumps nor likes.
                return;
            }
            if (click.isRightClick()) {
                menu.later(() -> {
                    spots.like(player, share);
                    openSpots(player, SpotTab.PUBLIC, 0);
                });
            } else if (click.isLeftClick()) {
                menu.act(player, () -> spots.jump(player, share.label()));
            }
        };
        menu.put(page, slot, shine(item(CamSpots.iconOf(spot, Material.MAP), name, lore), liked), action);
        menu.picks(player, page, slot, List.of(menu.pick("menu-pick-spot-jump", action, ClickType.LEFT),
                menu.pick(liked ? "menu-pick-spot-unlike" : "menu-pick-spot-like", action, ClickType.RIGHT)));
    }

    /** A spot offered to him: left-click accepts it, right-click turns it down. */
    void putOffer(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(share.getOwner(), share.getSpot());
        lore.addAll(lines(menu.text(player, "menu-offer-actions")));
        String name = messages.getMessage("menu-offer").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        Consumer<ClickType> action = click -> {
            if (click.isRightClick()) {
                menu.later(() -> {
                    spots.decline(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            } else if (click.isLeftClick()) {
                menu.later(() -> {
                    spots.accept(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            }
        };
        menu.put(page, slot, shine(item(Material.PAPER, name, lore), true), action);
        menu.picks(player, page, slot, List.of(menu.pick("menu-pick-accept", action, ClickType.LEFT),
                menu.pick("menu-pick-decline", action, ClickType.RIGHT)));
    }

    /**
     * A spot shared with him that he accepted: left-click jumps to it like to
     * one of his own, in camera mode and within reach, right-click takes it
     * off his list.
     */
    void putSharedSpot(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(share.getOwner(), share.getSpot());
        lore.addAll(reachLore());
        lore.addAll(lines(menu.text(player, "menu-shared-spot-actions")));
        String name = messages.getMessage("menu-shared-spot").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        Consumer<ClickType> action = click -> {
            if (click.isShiftClick()) {
                // Nothing: a slip of the shift key neither jumps nor drops it.
                return;
            }
            if (click.isRightClick()) {
                menu.later(() -> {
                    spots.decline(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            } else if (click.isLeftClick()) {
                menu.act(player, () -> spots.jump(player, share.label()));
            }
        };
        menu.put(page, slot, CamSpots.iconOf(share.getSpot(), Material.MAP), name, lore, action);
        menu.picks(player, page, slot, List.of(menu.pick("menu-pick-spot-jump", action, ClickType.LEFT),
                menu.pick("menu-pick-spot-remove", action, ClickType.RIGHT)));
    }

    /**
     * How close he has to be to jump to a spot, over what a click does:
     * without camera mode within {@code viewpoints.start-distance} of it, in
     * camera mode where his camera may fly - or only the latter, while
     * {@code viewpoints.start-distance} is 0.
     */
    List<String> reachLore() {
        double start = settings.spots().startDistance();
        return lines(messages.getMessage(start > 0.0 ? "menu-spot-reach-lore" : "menu-spot-reach-lore-cam-only")
                .replace("{start}", String.valueOf(Math.round(start))));
    }

    /**
     * Where a spot of this owner is, since when, and how often it was looked
     * at, as lines under it - with how often one player counts, while
     * {@code viewpoints.views-cooldown-minutes} holds him back.
     */
    List<String> spotLore(UUID owner, Spot spot) {
        List<String> lore = new ArrayList<>(lines(place(messages.getMessage("menu-spot-lore"), spot)));
        if (spot.created() > 0L) {
            lore.addAll(lines(messages.getMessage("menu-spot-created").replace("{date}", menu.trailPages.date(spot.created()))));
        }
        String look = plugin.getSpots().describe(spot.look());
        if (!look.isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-look").replace("{look}", look)));
        }
        lore.addAll(viewsLore(plugin.getSpots().views(owner, spot)));
        return lore;
    }

    /**
     * How often a spot was looked at, as lines under it - with how often one
     * player counts, while {@code viewpoints.views-cooldown-minutes} holds him
     * back.
     */
    List<String> viewsLore(long views) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-spot-views")
                .replace("{views}", String.valueOf(views))));
        int minutes = settings.spots().viewsCooldownMinutes();
        if (minutes > 0) {
            lore.addAll(lines(messages.getMessage("menu-spot-views-hint")
                    .replace("{minutes}", String.valueOf(minutes))));
        }
        return lore;
    }

    /**
     * Whom one of his spots is shared with: the players online and those it
     * is shared with or offered to who are not, a page of {@value #ENTRIES}
     * at a time, see {@link #sharePage}. A click on a player offers it to
     * him, or takes it back. The beacon in the middle of the last row, on
     * every page, makes it public, or private again; the hopper in its
     * corner sorts the players, see {@link #sharePage}.
     */
    void openShare(Player player, String key, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        Spot spot = null;
        for (Spot own : spots.own(player)) {
            if (own.key().equals(key)) {
                spot = own;
            }
        }
        if (spot == null || !settings.spots().sharing()) {
            openSpots(player, SpotTab.PRIVATE, 0);
            return;
        }
        Spot target = spot;
        Share share = spots.shareOf(player, spot);
        Map<UUID, String> people = sharePeople(player);
        if (share != null) {
            for (Map.Entry<UUID, String> entry : share.getPlayers().entrySet()) {
                people.putIfAbsent(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<UUID, String> entry : share.getPending().entrySet()) {
                people.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        // Shared from its options, it leads back there.
        boolean options = settings.spots().options();
        sharePage(player, spot.name(), "share:" + key, people,
                other -> share == null ? "off" : share.isAcceptedBy(other) ? "on"
                        : share.isOfferedTo(other) ? "offered" : "off",
                (other, name) -> {
                    if (share != null && (share.isAcceptedBy(other) || share.isOfferedTo(other))) {
                        spots.unshareWith(player, target, other, name);
                    } else {
                        Player online = Bukkit.getPlayer(other);
                        spots.shareWith(player, target, online != null ? online : Bukkit.getOfflinePlayer(other));
                    }
                },
                share != null && share.isEveryone(), everybody -> spots.makePublic(player, target, everybody),
                options ? "menu-to-spot" : "menu-to-spots", () -> {
                    if (options) {
                        menu.newSpotPages.openSpotOptions(player, player.getUniqueId(), player.getName(), key, false, false);
                    } else {
                        openSpots(player, SpotTab.PRIVATE, 0);
                    }
                }, number -> openShare(player, key, number), pageNumber);
    }

    /**
     * The page of whom a spot is shared with - one that is saved, see
     * {@link #openShare}, or a new one, see {@link NewSpotPages#openNewShare}: a head for
     * each player, saying how it stands with him, a page of {@value #ENTRIES}
     * at a time; the beacon in the middle of the last row that makes it
     * public or private; the hopper in its corner that sorts the players -
     * those it is shared with first, then those it is offered to, each by
     * name, until he picks another order: by name, or the nearest first -;
     * the way back.
     *
     * @param state    how it stands with a player: {@code on} (he accepted it), {@code offered},
     *                 {@code invited} (a new spot: offered once it is saved) or {@code off}
     * @param toggle   what a click on a player does
     * @param everybody whether it is public
     * @param toPublic what the beacon does: made public ({@code true}) or private
     * @param again    draws the page anew, on the page of the list given
     */
    void sharePage(Player player, String spot, String list, Map<UUID, String> people,
                           Function<UUID, String> state, BiConsumer<UUID, String> toggle, boolean everybody,
                           Consumer<Boolean> toPublic, String backKey, Runnable back, IntConsumer again,
                           int pageNumber) {
        Page page = menu.page(player, LIST, messages.getMessage("menu-share-title").replace("{spot}", spot));
        String sort = shareSort(player);
        // Sorting is stable: whoever stands as far up as another stays by name.
        List<Map.Entry<UUID, String>> ordered = new ArrayList<>(people.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER));
        if (sort.equals("shared")) {
            ordered.sort(Comparator.comparingInt(entry -> shareRank(state.apply(entry.getKey()))));
        } else if (sort.equals("near")) {
            Map<UUID, Double> far = new HashMap<>();
            for (Map.Entry<UUID, String> entry : ordered) {
                far.put(entry.getKey(), distance(player, Bukkit.getPlayer(entry.getKey())));
            }
            ordered.sort(Comparator.comparingDouble(entry -> far.get(entry.getKey())));
        }
        // Filled in below, once the list knows which page of it is shown.
        int[] shown = new int[1];
        List<Row> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : ordered) {
            String now = state.apply(entry.getKey());
            String name = now.equals("off") ? "menu-share-player"
                    : "menu-share-player-" + (now.equals("invited") ? "offered" : now);
            entries.add(new Row(entry.getKey(), slot -> {
                ItemStack head = item(Material.PLAYER_HEAD,
                        messages.getMessage(name).replace("{player}", entry.getValue()),
                        lines(menu.text(player, "menu-share-" + now + "-lore")));
                Player other = Bukkit.getPlayer(entry.getKey());
                if (other != null && head.getItemMeta() instanceof SkullMeta skull) {
                    // Only of a player online: one offline might have to be
                    // asked of Mojang for his skin.
                    PlayerSkins.putOn(skull, other);
                    head.setItemMeta(skull);
                }
                menu.put(page, slot, head, click -> menu.later(() -> {
                    toggle.accept(entry.getKey(), entry.getValue());
                    again.accept(shown[0]);
                }));
            }));
        }
        Listed listed = menu.list(page, list + ":" + sort, entries, pageNumber);
        int number = listed.number();
        shown[0] = number;
        menu.put(page, SLOT_SHARE_PUBLIC, shine(item(Material.BEACON, messages.getMessage("menu-share-everybody"),
                lines(menu.text(player, everybody ? "menu-share-public-lore" : "menu-share-private-lore"))),
                everybody), click -> menu.later(() -> {
                    toPublic.accept(!everybody);
                    again.accept(number);
                }));
        menu.put(page, SLOT_BACK, Material.ARROW, backKey, click -> menu.later(back));
        menu.newSpotPages.putSort(player, page, SLOT_SHARE_SORT, sort, SHARE_SORTS, chosen -> {
            menu.shareSorts.put(player.getUniqueId(), chosen);
            again.accept(0);
        });
        menu.paging(page, number, listed.pages(), again::accept);
        menu.show(player, page, EVERY_SECOND, () -> again.accept(number));
    }

    /** How he has the page that shares a spot sorted: those it is shared with first, until he picks another order. */
    String shareSort(Player player) {
        String sort = menu.shareSorts.get(player.getUniqueId());
        return sort != null && SHARE_SORTS.contains(sort) ? sort : SHARE_SORTS.get(0);
    }

    /** How he has a tab of the spots sorted: the newest first, until he picks another order. */
    SpotSort spotSort(Player player, SpotTab tab) {
        Map<SpotTab, SpotSort> own = menu.spotSorts.get(player.getUniqueId());
        SpotSort sort = own == null ? null : own.get(tab);
        return sort != null && tab.sorts.contains(sort.key) ? sort : SpotSort.DATE;
    }
}
