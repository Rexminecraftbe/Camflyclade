package de.elia.cameraplugin.spot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A spot one player shares with others, {@code /cam spot share}: public, for
 * everybody, and with single players, each of whom has to accept it first.
 * It is a copy of his spot, kept up to date by {@link SharedSpots} while he
 * moves it.
 */
public final class Share {

    private final UUID owner;
    /** His name, as it was when he last shared or moved the spot. */
    private String ownerName;
    private Spot spot;
    /** Whether it is public, for everybody: {@code /cam spot share <spot> all}. */
    private boolean everyone;
    /** The players who accepted it, with the names they had then. */
    private final Map<UUID, String> players = new LinkedHashMap<>();
    /** The players it was offered to who have not accepted it yet, with their names. */
    private final Map<UUID, String> pending = new LinkedHashMap<>();
    /** The players who like it, for the public spots. */
    private final Set<UUID> likes = new LinkedHashSet<>();

    Share(UUID owner, String ownerName, Spot spot) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.spot = spot;
    }

    public UUID getOwner() { return owner; }
    public String getOwnerName() { return ownerName; }
    public Spot getSpot() { return spot; }
    /** Whether it is public, for everybody. */
    public boolean isEveryone() { return everyone; }
    /** The players who accepted it, with their names; not to be changed. */
    public Map<UUID, String> getPlayers() { return Collections.unmodifiableMap(players); }
    /** The players it was offered to and who have not accepted it yet; not to be changed. */
    public Map<UUID, String> getPending() { return Collections.unmodifiableMap(pending); }
    /** How many players like it. */
    public int likes() { return likes.size(); }
    /** Whether this player likes it. */
    public boolean isLikedBy(UUID player) { return likes.contains(player); }

    /** Whether this player may use it: anybody but its owner, while it is public or he accepted it. */
    public boolean isFor(UUID viewer) {
        return !viewer.equals(owner) && (everyone || players.containsKey(viewer));
    }

    /** Whether it waits for this player to accept it. */
    public boolean isOfferedTo(UUID viewer) {
        return pending.containsKey(viewer);
    }

    /** Whether this player accepted it himself, not only finding it among the public ones. */
    public boolean isAcceptedBy(UUID viewer) {
        return players.containsKey(viewer);
    }

    /** How it is named in a message and in a command: {@code Owner:spot}. */
    public String label() {
        return ownerName + ":" + spot.name();
    }

    /**
     * Whether nothing is left of it: neither public nor offered to anybody,
     * and liked by nobody - likes stay while a spot is private for a while.
     */
    boolean isEmpty() {
        return !everyone && players.isEmpty() && pending.isEmpty() && likes.isEmpty();
    }

    void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    void setSpot(Spot spot) { this.spot = spot; }
    void setEveryone(boolean everyone) { this.everyone = everyone; }
    void addPlayer(UUID player, String name) {
        pending.remove(player);
        players.put(player, name);
    }
    void addPending(UUID player, String name) { pending.put(player, name); }
    /** Takes it back from a player, accepted or still offered; whether it was either. */
    boolean removePlayer(UUID player) {
        boolean accepted = players.remove(player) != null;
        boolean offered = pending.remove(player) != null;
        return accepted || offered;
    }
    /** Takes it back from everybody it was shared with or offered to by name. */
    void clearPlayers() {
        players.clear();
        pending.clear();
    }
    void addLike(UUID player) { likes.add(player); }
    boolean removeLike(UUID player) { return likes.remove(player); }
    Set<UUID> likers() { return Collections.unmodifiableSet(likes); }
}
