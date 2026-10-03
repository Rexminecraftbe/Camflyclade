package de.elia.cameraplugin.body;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.display.NameDisplay;
import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.logging.Level;

/**
 * Builds the body a player leaves behind in camera mode, the invisible
 * mannequin that takes the hits for a body that is not a mannequin itself, and
 * the name standing over the body.
 */
public final class BodySpawner {

    /** Armour slots in the order of {@link org.bukkit.inventory.PlayerInventory#getArmorContents()}. */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;
    private final NamespacedKey bodyKey;
    private final NamespacedKey hitboxKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey hiddenArmorAsset;
    /** Whether the missing way to hide the "NPC" line has already been reported. */
    private boolean mannequinLabelReported = false;

    public BodySpawner(JavaPlugin plugin, CamSettings settings, Messages messages, ConsoleLog log) {
        this.settings = settings;
        this.messages = messages;
        this.log = log;
        bodyKey = new NamespacedKey(plugin, "cam_body");
        hitboxKey = new NamespacedKey(plugin, "cam_hitbox");
        nameKey = new NamespacedKey(plugin, "cam_name");
        // Deliberately not a real equipment asset: the client finds nothing for
        // it and therefore draws nothing.
        hiddenArmorAsset = new NamespacedKey(plugin, "hidden_armor");
    }

    /**
     * Copies the player's armour for the visible body. Copies are all it takes:
     * the pieces are only worn there, the damage is calculated on the player
     * himself and his own armour is what wears out.
     *
     * <p>{@link de.elia.cameraplugin.mirrordamage.DamageMirror} takes the same
     * copies to wear out in place of the originals.</p>
     */
    public ItemStack[] createMirrorArmor(ItemStack[] originalArmor) {
        ItemStack[] mirrorArmor = new ItemStack[originalArmor.length];
        for (int i = 0; i < originalArmor.length; i++) {
            if (originalArmor[i] != null) {
                mirrorArmor[i] = originalArmor[i].clone();
            }
        }
        return mirrorArmor;
    }

    /**
     * Copies the armour the armour stand of body type 1 shows. Everything but
     * the helmet: the player's head sits in that slot and is what the body is
     * recognised by. The helmet itself is worn by the mannequin standing in the
     * body, hidden, so a mob head still shortens the range of its kind of mob.
     */
    private ItemStack[] createArmorStandArmor(ItemStack[] originalArmor) {
        ItemStack[] worn = createMirrorArmor(originalArmor);
        for (int i = 0; i < worn.length && i < ARMOR_SLOTS.length; i++) {
            if (ARMOR_SLOTS[i] == EquipmentSlot.HEAD) {
                worn[i] = null;
            }
        }
        return worn;
    }

    /**
     * Copies the player's armour for a mannequin that is not to show it and
     * takes away its rendering: an invisible mannequin, whose pieces would
     * otherwise float in the air by themselves - in front of the armour stand
     * or where the invisible body stands -, and the visible one when
     * {@code body.armor-visible} is off.
     *
     * <p>Copies are enough here: the body never really takes the damage, its
     * damage event is cancelled. The reduction and the durability are both
     * taken from the player's own armour when the hit is passed on to him, and
     * the pieces on the mannequin change nothing about either.</p>
     */
    public ItemStack[] createHiddenArmor(ItemStack[] originalArmor) {
        ItemStack[] hiddenArmor = new ItemStack[originalArmor.length];
        boolean stillVisible = false;
        for (int i = 0; i < originalArmor.length && i < ARMOR_SLOTS.length; i++) {
            if (originalArmor[i] == null) {
                continue;
            }
            ItemStack copy = originalArmor[i].clone();
            if (!EquipmentVisibility.hide(copy, ARMOR_SLOTS[i], hiddenArmorAsset)) {
                stillVisible = true;
            }
            hiddenArmor[i] = copy;
        }
        if (stillVisible) {
            log.log(Level.WARNING, "Die Rüstung des Mannequins konnte nicht ausgeblendet werden, "
                    + "sie bleibt am Körper sichtbar. Setter: " + EquipmentVisibility.describeAssetSetter());
        }
        return hiddenArmor;
    }

    /**
     * Creates the invisible mannequin that takes the hits for a body that is
     * not a mannequin itself, the armour stand of a visible type 1. A
     * mannequin has the same hitbox as a player, so hits land on the body the
     * way they would land on the player himself.
     *
     * <p>It is invisible and its armour is not rendered either, but it is a
     * normal entity otherwise: players, mobs and the world hit it directly, just
     * like the visible mannequin of body type 2.</p>
     */
    public Mannequin spawnHitbox(Player player, Location location, ItemStack[] mirrorArmor) {
        Mannequin hitbox = (Mannequin) location.getWorld().spawnEntity(location, EntityType.MANNEQUIN);
        EntityEquipment equipment = hitbox.getEquipment();
        if (equipment != null) {
            equipment.setArmorContents(mirrorArmor);
        }
        hitbox.getPersistentDataContainer().set(hitboxKey, PersistentDataType.INTEGER, 1);
        hideMannequin(hitbox);
        hitbox.setSilent(true);
        // It stands inside the armour stand and therefore has to behave the same
        // way: whatever moves the one moves the other. Only then does the
        // movement check on the mannequin notice that the body has fallen.
        hitbox.setGravity(useBodyGravity());
        applyMovementSensitivity(hitbox);
        hitbox.setInvulnerable(false);
        hitbox.setCustomName(messages.getMessage("hitbox.name-format").replace("{player}", player.getName()));
        hitbox.setCustomNameVisible(false);
        hideMannequinDescription(hitbox);
        hitbox.setCanPickupItems(false);
        return hitbox;
    }

    /**
     * Spawns the body that stays behind while the player is in camera mode:
     * either a mannequin or an armour stand wearing the player's head, see
     * {@link #usesMannequinBody()}. An invisible body gets neither the skin
     * nor the head. The name above it is an entity of its own, see
     * {@link #spawnNameDisplay}.
     *
     * <p>The armour stand puts the armour on right here, the mannequin gets it
     * from the caller: it is the entity taking the hits as well and therefore
     * wears the pieces even when they are not meant to be seen.</p>
     */
    public LivingEntity spawnCameraBody(Player player, Location location, int remainingAir,
                                        ItemStack[] originalArmor) {
        LivingEntity body = usesMannequinBody()
                ? spawnMannequinBody(player, location)
                : spawnArmorStandBody(player, location, originalArmor);

        body.setRemainingAir(remainingAir);
        body.getPersistentDataContainer().set(bodyKey, PersistentDataType.INTEGER, 1);
        body.setGravity(useBodyGravity());
        body.setCanPickupItems(false);
        body.setInvulnerable(false);
        AttributeInstance maxHealth = body.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(20.0);
            body.setHealth(20.0);
        }
        return body;
    }

    /**
     * Creates a mannequin as the body itself: one that shows the player's own
     * skin, or one that is not drawn at all for an invisible body - it needs
     * no skin then, and like the hitbox it makes no sound that would give its
     * spot away.
     */
    private Mannequin spawnMannequinBody(Player player, Location location) {
        Mannequin mannequin = (Mannequin) location.getWorld().spawnEntity(location, EntityType.MANNEQUIN);
        if (!settings.isBodyVisible()) {
            hideMannequin(mannequin);
            mannequin.setSilent(true);
        } else if (!MannequinSkin.apply(mannequin, player)) {
            log.log(Level.WARNING, "Der Skin von " + player.getName()
                    + " konnte nicht auf das Mannequin übertragen werden, es benutzt den Standard-Skin.");
        }
        applyMovementSensitivity(mannequin);
        hideMannequinDescription(mannequin);
        return mannequin;
    }

    /**
     * Takes the grey "NPC" line off a mannequin, the line the client draws
     * under its name tag. A body shows no name tag of its own - its name is a
     * text display, see {@link #spawnNameDisplay} -, but the hitbox carries a
     * name, and wherever that is shown the line would come along.
     *
     * <p>Reported once when this server's API offers no way to do it, so that
     * such a line does not turn up without a word about why.</p>
     */
    private void hideMannequinDescription(Mannequin mannequin) {
        if (MannequinLabel.hideDescription(mannequin) || mannequinLabelReported) {
            return;
        }
        mannequinLabelReported = true;
        log.log(Level.WARNING, "Die Zeile \"NPC\" unter dem Namen des Mannequins konnte nicht abgeschaltet werden."
                + " Setter: " + MannequinLabel.describeSetter());
    }

    /**
     * Whether the body itself is the mannequin. Every body is, except the
     * visible one of type 1, the armour stand wearing the player's head.
     *
     * <p>An invisible body has no head to show, so both types end up the same
     * there: an invisible mannequin, with the name standing over it on its
     * own. Either way a mannequin is the entity that is hit, so it is hit
     * where the player himself would be hit.</p>
     */
    public boolean usesMannequinBody() {
        return settings.getBodyType().isMannequinBody(settings.isBodyVisible());
    }

    /**
     * Whether the armour of the player is drawn on his body,
     * {@code body.armor-visible}.
     *
     * <p>An invisible body never shows it, whatever the setting says: armour
     * does not turn invisible along with what wears it, the pieces would hang
     * in the air on their own - the same reason an invisible body wears no
     * head.</p>
     *
     * <p>Purely a matter of looks: the hit is calculated on the player himself
     * with his own armour, and his own armour is what wears out, see
     * {@link de.elia.cameraplugin.mirrordamage.DamageMirror#onBodyDamage}.</p>
     */
    public boolean showsBodyArmor() {
        return settings.isBodyArmorVisible() && settings.isBodyVisible();
    }

    /**
     * Puts the configured name over the body: a text display of its own, not
     * the name tag of the body, see {@link NameDisplay}. It is also what an
     * invisible body keeps as a name without a second entity to carry it: an
     * invisible mannequin shows no name tag.
     *
     * @return the name, or {@code null} when the body carries none
     */
    public TextDisplay spawnNameDisplay(Player player, LivingEntity body) {
        if (!settings.isBodyNameVisible()) {
            return null;
        }
        String format = messages.getMessage("armorstand.name-format").replace("{player}", player.getName());
        return NameDisplay.spawn(nameLocation(body), format, settings.getBodyNameStyle(), nameKey);
    }

    /**
     * Where the name of this body stands: over its head, where the game puts a
     * name tag. The text grows upwards from there, so a larger name or a second
     * line never covers the head.
     */
    public Location nameLocation(LivingEntity body) {
        return body.getLocation().add(0.0, body.getHeight() + NameDisplay.ABOVE_HEAD, 0.0);
    }

    /**
     * Takes a mannequin out of sight. The armour it wears is hidden separately
     * through {@link #createHiddenArmor(ItemStack[])}, otherwise the pieces
     * would stay where the body is.
     */
    private void hideMannequin(Mannequin mannequin) {
        mannequin.setInvisible(true);
        mannequin.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
    }

    /**
     * Puts the configured {@code body.movement-sensitivity} onto a mannequin.
     *
     * <p>On level 0 it is nailed to its spot, on the levels above it is moved by
     * gravity, water and pistons, because that movement is what ends camera
     * mode. Only level 2 also lets players and mobs push it.</p>
     *
     * <p>The same call fits the hitbox and the body, because the mannequin is
     * the entity that gets pushed in either case. Behind the armour stand of
     * body type 1 level 2 has already fallen back to level 1, so the hitbox
     * standing in that body never becomes collidable.</p>
     */
    private void applyMovementSensitivity(Mannequin mannequin) {
        mannequin.setImmovable(settings.getMovementSensitivity().isFixed());
        mannequin.setCollidable(settings.getMovementSensitivity().allowsEntityPush());
    }

    /**
     * Gravity for the body entities, decided by
     * {@code body.movement-sensitivity} alone: level 0 nails the body to its
     * spot, every level above it lets the body fall.
     */
    private boolean useBodyGravity() {
        return !settings.getMovementSensitivity().isFixed();
    }

    /**
     * Creates the body of a visible type 1: an armour stand wearing the
     * player's head, and his armour with it when {@link #showsBodyArmor()}
     * says so. An invisible body never gets here, it is a mannequin, see
     * {@link #usesMannequinBody()}.
     */
    private ArmorStand spawnArmorStandBody(Player player, Location location, ItemStack[] originalArmor) {
        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        // Never a marker: a marker neither falls nor is pushed by a piston, and
        // the body everyone sees has to go wherever the mannequin standing in
        // it goes. Level 0 is held in place by startBodyPin instead.
        armorStand.setMarker(false);
        armorStand.addEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.CHEST, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.LEGS, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.FEET, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.OFF_HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);

        EntityEquipment equipment = armorStand.getEquipment();
        if (showsBodyArmor()) {
            // Before the head goes on, not after: this call writes the whole
            // set of four slots and would take the head off again.
            equipment.setArmorContents(createArmorStandArmor(originalArmor));
        }
        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            playerHead.setItemMeta(skullMeta);
        }
        equipment.setHelmet(playerHead);
        return armorStand;
    }

    /**
     * Removes every body, hitbox and name the plugin left standing, found by
     * the mark each of them carries.
     */
    public void removeLeftoverEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                PersistentDataContainer marks = entity.getPersistentDataContainer();
                if (marks.has(bodyKey, PersistentDataType.INTEGER)
                        || marks.has(hitboxKey, PersistentDataType.INTEGER)
                        || marks.has(nameKey, PersistentDataType.INTEGER)) {
                    entity.remove();
                }
            }
        }
    }
}
