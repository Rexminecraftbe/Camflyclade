package de.elia.cameraplugin.body;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.display.NameDisplay;
import de.elia.cameraplugin.display.SpriteText;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Builds the body a player leaves behind in camera mode, the invisible
 * mannequin that takes the hits for a body that is not a mannequin itself, and
 * the name standing over the body, and clears them away again - a body a
 * chunk brings back after camera mode is over included.
 */
public final class BodySpawner implements Listener {

    /** How many hearts a line over the body holds, like a row of the bar of hearts. */
    private static final int HEARTS_PER_LINE = 10;

    /** The health of a player whose attribute cannot be read: the game's own. */
    private static final double DEFAULT_MAX_HEALTH = 20.0;

    /** Armour slots in the order of {@link org.bukkit.inventory.PlayerInventory#getArmorContents()}. */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;
    private final CameraPlayers cameraPlayers;
    private final NamespacedKey bodyKey;
    private final NamespacedKey hitboxKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey hiddenArmorAsset;
    /** Whether the missing way to hide the "NPC" line has already been reported. */
    private boolean mannequinLabelReported = false;

    public BodySpawner(JavaPlugin plugin, CamSettings settings, Messages messages, ConsoleLog log,
                       CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.log = log;
        this.cameraPlayers = cameraPlayers;
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
     * Copies the armour the armour stand of the armorstand body shows.
     * Everything but the helmet: the player's head sits in that slot and is
     * what the body is recognised by. The helmet itself is worn by the
     * mannequin standing in the body, hidden, so a mob head still shortens the
     * range of its kind of mob.
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
            log.log(Level.WARNING, "The armour of the mannequin could not be hidden,"
                    + " it stays visible on the body. Setter: " + EquipmentVisibility.describeAssetSetter());
        }
        return hiddenArmor;
    }

    /**
     * Creates the invisible mannequin that takes the hits for a body that is
     * not a mannequin itself, the armour stand of a visible armorstand body. A
     * mannequin has the same hitbox as a player, so hits land on the body the
     * way they would land on the player himself.
     *
     * <p>It is invisible and its armour is not rendered either, but it is a
     * normal entity otherwise: players, mobs and the world hit it directly, just
     * like the visible mannequin body.</p>
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
     * wears the pieces even when they are not meant to be seen. The same goes
     * for the air and the frost of the player, which the caller hands to
     * whichever mannequin takes the hits.</p>
     */
    public LivingEntity spawnCameraBody(Player player, Location location, ItemStack[] originalArmor) {
        LivingEntity body = usesMannequinBody()
                ? spawnMannequinBody(player, location)
                : spawnArmorStandBody(player, location, originalArmor);

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
            log.log(Level.WARNING, "The skin of " + player.getName()
                    + " could not be put on the mannequin, it uses the default skin.");
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
        log.log(Level.WARNING, "The \"NPC\" line under the name of the mannequin could not be switched off."
                + " Setter: " + MannequinLabel.describeSetter());
    }

    /**
     * Whether the body itself is the mannequin. Every body is, except the
     * visible armorstand body, the armour stand wearing the player's head.
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
     * <p>With {@code body.health-visible} the hearts of the player stand there
     * as well, over or under the name as {@code health-position} says - on
     * their own, when {@code name-visible} is off. See
     * {@link #writeHearts}.</p>
     *
     * @param absorption the yellow hearts he had when camera mode started, in
     *                   health; camera mode pauses the effect behind them
     * @return the name, or {@code null} when the body carries none
     */
    public TextDisplay spawnNameDisplay(Player player, LivingEntity body, double absorption) {
        if (!settings.isBodyNameVisible() && !settings.isBodyHealthVisible()) {
            return null;
        }
        return NameDisplay.spawnStyled(nameLocation(body), nameText(player, absorption), settings.getBodyNameStyle(),
                nameKey, display -> writeHearts(display, player, absorption, layered() ? Layer.BACK : Layer.ONLY));
    }

    /**
     * Puts up the front of the hearts, see {@link Layer}, a hair in front of
     * the name of {@link #spawnNameDisplay}: only while
     * {@code health-show-missing} wants the hearts the way the bar draws
     * them, and only where the server can show pictures at all.
     *
     * @return the front, or {@code null} when there is none
     */
    public TextDisplay spawnHeartsFront(Player player, LivingEntity body, double absorption) {
        if (!layered()) {
            return null;
        }
        return NameDisplay.spawnStyled(nameLocation(body), nameText(player, absorption), settings.getBodyNameStyle(),
                nameKey, display -> {
                    NameDisplay.putInFront(display, settings.getBodyNameStyle());
                    writeHearts(display, player, absorption, Layer.FRONT);
                });
    }

    /**
     * Writes the name over a body anew, when the hearts of the player have
     * changed.
     *
     * @param front the front of the hearts, or {@code null}
     */
    public void refreshName(Player player, TextDisplay name, TextDisplay front, double absorption) {
        if (front != null) {
            writeHearts(front, player, absorption, Layer.FRONT);
        }
        if (!writeHearts(name, player, absorption, front != null ? Layer.BACK : Layer.ONLY)) {
            name.setText(nameText(player, absorption));
        }
    }

    /**
     * Whether the hearts are drawn in two layers: with
     * {@code health-show-missing} every heart stands on an empty one, the way
     * the bar draws them, so that a half heart is red on one side and black on
     * the other. Pictures cannot be laid over each other within one text, so
     * the empty ones stand in one text display and the red ones in a second
     * one in front of it. Only with pictures, see {@link SpriteText}.
     */
    private boolean layered() {
        return settings.isBodyHealthVisible() && settings.isBodyHealthShowMissing() && SpriteText.available();
    }

    /** Which of the texts a display holds, see {@link #layered()}. */
    private enum Layer {
        /** The only one: name and hearts. */
        ONLY,
        /** Behind: the name, the background and an empty heart in every place. */
        BACK,
        /** In front: the name once more and the hearts themselves. */
        FRONT
    }

    /**
     * One heart of the bar: a whole one, the half one at the end, one he is
     * missing, and the yellow ones of absorption after them.
     */
    private enum Heart { FULL, HALF, EMPTY, ABSORBING_FULL, ABSORBING_HALF }

    /**
     * The hearts of a player the way his bar shows them: every whole one, the
     * half one at the end, with {@code body.health-show-missing} those he is
     * missing up to his maximum, and with {@code health-show-absorption} the
     * yellow ones after them. Ten to a line like the bar; with
     * {@code health-rows-upward} every further line stands above the one
     * before, as on the bar, otherwise below. {@code null} stands for the
     * start of a new line.
     */
    private List<Heart> hearts(Player player, double absorption) {
        int halves = (int) Math.ceil(player.getHealth());
        int count = (halves + 1) / 2;
        if (settings.isBodyHealthShowMissing()) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            count = Math.max(count, (int) Math.ceil((max != null ? max.getValue() : DEFAULT_MAX_HEALTH) / 2.0));
        }
        List<Heart> all = new ArrayList<>();
        for (int heart = 0; heart < count; heart++) {
            int upTo = 2 * heart + 2;
            all.add(upTo <= halves ? Heart.FULL : upTo - 1 <= halves ? Heart.HALF : Heart.EMPTY);
        }
        int yellow = settings.isBodyHealthShowAbsorption() ? (int) Math.ceil(absorption) : 0;
        for (int heart = 0; 2 * heart < yellow; heart++) {
            all.add(2 * heart + 2 <= yellow ? Heart.ABSORBING_FULL : Heart.ABSORBING_HALF);
        }
        List<List<Heart>> lines = new ArrayList<>();
        for (int from = 0; from < all.size(); from += HEARTS_PER_LINE) {
            lines.add(all.subList(from, Math.min(all.size(), from + HEARTS_PER_LINE)));
        }
        if (settings.isBodyHealthRowsUpward()) {
            Collections.reverse(lines);
        }
        List<Heart> hearts = new ArrayList<>();
        for (List<Heart> line : lines) {
            if (!hearts.isEmpty()) {
                hearts.add(null);
            }
            hearts.addAll(line);
        }
        return hearts;
    }

    /**
     * The name over the body in letters: {@code name-format} with his name in
     * the colour of {@code body.name}, and with {@code body.health-visible} his
     * hearts in a line of their own over or under it, written with the
     * letters of {@code armorstand.health-full}, {@code -half},
     * {@code -empty}, {@code -absorbing-full} and {@code -absorbing-half}.
     * That is all Spigot can show; Paper shows the pictures of the bar
     * instead, see {@link #writeHearts}.
     */
    private String nameText(Player player, double absorption) {
        String name = styledName(player);
        if (!settings.isBodyHealthVisible()) {
            return name;
        }
        StringBuilder hearts = new StringBuilder();
        for (Heart heart : hearts(player, absorption)) {
            hearts.append(heart == null ? "\n" : messages.getMessage(switch (heart) {
                case FULL -> "armorstand.health-full";
                case HALF -> "armorstand.health-half";
                case EMPTY -> "armorstand.health-empty";
                case ABSORBING_FULL -> "armorstand.health-absorbing-full";
                case ABSORBING_HALF -> "armorstand.health-absorbing-half";
            }));
        }
        if (name.isEmpty()) {
            return hearts.toString();
        }
        return settings.isBodyHealthAbove() ? hearts + "\n" + name : name + "\n" + hearts;
    }

    /**
     * {@code name-format} with his name in the colour of {@code body.name},
     * or nothing with {@code name-visible: false}.
     */
    private String styledName(Player player) {
        String format = settings.isBodyNameVisible()
                ? messages.getMessage("armorstand.name-format").replace("{player}", player.getName())
                : "";
        return format.isEmpty() ? "" : NameDisplay.styled(format, settings.getBodyNameStyle());
    }

    /**
     * Writes the hearts of the player as the pictures of his bar, out of
     * {@code hud/heart/}, where the server can show pictures in a text
     * display, see {@link SpriteText}. Every layer holds the name and a
     * picture in every place, so that the places of both layers line up.
     *
     * @return whether it did; if not, the letters of {@link #nameText} stand
     *         there
     */
    private boolean writeHearts(TextDisplay display, Player player, double absorption, Layer layer) {
        if (!settings.isBodyHealthVisible() || !SpriteText.available()) {
            return false;
        }
        String name = styledName(player);
        SpriteText text = new SpriteText();
        if (!name.isEmpty() && !settings.isBodyHealthAbove()) {
            text.text(name).line();
        }
        for (Heart heart : hearts(player, absorption)) {
            if (heart == null) {
                text.line();
                continue;
            }
            String sprite = layer == Layer.BACK ? "container" : switch (heart) {
                case FULL -> "full";
                case HALF -> "half";
                case EMPTY -> "container";
                case ABSORBING_FULL -> "absorbing_full";
                case ABSORBING_HALF -> "absorbing_half";
            };
            text.sprite(SpriteText.GUI_ATLAS, "minecraft:hud/heart/" + sprite);
        }
        if (!name.isEmpty() && settings.isBodyHealthAbove()) {
            text.line().text(name);
        }
        return text.writeTo(display);
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
     * <p>That they do not push it below level 2 is the business of a team,
     * see {@link de.elia.cameraplugin.scoreboard.NoCollisionTeam}. The
     * mannequin itself stays collidable on every level: switched off, it
     * would let every arrow, trident and wind charge and the stab of a spear
     * pass through it, since the server asks the same switch whether they can
     * hit it.</p>
     *
     * <p>The same call fits the hitbox and the body, because the mannequin is
     * the entity that gets pushed in either case. Behind the armour stand of
     * the armorstand body level 2 has already fallen back to level 1, so
     * nobody ever pushes the hitbox standing in that body.</p>
     */
    private void applyMovementSensitivity(Mannequin mannequin) {
        mannequin.setImmovable(settings.getMovementSensitivity().isFixed());
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
     * Creates a visible armorstand body: an armour stand wearing the
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
     * Keeps the chunk the body stands in loaded for as long as camera mode
     * runs, the way the player himself would keep it loaded standing there.
     *
     * <p>Without it the chunk goes as soon as no player is near any more - the
     * camera flew off far enough, or into another world. The body, its hitbox
     * and its name go with it, and what the chunk brings back later are new
     * entities under the same ids: everything camera mode holds on to would
     * from then on point at entities that no longer exist, and the end of
     * camera mode would remove those and leave the body standing.</p>
     *
     * @return the chunk now held, for {@link #releaseChunk(Chunk)}
     */
    public Chunk holdChunk(LivingEntity body) {
        Chunk chunk = body.getLocation().getChunk();
        chunk.addPluginChunkTicket(plugin);
        return chunk;
    }

    /**
     * Lets go of the chunk {@link #holdChunk(LivingEntity)} held, unless the
     * body of another camera player stands in it too: the server keeps a
     * single ticket per plugin and chunk, so that one is his as well. Called
     * once the player is out of {@link CameraPlayers}.
     */
    public void releaseChunk(Chunk chunk) {
        for (UUID otherId : cameraPlayers.ids()) {
            Chunk other = cameraPlayers.get(otherId).getBodyChunk();
            if (other.getX() == chunk.getX() && other.getZ() == chunk.getZ()
                    && other.getWorld().equals(chunk.getWorld())) {
                return;
            }
        }
        chunk.removePluginChunkTicket(plugin);
    }

    /**
     * Takes the armour off a body, hitbox or name of camera mode, so that
     * none of it can drop, and removes it.
     *
     * <p>Looked up by its id first: should its chunk have been unloaded
     * after all, the handle kept since the start belongs to an entity that is
     * gone, and the one standing there now is a new one. One that is not
     * loaded at all is left to {@link #onEntitiesLoad}.</p>
     */
    public void remove(Entity entity) {
        Entity loaded = Bukkit.getEntity(entity.getUniqueId());
        if (loaded == null) {
            return;
        }
        if (loaded instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                equipment.setArmorContents(new ItemStack[4]);
            }
        }
        loaded.remove();
    }

    /**
     * Removes every body, hitbox and name the plugin left standing, found by
     * the mark each of them carries.
     *
     * <p>Only what is loaded right now. A body or hitbox in a chunk that is
     * not, one a crash left there, is removed by {@link #onEntitiesLoad} when
     * the chunk comes back.</p>
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

    /**
     * Removes a body or hitbox a chunk brings back when it belongs to nobody
     * in camera mode any more: one camera mode ended without, while its chunk
     * was not loaded, or one a crash left behind.
     *
     * <p>The names are left alone: they are not saved with their chunk, see
     * {@link NameDisplay}, so none comes back from one, and a chunk that only
     * wakes up again may hand over one that is still in use.</p>
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            PersistentDataContainer marks = entity.getPersistentDataContainer();
            if ((marks.has(bodyKey, PersistentDataType.INTEGER) || marks.has(hitboxKey, PersistentDataType.INTEGER))
                    && cameraPlayers.getBodyOrHitboxOwner(entity) == null) {
                entity.remove();
            }
        }
    }
}
