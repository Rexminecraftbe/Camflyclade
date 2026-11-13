package de.elia.cameraplugin.session;

import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.Map;

/**
 * What a player left behind when he started camera mode, and what camera mode
 * put in his place.
 */
public class CameraData {
    private final LivingEntity body;
    private final Mannequin hitbox;
    /** The name standing over the body, or {@code null} when it carries none. */
    private final TextDisplay nameDisplay;
    /**
     * The front of the hearts in front of that name, or {@code null}, see
     * {@link de.elia.cameraplugin.body.BodySpawner#spawnHeartsFront}.
     */
    private TextDisplay heartsFront;
    /** The chunk kept loaded for the body, see {@link de.elia.cameraplugin.body.BodySpawner#holdChunk}. */
    private final Chunk bodyChunk;
    private final GameMode originalGameMode;
    private final boolean originalAllowFlight;
    private final boolean originalFlying;
    private final boolean originalGlowing;
    /** Every slot of his inventory, the armour and the off hand included. */
    private final ItemStack[] originalInventoryContents;
    private final Collection<PotionEffect> pausedEffects;
    /** When camera mode started, in milliseconds as {@link System#currentTimeMillis()} counts them. */
    private final long startedAt = System.currentTimeMillis();
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
    /** How far the camera has flown so far, in blocks, see {@link SessionTracker}. */
    private double distanceFlown;
    /**
     * The farthest the camera has been from the body so far, in blocks, in
     * the world of the body.
     */
    private double farthestFromBody;
    /**
     * His statistics of moving when camera mode started, put back when it
     * ends, see {@link de.elia.cameraplugin.progress.CamProgressGuard}.
     */
    private Map<Statistic, Integer> movementAtStart = Map.of();
    /** His health when camera mode started, see {@link PausedEffects}. */
    private double healthAtStart;
    /** His absorption when camera mode started, see {@link PausedEffects}. */
    private double absorptionAtStart;
    /** How long he would still have burnt when camera mode started, in ticks. */
    private int fireTicksAtStart;
    private ItemStack[] heldForMode;
    private float flySpeedAtStart = 0.1f;
    /**
     * The team he stood in on the main scoreboard when camera mode started,
     * or {@code null}, see {@link de.elia.cameraplugin.scoreboard.NoCollisionTeam#teamOf}.
     */
    private String teamAtStart;
    /**
     * The colour he had on the locator bar when camera mode started, or
     * {@code null} when he was not on it, see
     * {@link de.elia.cameraplugin.locator.CamLocatorBar#pointOf}.
     */
    private Color locatorBarPoint;

    public CameraData(LivingEntity body, Mannequin hitbox, TextDisplay nameDisplay, Chunk bodyChunk, GameMode originalGameMode, boolean originalAllowFlight, boolean originalFlying, boolean originalGlowing, ItemStack[] originalInventoryContents, Collection<PotionEffect> pausedEffects) {
        this.body = body;
        this.hitbox = hitbox;
        this.nameDisplay = nameDisplay;
        this.bodyChunk = bodyChunk;
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
    /** The front of its hearts, or {@code null} when there is none. */
    public TextDisplay getHeartsFront() { return heartsFront; }
    public void setHeartsFront(TextDisplay heartsFront) { this.heartsFront = heartsFront; }
    /** The chunk kept loaded for the body while camera mode runs. */
    public Chunk getBodyChunk() { return bodyChunk; }
    public GameMode getOriginalGameMode() { return originalGameMode; }
    public boolean getOriginalAllowFlight() { return originalAllowFlight; }
    public boolean getOriginalFlying() { return originalFlying; }
    /** Whether the player was already glowing before camera mode. */
    public boolean getOriginalGlowing() { return originalGlowing; }
    public ItemStack[] getOriginalInventoryContents() { return originalInventoryContents; }
    public Collection<PotionEffect> getPausedEffects() { return pausedEffects; }
    /** When camera mode started, for {@code /cam list}. */
    public long getStartedAt() { return startedAt; }
    /** The portal the distance is measured from, or {@code null} for the body. */
    public Location getPortalAnchor() { return portalAnchor; }
    public void setPortalAnchor(Location portalAnchor) { this.portalAnchor = portalAnchor; }
    /** The portal he set out through, or {@code null} when he is still home. */
    public Location getPortalEntry() { return portalEntry; }
    public void setPortalEntry(Location portalEntry) { this.portalEntry = portalEntry; }
    /** How far the camera has flown so far, for {@code cam-log} and the statistics. */
    public double getDistanceFlown() { return distanceFlown; }
    public void addDistanceFlown(double blocks) { distanceFlown += blocks; }
    /** The farthest the camera has been from the body, for {@code cam-log} and the statistics. */
    public double getFarthestFromBody() { return farthestFromBody; }
    public void noteDistanceFromBody(double blocks) { farthestFromBody = Math.max(farthestFromBody, blocks); }
    /** His statistics of moving when camera mode started. */
    public Map<Statistic, Integer> getMovementAtStart() { return movementAtStart; }
    public void setMovementAtStart(Map<Statistic, Integer> movementAtStart) { this.movementAtStart = movementAtStart; }
    /** His health when camera mode started, given back with the paused effects. */
    public double getHealthAtStart() { return healthAtStart; }
    /** His absorption when camera mode started, given back with the paused effects. */
    public double getAbsorptionAtStart() { return absorptionAtStart; }
    public void setHeartsAtStart(double health, double absorption) {
        this.healthAtStart = health;
        this.absorptionAtStart = absorption;
    }
    /**
     * How long he would still have burnt when camera mode started, in ticks:
     * the fire is paused like his effects, and he burns on when it ends.
     */
    public int getFireTicksAtStart() { return fireTicksAtStart; }
    public void setFireTicksAtStart(int fireTicks) { this.fireTicksAtStart = Math.max(0, fireTicks); }

    /**
     * What a plugin keeping an inventory for each game mode put into his
     * hands for the game mode of the camera, set aside until he leaves it -
     * without such a plugin a copy of his own things. See
     * {@link CameraMode}{@code #switchGameMode}.
     */
    public ItemStack[] getHeldForMode() {
        return heldForMode;
    }

    public void setHeldForMode(ItemStack[] heldForMode) {
        this.heldForMode = heldForMode;
    }

    /** His own flight speed, before camera mode set one of its own. */
    public float getFlySpeedAtStart() {
        return flySpeedAtStart;
    }

    public void setFlySpeedAtStart(float flySpeed) {
        this.flySpeedAtStart = flySpeed;
    }

    /**
     * The team he stood in when camera mode started, for the crash
     * protection: camera mode puts him into a team of its own, and the world
     * keeps that over a crash.
     */
    public String getTeamAtStart() {
        return teamAtStart;
    }

    public void setTeamAtStart(String team) {
        this.teamAtStart = team;
    }

    /** The point his body takes over on the locator bar, or {@code null} for none. */
    public Color getLocatorBarPoint() {
        return locatorBarPoint;
    }

    public void setLocatorBarPoint(Color point) {
        this.locatorBarPoint = point;
    }
}
