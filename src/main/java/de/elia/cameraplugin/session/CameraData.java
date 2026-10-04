package de.elia.cameraplugin.session;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;

/**
 * What a player left behind when he started camera mode, and what camera mode
 * put in his place.
 */
public class CameraData {
    private final LivingEntity body;
    private final Mannequin hitbox;
    /** The name standing over the body, or {@code null} when it carries none. */
    private final TextDisplay nameDisplay;
    private final GameMode originalGameMode;
    private final boolean originalAllowFlight;
    private final boolean originalFlying;
    private final boolean originalGlowing;
    /** Every slot of his inventory, the armour and the off hand included. */
    private final ItemStack[] originalInventoryContents;
    private final Collection<PotionEffect> pausedEffects;
    /**
     * Where the distance to the body is measured from while the player is
     * in a world his body is not in: the portal he came out of there.
     * {@code null} while he is in the world of his body, where the body
     * itself is measured from.
     */
    private Location portalAnchor;
    /**
     * Where he stepped into the first portal of his trip, the spot
     * {@code portals.return-to: portal} brings him back to. {@code null}
     * while he has not left the world of his body.
     */
    private Location portalEntry;

    public CameraData(LivingEntity body, Mannequin hitbox, TextDisplay nameDisplay, GameMode originalGameMode, boolean originalAllowFlight, boolean originalFlying, boolean originalGlowing, ItemStack[] originalInventoryContents, Collection<PotionEffect> pausedEffects) {
        this.body = body;
        this.hitbox = hitbox;
        this.nameDisplay = nameDisplay;
        this.originalGameMode = originalGameMode;
        this.originalAllowFlight = originalAllowFlight;
        this.originalFlying = originalFlying;
        this.originalGlowing = originalGlowing;
        this.originalInventoryContents = originalInventoryContents;
        this.pausedEffects = pausedEffects;
    }

    public LivingEntity getBody() { return body; }
    /** The separate hitbox, or {@code null} when the body is hit directly. */
    public Mannequin getHitbox() { return hitbox; }
    /** The entity that takes the hits: the separate hitbox, or the body itself. */
    public LivingEntity getDamageTarget() { return hitbox != null ? hitbox : body; }
    /** The name over the body, or {@code null} when it carries none. */
    public TextDisplay getNameDisplay() { return nameDisplay; }
    public GameMode getOriginalGameMode() { return originalGameMode; }
    public boolean getOriginalAllowFlight() { return originalAllowFlight; }
    public boolean getOriginalFlying() { return originalFlying; }
    /** Whether the player was already glowing before camera mode. */
    public boolean getOriginalGlowing() { return originalGlowing; }
    public ItemStack[] getOriginalInventoryContents() { return originalInventoryContents; }
    public Collection<PotionEffect> getPausedEffects() { return pausedEffects; }
    /** The portal the distance is measured from, or {@code null} for the body. */
    public Location getPortalAnchor() { return portalAnchor; }
    public void setPortalAnchor(Location portalAnchor) { this.portalAnchor = portalAnchor; }
    /** The portal he set out through, or {@code null} when he is still home. */
    public Location getPortalEntry() { return portalEntry; }
    public void setPortalEntry(Location portalEntry) { this.portalEntry = portalEntry; }
}
