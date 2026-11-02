package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.ClientFrost;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a guest of a guide out of camera mode sees of the guide on his own
 * screen, {@code follow.mirror-...}: the guide's hearts as his own, the item in
 * the guide's hand in his, the one in the guide's off hand in his, the one on
 * the guide's head on his - a carved pumpkin with the rim it draws round the
 * screen -, the guide's effects and the guide's frost, and if switched on the
 * guide's hunger and experience. How the guide swings the items,
 * {@link GuideSwing} shows.
 *
 * <p>All of it goes to the client of the guest only, through
 * {@code sendHealthUpdate} and {@code sendEquipmentChange}: on the server his
 * health stays his, and his hands stay as empty as camera mode keeps them.
 * There is nothing he could take, drop or use, and the rest of the guide's
 * inventory he never sees. His client does use the pictures like his own,
 * though, and whatever the server turns down of that - a click on a block, on
 * a body, into his inventory - it answers with his own empty hands: the tour
 * has the pictures sent again in the same tick, see {@link #again}. They are
 * sent again every {@value #REFRESH} ticks anyway, and whenever the guide's
 * hearts or items change, so whatever else the server sends him of his own in
 * between - a hit on his body - does not stay either.</p>
 *
 * <p>Hearts, hunger and experience go out at the end of the tick, like the
 * effects: the server sends the guest his own whenever they change, and the
 * guide's go over that again before his client draws it. Seen through the
 * guide's eyes, a client draws the hearts and the hunger after the guide's
 * body there - the hunger always full, as no client is ever sent another
 * player's -, so the guide's hunger shows only while the guest looks round
 * freely. The experience bar it always draws after its own player.</p>
 *
 * <p>Once he is off the tour, or his guide starts camera mode, which empties
 * the guide's hands, the client is sent his own health and his own empty hands
 * again. Should he leave the server instead, there is nothing to put back: the
 * server sends him what is his when he joins.</p>
 *
 * <p>The guide's effects go to the guest's client twice. As the guest's own:
 * a client shows the icons, the brighter world of night vision and the wobble
 * of nausea after its own player - all but the effects it moves its own player
 * by, {@link #MOVING}, which would carry his camera off on his client. And on
 * the guide's body there: the fog of blindness and darkness and the colour of
 * the hearts, a client takes from the player it looks through - with
 * {@code guide-view}, the guide. On the server nothing changes: the guest's own
 * effects stay his, and once one of the guide's ends, his own of that kind, if
 * he has one, is shown again.</p>
 *
 * <p>The frost goes as the guest's own as well, see {@link GuideFrost}. Effects
 * and frost go out at the end of every tick, see {@link #tickEnd}: whatever the
 * server sends him of his own - night vision camera mode gives or takes, his
 * own frost - goes out during the tick, and the guide's goes over it before
 * his client draws the next picture.</p>
 *
 * <p>When the guide places a block, a client lowers the item in his hand and
 * brings it up again - after a change of its count, and in creative mode,
 * where the count stays, at every use of its own. The guest's client is sent
 * the item anew, see {@link #placed}; in creative mode with a count one off
 * from the one it shows, so that it lowers and raises the item too. Seen
 * through the guide's eyes, the count shows nowhere: the hotbar there is the
 * guide's.</p>
 *
 * <p>Hearts of 0 are never sent: the client would show the screen of death.
 * A guide who dies ends the tour first, see {@link CamTour}.</p>
 */
final class GuideMirror {

    /** How often everything is sent again, in ticks, changed or not. */
    private static final int REFRESH = 10;

    /**
     * The effects a client moves its own player by: shown to a guest as his
     * own, they would carry his camera off on his client.
     */
    private static final Set<PotionEffectType> MOVING = Set.of(PotionEffectType.LEVITATION,
            PotionEffectType.SLOW_FALLING, PotionEffectType.JUMP_BOOST, PotionEffectType.DOLPHINS_GRACE);

    private final CamSettings settings;
    /** Shows a guest the guide's frost as his own. */
    private final GuideFrost frost;
    /** What each guest's client was sent, by the id of the guest. */
    private final Map<UUID, Shown> shown = new HashMap<>();

    /** What one guest's client was sent last. */
    private static final class Shown {
        /** Whether the guest's client was sent the guide's hearts or hunger, and what. */
        boolean vitalsShown;
        double sentHealth;
        int sentFood;
        float sentSaturation;
        /** The guest's own when they were sent: the server sends him those whenever they change. */
        double ownHealth;
        int ownFood;
        float ownSaturation;
        /** Whether the guest's client was sent the guide's experience, what, and the guest's own then. */
        boolean experienceShown;
        float sentExp;
        int sentLevel;
        int ownExperience;
        /** The guide's items, as they were when last sent; {@code null} while none were sent. */
        ItemStack mainHand;
        ItemStack offHand;
        ItemStack head;
        /** Whether the item in a hand is shown with a count one off, see {@link #placed}. */
        boolean mainHandShift;
        boolean offHandShift;
        /** Whether the guide placed a block with the item in a hand since the last update. */
        boolean mainHandUsed;
        boolean offHandUsed;
        /** The slot of the hotbar his client held the item of the hand in. */
        int slot = -1;
        /** Ticks since everything was last sent. */
        int sinceRefresh;
        /** Whether everything is sent at the next update, whatever the time. */
        boolean again;
        /** Whether everything was sent at the last update: the effects go again at the end of the tick, too. */
        boolean refreshed;
        /** The guide, whose effects and frost go out at the end of the tick; {@code null} before the first update. */
        Player guide;
        /** The guide's effects sent as the guest's own, by their kind. */
        final Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
        /** The guide's effects sent on the guide's body, by their kind. */
        final Map<PotionEffectType, PotionEffect> onGuide = new HashMap<>();
        /** Whether the effects go out again at the end of the tick: the server just sent him some of his own. */
        boolean effectsAgain;
        /** The frost sent, -1 while none was. */
        int frost = -1;
        /** The guest's own frost on the server when it was sent, and the ticks since. */
        int ownFrost;
        int sinceFrost;
    }

    GuideMirror(CamSettings settings, GuideFrost frost) {
        this.settings = settings;
        this.frost = frost;
    }

    /**
     * Every tick, for each guest of a guide out of camera mode: whatever
     * changed of the guide since, or all of it now and then. Effects and
     * frost follow at the end of the tick, see {@link #tickEnd}.
     */
    void update(Player guide, Player guest) {
        Shown state = shown.computeIfAbsent(guest.getUniqueId(), id -> new Shown());
        boolean refresh = state.again || ++state.sinceRefresh >= REFRESH;
        if (refresh) {
            state.sinceRefresh = 0;
            state.again = false;
        }
        state.refreshed |= refresh;
        state.guide = guide;
        int slot = guest.getInventory().getHeldItemSlot();
        if (state.slot >= 0 && slot != state.slot) {
            // The picture stayed in the slot his client held before.
            guest.updateInventory();
            refresh = true;
        }
        state.slot = slot;
        ItemStack mainHand = guide.getInventory().getItemInMainHand();
        ItemStack offHand = guide.getInventory().getItemInOffHand();
        // Another item: shown with its own count again.
        state.mainHandShift &= state.mainHand != null && state.mainHand.isSimilar(mainHand);
        state.offHandShift &= state.offHand != null && state.offHand.isSimilar(offHand);
        // Placed a block, and the count stayed - creative mode: one off now.
        boolean mainHandUsed = state.mainHandUsed && mainHand.equals(state.mainHand);
        boolean offHandUsed = state.offHandUsed && offHand.equals(state.offHand);
        state.mainHandUsed = false;
        state.offHandUsed = false;
        state.mainHandShift ^= mainHandUsed;
        state.offHandShift ^= offHandUsed;
        state.mainHand = mirror(guest, EquipmentSlot.HAND, settings.isTourMirrorMainHand(), mainHand,
                guest.getInventory().getItemInMainHand(), state.mainHand, state.mainHandShift,
                refresh || mainHandUsed);
        state.offHand = mirror(guest, EquipmentSlot.OFF_HAND, settings.isTourMirrorOffHand(), offHand,
                guest.getInventory().getItemInOffHand(), state.offHand, state.offHandShift, refresh || offHandUsed);
        state.head = mirror(guest, EquipmentSlot.HEAD, settings.isTourMirrorHead(),
                guide.getInventory().getHelmet(), guest.getInventory().getHelmet(), state.head, false, refresh);
    }

    /**
     * Sends the guest the guide's item for one slot, if it changed or it is
     * time; a slot no longer mirrored is sent his own again - as empty as
     * camera mode keeps it.
     *
     * @param shift whether to show it with a count one off, see {@link #placed}
     * @return the guide's item his client was sent there, {@code null} for
     *         nothing sent
     */
    private static ItemStack mirror(Player guest, EquipmentSlot slot, boolean on, ItemStack item, ItemStack own,
                                    ItemStack before, boolean shift, boolean refresh) {
        if (!on) {
            if (before != null) {
                guest.sendEquipmentChange(guest, slot, own == null ? new ItemStack(Material.AIR) : own);
            }
            return null;
        }
        ItemStack now = item == null ? new ItemStack(Material.AIR) : item;
        if (refresh || before == null || !now.equals(before)) {
            guest.sendEquipmentChange(guest, slot, shown(now, shift));
        }
        return now.clone();
    }

    /** The item as the guest's client is shown it: with a count one off, if so. */
    private static ItemStack shown(ItemStack item, boolean shift) {
        if (!shift || item.getType().isAir()) {
            return item;
        }
        ItemStack copy = item.clone();
        copy.setAmount(item.getAmount() > 1 ? item.getAmount() - 1 : item.getAmount() + 1);
        return copy;
    }

    /**
     * The guide placed a block with the item in this hand. The next update -
     * on Paper still in this tick, once the server has taken the block off
     * the stack - sends it: changed anyway, or in creative mode, where the
     * count stays, one off from what the guest's client shows.
     */
    void placed(UUID guest, boolean offHand) {
        Shown state = shown.get(guest);
        if (state == null) {
            return;
        }
        if (offHand) {
            state.offHandUsed = true;
        } else {
            state.mainHandUsed = true;
        }
    }

    /**
     * At the end of every tick, for each guest shown his guide: the guide's
     * effects and frost, whatever changed of them, after all the server sent
     * him of his own during the tick.
     */
    void tickEnd() {
        for (Map.Entry<UUID, Shown> entry : shown.entrySet()) {
            Shown state = entry.getValue();
            Player guest = Bukkit.getPlayer(entry.getKey());
            if (guest == null || state.guide == null || !state.guide.isOnline()) {
                continue;
            }
            boolean refreshed = state.refreshed;
            boolean refresh = refreshed || state.effectsAgain;
            state.refreshed = false;
            state.effectsAgain = false;
            vitals(state.guide, guest, state, refreshed);
            experience(state.guide, guest, state, refreshed);
            effects(state.guide, guest, state, refresh);
            frost(state.guide, guest, state);
        }
    }

    /**
     * Sends the guest the guide's hearts and hunger, as far as switched on -
     * his own in place of the others -, if they or his own changed, or now
     * and then. Hearts of 0 never: his client would show the screen of death.
     */
    private void vitals(Player guide, Player guest, Shown state, boolean refresh) {
        boolean hearts = settings.isTourMirrorHealth();
        boolean hunger = settings.isTourMirrorFood();
        if (!hearts && !hunger) {
            if (state.vitalsShown) {
                guest.sendHealthUpdate();
                state.vitalsShown = false;
            }
            return;
        }
        double health = (hearts ? guide : guest).getHealth();
        int food = (hunger ? guide : guest).getFoodLevel();
        float saturation = (hunger ? guide : guest).getSaturation();
        if (health <= 0.0) {
            return;
        }
        boolean ownChanged = guest.getHealth() != state.ownHealth || guest.getFoodLevel() != state.ownFood
                || guest.getSaturation() != state.ownSaturation;
        if (refresh || !state.vitalsShown || ownChanged || health != state.sentHealth || food != state.sentFood
                || saturation != state.sentSaturation) {
            guest.sendHealthUpdate(health, food, saturation);
            state.vitalsShown = true;
            state.sentHealth = health;
            state.sentFood = food;
            state.sentSaturation = saturation;
            state.ownHealth = guest.getHealth();
            state.ownFood = guest.getFoodLevel();
            state.ownSaturation = guest.getSaturation();
        }
    }

    /** Sends the guest the guide's experience bar and level, if it or his own changed, or now and then. */
    private void experience(Player guide, Player guest, Shown state, boolean refresh) {
        if (!settings.isTourMirrorExperience()) {
            if (state.experienceShown) {
                guest.sendExperienceChange(guest.getExp(), guest.getLevel());
                state.experienceShown = false;
            }
            return;
        }
        float exp = guide.getExp();
        int level = guide.getLevel();
        if (refresh || !state.experienceShown || exp != state.sentExp || level != state.sentLevel
                || guest.getTotalExperience() != state.ownExperience) {
            guest.sendExperienceChange(exp, level);
            state.experienceShown = true;
            state.sentExp = exp;
            state.sentLevel = level;
            state.ownExperience = guest.getTotalExperience();
        }
    }

    /** Sends the guest the guide's effects that changed, or all of them now and then. */
    private void effects(Player guide, Player guest, Shown state, boolean refresh) {
        Map<PotionEffectType, PotionEffect> now = new HashMap<>();
        if (settings.isTourMirrorEffects()) {
            for (PotionEffect effect : guide.getActivePotionEffects()) {
                now.put(effect.getType(), effect);
            }
        }
        for (Iterator<PotionEffectType> sent = state.effects.keySet().iterator(); sent.hasNext(); ) {
            PotionEffectType type = sent.next();
            if (!now.containsKey(type)) {
                showOwn(guest, type);
                sent.remove();
            }
        }
        for (Iterator<PotionEffectType> sent = state.onGuide.keySet().iterator(); sent.hasNext(); ) {
            PotionEffectType type = sent.next();
            if (!now.containsKey(type)) {
                guest.sendPotionEffectChangeRemove(guide, type);
                sent.remove();
            }
        }
        for (PotionEffect effect : now.values()) {
            if (!MOVING.contains(effect.getType())
                    && (renewed(state.effects.put(effect.getType(), effect), effect) || refresh)) {
                guest.sendPotionEffectChange(guest, effect);
            }
            if (renewed(state.onGuide.put(effect.getType(), effect), effect) || refresh) {
                guest.sendPotionEffectChange(guide, effect);
            }
        }
    }

    /**
     * Whether the client needs an effect anew: new, of another kind - or
     * longer than before, taken again. That it runs out, it counts by itself.
     */
    private static boolean renewed(PotionEffect before, PotionEffect now) {
        return before == null || before.getAmplifier() != now.getAmplifier() || before.isAmbient() != now.isAmbient()
                || before.hasParticles() != now.hasParticles() || before.hasIcon() != now.hasIcon()
                || before.isInfinite() != now.isInfinite() || now.getDuration() > before.getDuration();
    }

    /** Shows the guest his own effect of this kind again, or none if he has none. */
    private static void showOwn(Player guest, PotionEffectType type) {
        PotionEffect own = guest.getPotionEffect(type);
        if (own != null) {
            guest.sendPotionEffectChange(guest, own);
        } else {
            guest.sendPotionEffectChangeRemove(guest, type);
        }
    }

    /**
     * Sends the guest the guide's frost as his own, if it or his own changed
     * - the server just sent him that -, or now and then: his client may have
     * counted some up by itself, see {@link ClientFrost}.
     */
    private void frost(Player guide, Player guest, Shown state) {
        if (!settings.isTourMirrorFrost() || !frost.isAvailable()) {
            if (state.frost >= 0) {
                ClientFrost.resend(guest);
                state.frost = -1;
            }
            return;
        }
        int ticks = guide.getFreezeTicks();
        int own = guest.getFreezeTicks();
        if (ticks != state.frost || own != state.ownFrost || ++state.sinceFrost >= CamTour.FROST_REFRESH) {
            if (frost.show(guest, ticks)) {
                state.frost = ticks;
            }
            state.ownFrost = own;
            state.sinceFrost = 0;
        }
    }

    /**
     * Has everything sent to the guest again at the next update: the server
     * has just sent him his own empty hands.
     */
    void again(UUID guest) {
        Shown state = shown.get(guest);
        if (state != null) {
            state.again = true;
        }
    }

    /**
     * The server sent the guest some of his own effects: the guide's go out
     * again at the end of the tick.
     */
    void effectsAgain(UUID guest) {
        Shown state = shown.get(guest);
        if (state != null) {
            state.effectsAgain = true;
        }
    }

    /** Whether this guest is shown the guide's frost as his own right now. */
    boolean showsFrost(UUID guest) {
        Shown state = shown.get(guest);
        return state != null && state.frost >= 0;
    }

    /**
     * Puts back on his client what is his: his own health and hunger, his own
     * experience, his own hands and head, his own effects and frost - and
     * takes the guide's effects off the guide's body there.
     */
    void clear(Player guest) {
        Shown state = shown.remove(guest.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.vitalsShown) {
            guest.sendHealthUpdate();
        }
        if (state.experienceShown) {
            guest.sendExperienceChange(guest.getExp(), guest.getLevel());
        }
        if (state.mainHand != null || state.offHand != null || state.head != null) {
            guest.updateInventory();
        }
        for (PotionEffectType type : state.effects.keySet()) {
            showOwn(guest, type);
        }
        if (state.guide != null) {
            for (PotionEffectType type : state.onGuide.keySet()) {
                guest.sendPotionEffectChangeRemove(state.guide, type);
            }
        }
        if (state.frost >= 0) {
            ClientFrost.resend(guest);
        }
    }

    /** Whether this guest is shown anything of the guide right now. */
    boolean isShown(UUID guest) {
        return shown.containsKey(guest);
    }

    /** A guest who left the server: his client gets what is his when he joins again. */
    void forget(UUID guest) {
        shown.remove(guest);
    }

    /** Whether anything of the guide is shown to anybody at all - anything switched on. */
    boolean isOn() {
        return settings.isTourMirrorHealth() || settings.isTourMirrorMainHand() || settings.isTourMirrorOffHand()
                || settings.isTourMirrorHead() || settings.isTourMirrorEffects() || settings.isTourMirrorFrost()
                || settings.isTourMirrorFood() || settings.isTourMirrorExperience();
    }
}
