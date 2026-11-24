package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.tour.CamTour;
import de.elia.cameraplugin.tour.TourBans;
import de.elia.cameraplugin.tour.TourModeration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static de.elia.cameraplugin.menu.CamMenu.*;
import static de.elia.cameraplugin.menu.MenuItems.*;
import static de.elia.cameraplugin.menu.MenuSorts.*;

/**
 * The page of {@code /cam follow} in {@code /cam menu}: who leads, who
 * follows, the invitations, and the view.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class FollowPage {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    FollowPage(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The tour. While he follows one: whom, and the button that leaves it.
     * Otherwise, a page of {@value #ENTRIES} at a time: the invitations he
     * has - left-click accepts, right-click turns down -, his guests - a
     * click sends one away -, and whom he could invite: players close to him
     * who could follow him from where they are, in camera mode or not, with
     * how far away they are; a click invites one. One a restriction keeps
     * from taking part shows up all the same, with why he cannot be invited.
     * Is there nobody, the page says how close they would have to be. While
     * he has guests, the last row has the button that ends the tour, and
     * while there is anybody to invite, with {@code follow.invite-all}, the
     * beacon in its corner that invites all of them at once. A
     * restriction that keeps him from leading takes the place of the players
     * to invite, one that keeps him from taking part that of the button that
     * ends the tour - his own, or the one of everybody, which says that it
     * is switched off. Both pages have the button that switches between the
     * guide's view and looking round freely in the middle of their last row,
     * where the guide's view works.
     */
    void openTour(Player player, int pageNumber) {
        CamTour tour = plugin.getTour();
        if (!settings.follow().enabled()) {
            menu.mainPage.openMain(player);
            return;
        }
        Player guide = tour.guideOf(player);
        if (guide != null) {
            Page page = menu.page(player, SMALL, messages.getMessage("menu-tour-title"));
            menu.put(page, 11, head(guide, messages.getMessage("menu-tour-guide").replace("{player}", guide.getName()),
                    lines(messages.getMessage("menu-tour-guide-lore").replace("{player}", guide.getName()))),
                    NOTHING);
            viewButton(player, page, 13);
            menu.put(page, 15, Material.BARRIER, "menu-tour-leave", click -> menu.act(player, () -> tour.leave(player)));
            menu.put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-main", click -> menu.later(() -> menu.mainPage.openMain(player)));
            menu.show(player, page, EVERY_SECOND, () -> openTour(player, 0));
            return;
        }
        // Each player keeps his place, whether he invited him, follows him or
        // could be invited: one who accepts an invitation stays where he was,
        // as a guest. Who invited him is an entry of its own - he could be
        // invited by him as well.
        List<Row> entries = new ArrayList<>();
        Page page = menu.page(player, LIST, messages.getMessage("menu-tour-title"));
        for (Player inviter : tour.invitationsOf(player)) {
            String name = inviter.getName();
            Consumer<ClickType> action = click -> {
                if (click.isRightClick()) {
                    menu.later(() -> {
                        tour.decline(player, inviter, name);
                        openTour(player, 0);
                    });
                } else if (click.isLeftClick()) {
                    menu.act(player, () -> tour.accept(player, inviter, name));
                }
            };
            entries.add(new Row("invitation/" + inviter.getUniqueId(), slot -> {
                menu.put(page, slot, shine(head(inviter,
                        messages.getMessage("menu-tour-invitation").replace("{player}", name),
                        lines(menu.text(player, "menu-tour-invitation-lore").replace("{player}", name))), true), action);
                menu.picks(player, page, slot, List.of(menu.pick("menu-pick-follow", action, ClickType.LEFT),
                        menu.pick("menu-pick-decline", action, ClickType.RIGHT)));
            }));
        }
        TourModeration moderation = plugin.getTourModeration();
        TourModeration.Hold lead = moderation.hold(player, TourBans.What.LEAD);
        TourModeration.Hold join = moderation.hold(player, TourBans.What.JOIN);
        if (lead != null && lead.everybody() && join != null && join.everybody()) {
            // Tours switched off keep him from both: said once.
            join = null;
        }
        List<Player> guests = tour.guestsOf(player);
        for (Player guest : guests) {
            String name = guest.getName();
            entries.add(new Row(guest.getUniqueId(), slot -> menu.put(page, slot, head(guest,
                    messages.getMessage("menu-tour-guest").replace("{player}", name),
                    lines(menu.text(player, "menu-tour-guest-lore"))), click -> menu.later(() -> {
                        tour.kick(player, guest, name);
                        openTour(player, 0);
                    }))));
        }
        // Whom he could invite - unless he may not lead; one who may not take
        // part shows up all the same, with why he cannot be invited.
        if (tour.canLead(player) && lead == null) {
            boolean full = tour.isFull(player);
            for (Player other : tour.candidates(player)) {
                String name = other.getName();
                TourModeration.Hold kept = moderation.hold(other, TourBans.What.JOIN);
                long holds = tour.invitationLeft(player, other);
                long wait = tour.inviteWait(player, other);
                String key = kept != null ? kept.everybody() ? "menu-tour-candidate-disabled-lore"
                        : "menu-tour-candidate-restricted-lore"
                        : full ? "menu-tour-candidate-full-lore"
                        : holds > 0L ? "menu-tour-candidate-invited-lore"
                        : wait > 0L ? "menu-tour-candidate-wait-lore"
                        : plugin.isInCameraMode(other) ? "menu-tour-candidate-lore"
                        : "menu-tour-candidate-outside-lore";
                long blocks = Math.round(player.getLocation().distance(other.getLocation()));
                String lore = menu.text(player, key).replace("{blocks}", String.valueOf(blocks))
                        .replace("{max}", String.valueOf(settings.follow().maxGuests()))
                        .replace("{expires}", String.valueOf(holds)).replace("{seconds}", String.valueOf(wait));
                entries.add(new Row(other.getUniqueId(), slot -> menu.put(page, slot, head(other,
                        messages.getMessage("menu-tour-candidate").replace("{player}", name), lines(lore)),
                        click -> menu.later(() -> {
                            tour.invite(player, other, name);
                            openTour(player, 0);
                        }))));
            }
        }
        Listed listed = menu.list(page, "tour", entries, pageNumber);
        int number = listed.number();
        if (listed.empty() && lead != null) {
            menu.put(page, 22, tourKept(TourBans.What.LEAD, lead), NOTHING);
        } else if (listed.empty() && tour.canLead(player)) {
            // How close one has to be: out of camera mode to the guide's
            // camera, in camera mode camera to camera.
            double start = settings.follow().startDistance();
            menu.put(page, 22, Material.GRAY_DYE, messages.getMessage("menu-tour-empty"),
                    lines(messages.getMessage(start > 0.0 ? "menu-tour-empty-lore" : "menu-tour-empty-lore-cam-only")
                            .replace("{start}", String.valueOf(Math.round(start)))
                            .replace("{radius}", String.valueOf(Math.round(settings.follow().radius())))),
                    NOTHING);
        } else if (listed.empty()) {
            menu.put(page, 22, Material.GRAY_DYE, "menu-tour-not-in-cam", NOTHING);
        }
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-main", click -> menu.later(() -> menu.mainPage.openMain(player)));
        if (!guests.isEmpty()) {
            menu.put(page, SLOT_TOUR_END, Material.BARRIER, "menu-tour-end", click -> menu.act(player, () -> tour.end(player)));
        } else if (lead != null && !listed.empty()) {
            menu.put(page, SLOT_TOUR_END, tourKept(TourBans.What.LEAD, lead), NOTHING);
        } else if (join != null) {
            menu.put(page, SLOT_TOUR_END, tourKept(TourBans.What.JOIN, join), NOTHING);
        }
        if (settings.follow().inviteAll() && lead == null && tour.canLead(player)) {
            inviteAllButton(player, page);
        }
        viewButton(player, page, SLOT_TOUR_VIEW);
        menu.paging(page, number, listed.pages(), n -> openTour(player, n));
        menu.show(player, page, EVERY_SECOND, () -> openTour(player, number));
    }

    /**
     * The beacon that invites everybody he could invite at once: how many,
     * and how close they have to be for it - out of camera mode
     * {@code follow.start-distance}, in camera mode {@code follow.radius} -,
     * and that a click invites them. Only while anybody is there to be
     * invited - not those already invited, those he may not invite again
     * yet, nor those kept from taking part.
     */
    void inviteAllButton(Player player, Page page) {
        int count = plugin.getTour().invitable(player).size();
        if (count == 0) {
            return;
        }
        double start = settings.follow().startDistance();
        String lore = menu.text(player, start > 0.0 ? "menu-tour-invite-all-lore"
                        : "menu-tour-invite-all-lore-cam-only")
                .replace("{count}", String.valueOf(count))
                .replace("{start}", String.valueOf(Math.round(start)))
                .replace("{radius}", String.valueOf(Math.round(settings.follow().radius())));
        menu.put(page, SLOT_EVERYBODY, shine(item(Material.BEACON, messages.getMessage("menu-tour-invite-all"),
                lines(lore)), true), click -> menu.later(() -> {
                    plugin.getTour().inviteAll(player);
                    openTour(player, 0);
                }));
    }

    /**
     * A grey item that says what keeps him from leading tours or taking part
     * in them: his own restriction, or that tours are switched off - for how
     * long still and why.
     */
    ItemStack tourKept(TourBans.What what, TourModeration.Hold hold) {
        String key = hold.everybody() ? "menu-tour-disabled" : "menu-tour-" + what.key + "-restricted";
        return item(Material.GRAY_DYE, messages.getMessage(key),
                lines(plugin.getTourModeration().fill(messages.getMessage(key + "-lore"), hold)));
    }

    /**
     * Whether he sees through the eyes of his guide on tours or looks round
     * freely: how it stands under its name; a click switches. Only where the
     * guide's view works at all.
     */
    void viewButton(Player player, Page page, int slot) {
        CamTour tour = plugin.getTour();
        if (!tour.viewWorks()) {
            return;
        }
        boolean guide = tour.seesThroughGuide(player);
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-tour-view-lore")));
        lore.addAll(lines(messages.getMessage("menu-state").replace("{state}",
                messages.getMessage(guide ? "menu-tour-view-guide" : "menu-tour-view-free"))));
        menu.put(page, slot, item(guide ? Material.ENDER_EYE : Material.SPYGLASS, messages.getMessage("menu-tour-view"),
                lore), click -> menu.later(() -> {
                    tour.toggleView(player);
                    openTour(player, 0);
                }));
    }
}
