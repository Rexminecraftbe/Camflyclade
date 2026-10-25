package de.elia.cameraplugin.spot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A spot one player shares with others, {@code /cam spot share}: with single
 * players, with everybody, or both. It is a copy of his spot, kept up to date
 * by {@link SharedSpots} while he moves it.
 */
public final class Share {

    private final UUID owner;
    /** His name, as it was when he last shared or moved the spot. */
    private String ownerName;
    private Spot spot;
    /** Whether it is shared with everybody, {@code /cam spot share <spot> all}. */
    private boolean everyone;
    /** The players it is shared with by name, with the names they had then. */
    private final Map<UUID, String> players = new LinkedHashMap<>();

    Share(UUID owner, String ownerName, Spot spot) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.spot = spot;
    }

    public UUID getOwner() { return owner; }
    public String getOwnerName() { return ownerName; }
    public Spot getSpot() { return spot; }
    public boolean isEveryone() { return everyone; }
    /** The players it is shared with by name, with their names; not to be changed. */
    public Map<UUID, String> getPlayers() { return Collections.unmodifiableMap(players); }

    /** Whether this player may use it: anybody but its owner it is shared with. */
    public boolean isFor(UUID viewer) {
        return !viewer.equals(owner) && (everyone || players.containsKey(viewer));
    }

    /** How it is named in a message and in a command: {@code Owner:spot}. */
    public String label() {
        return ownerName + ":" + spot.name();
    }

    /** Whether it is shared with nobody any more, which is the end of it. */
    boolean isEmpty() {
        return !everyone && players.isEmpty();
    }

    void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    void setSpot(Spot spot) { this.spot = spot; }
    void setEveryone(boolean everyone) { this.everyone = everyone; }
    void addPlayer(UUID player, String name) { players.put(player, name); }
    boolean removePlayer(UUID player) { return players.remove(player) != null; }
}
