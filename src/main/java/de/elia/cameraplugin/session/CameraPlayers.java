package de.elia.cameraplugin.session;

import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is in camera mode right now, and which entities stand in for them: the
 * body, and the invisible hitbox a body that is no mannequin itself has.
 */
public final class CameraPlayers {

    private final Map<UUID, CameraData> cameraPlayers = new HashMap<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private final Map<UUID, UUID> hitboxEntities = new HashMap<>();

    public boolean contains(UUID playerId) {
        return cameraPlayers.containsKey(playerId);
    }

    /** What the player left behind, or {@code null} when he is not in camera mode. */
    public CameraData get(UUID playerId) {
        return cameraPlayers.get(playerId);
    }

    public boolean isEmpty() {
        return cameraPlayers.isEmpty();
    }

    /** The ids of everybody in camera mode. A live view, not a copy. */
    public Set<UUID> ids() {
        return cameraPlayers.keySet();
    }

    public void put(UUID playerId, CameraData data) {
        cameraPlayers.put(playerId, data);
    }

    public void remove(UUID playerId) {
        cameraPlayers.remove(playerId);
    }

    public void addBody(UUID bodyId, UUID ownerId) {
        bodyOwners.put(bodyId, ownerId);
    }

    public void removeBody(UUID bodyId) {
        bodyOwners.remove(bodyId);
    }

    public void addHitbox(UUID hitboxId, UUID ownerId) {
        hitboxEntities.put(hitboxId, ownerId);
    }

    public void removeHitbox(UUID hitboxId) {
        hitboxEntities.remove(hitboxId);
    }

    /** {@code true} when the entity is the camera body of a player. */
    public boolean isCameraBody(Entity entity) {
        return entity != null && bodyOwners.containsKey(entity.getUniqueId());
    }

    /**
     * Returns the camera player that owns the given body or hitbox entity,
     * or {@code null} when the entity is not managed by us.
     */
    public UUID getBodyOrHitboxOwner(Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID owner = bodyOwners.get(entity.getUniqueId());
        return owner != null ? owner : hitboxEntities.get(entity.getUniqueId());
    }
}
