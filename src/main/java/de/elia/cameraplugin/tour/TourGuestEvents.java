package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the guests of a tour of {@link CamTour} do with the guide's items in
 * their hands, and the guide with his own: the guide's items and hearts are
 * sent again after a click of a guest, his swings and the blocks he places
 * show on his guests' screens, see {@link GuideMirror}, and a guest is kept
 * on the server when his client clicks his own player.
 *
 * <p>A listener of its own, registered next to {@link CamTour}, see
 * {@link CamTour#guestEvents()}; public so that the server calls it as
 * directly as before, when it was part of that class.</p>
 */
public final class TourGuestEvents implements Listener {

    /** Why the server kicks a player who hits or clicks his own player, see {@link #onSelfInteraction}. */
    private static final String SELF_INTERACTION = "Cannot interact with self!";

    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    /** The tours, asked who follows and whom; read only. */
    private final CamTour tours;
    /** What the guests of a guide out of camera mode see of him on their own screen. */
    private final GuideMirror mirror;
    /** The internals that swing a guest's arm on his own client as his guide swings his. */
    private final GuideSwing swing;

    TourGuestEvents(CameraPlugin plugin, CamTour tours, GuideMirror mirror, GuideSwing swing) {
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.tours = tours;
        this.mirror = mirror;
        this.swing = swing;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteract(PlayerInteractEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteractEntity(PlayerInteractEntityEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteractAtEntity(PlayerInteractAtEntityEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInventoryClick(InventoryClickEvent event) {
        mirrorAgain(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInventoryDrag(InventoryDragEvent event) {
        mirrorAgain(event.getWhoClicked());
    }

    /**
     * A guide out of camera mode swings his arm - hits, digs, and up to 26.2
     * places blocks -: his guests who are shown his items see the arm that
     * holds them swing, with {@code follow.mirror-swing}, see
     * {@link GuideSwing}. Up to 26.2 their clients answer with a swing of
     * their own, which leads no tour.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuideSwing(PlayerAnimationEvent event) {
        swingGuests(event.getPlayer(), event.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING, false);
    }

    /**
     * A guide out of camera mode places a block: the item in his hand sinks
     * and comes up again - on his guests' screens too, see
     * {@link GuideMirror#placed}. Since 26.3 his client no longer tells the
     * server it swung for that either: the server swings his arm itself, and
     * no plugin hears of it - so his guests are sent the swing from here, see
     * {@link GuideSwing#serverSwingsOnUse}. Up to 26.2 the swing his client
     * reports does it, see {@link #onGuideSwing}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuidePlace(BlockPlaceEvent event) {
        Player guide = event.getPlayer();
        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        boolean swings = settings.follow().mirrorSwing() && swing.serverSwingsOnUse();
        for (Player guest : shownGuests(guide)) {
            mirror.placed(guest.getUniqueId(), offHand);
            if (swings && !tours.geyserCamera(guest)) {
                swing.swing(guest, guide, offHand, true);
            }
        }
    }

    /**
     * Swings the arm of every guest of this guide who is shown his items, as
     * the guide's swung: at a hit, or at a use of the item.
     */
    private void swingGuests(Player guide, boolean offHand, boolean use) {
        if (settings.follow().mirrorSwing()) {
            for (Player guest : shownGuests(guide)) {
                // Geyser's camera on Bedrock draws no hands, see GuideMirror: nothing to swing.
                if (!tours.geyserCamera(guest)) {
                    swing.swing(guest, guide, offHand, use);
                }
            }
        }
    }

    /** The guests online who are shown this guide, while he leads out of camera mode. */
    private List<Player> shownGuests(Player guide) {
        Tour tour = tours.all().get(guide.getUniqueId());
        List<Player> guests = new ArrayList<>();
        if (tour == null || cameraPlayers.contains(guide.getUniqueId())) {
            return guests;
        }
        for (UUID id : tour.guests.keySet()) {
            Player guest = Bukkit.getPlayer(id);
            if (guest != null && mirror.isShown(id)) {
                guests.add(guest);
            }
        }
        return guests;
    }

    /**
     * Keeps a guest on the server whose client hits or clicks his own player.
     * Seeing through the guide's eyes, he aims from them - and his own
     * player stands right there, around them: his client finds itself under
     * the crosshair, and a click goes to himself. The server answers that
     * with a kick, "Cannot interact with self!", after asking the plugins;
     * turned down here, the click is only lost, as any click of a guest is.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelfInteraction(PlayerKickEvent event) {
        if (tours.follows(event.getPlayer().getUniqueId()) && SELF_INTERACTION.equals(event.getReason())) {
            event.setCancelled(true);
        }
    }

    /**
     * Sends the guide's items and hearts to this guest again at the next
     * tick of the tour, if he is shown them.
     *
     * <p>What he holds is only a picture of the guide's items, see
     * {@link GuideMirror}, and his client uses it like his own. Whatever the
     * server turns down of that - a click on a block, on a body, into his
     * inventory - it answers with his own empty hands, and the pictures would
     * be gone until their next refresh, flickering away. Paper handles what
     * the clients sent at the start of a tick, before the tour's own tick, so
     * they go out again in that very tick.</p>
     */
    private void mirrorAgain(HumanEntity guest) {
        if (tours.follows(guest.getUniqueId())) {
            mirror.again(guest.getUniqueId());
        }
    }

    /**
     * The effects of a guest change on the server - the night vision camera
     * mode gives him, say -, and the server sends him the change at once:
     * those of his guide he is shown go out again at the end of the tick,
     * over it, see {@link GuideMirror}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuestEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player guest && tours.follows(guest.getUniqueId())) {
            mirror.effectsAgain(guest.getUniqueId());
        }
    }
}
