package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.spot.CamSpots.Owner;
import de.elia.cameraplugin.storage.PlayerStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who has spots of {@link CamSpots}, with how many, for the admin page of the
 * spots - online or not, without reading the file of everybody each time it
 * opens.
 */
final class SpotOwners {

    private final CameraPlugin plugin;
    private final PlayerStore store;
    /** The spots, asked where they are kept and how many a player has. */
    private final CamSpots spots;
    /**
     * Who has spots, with how many: on {@code storage: file} read once from
     * the files of the players in the background, at the start and after a
     * reload, see {@link #index()}, and kept up to date as spots are
     * saved and deleted - so that the admin page reads no file of everybody
     * every time it opens.
     */
    private final Map<UUID, Owner> owners = new HashMap<>();
    /** Counts the readings of the files, so that an older one that comes back late is left alone. */
    private int ownerReadings;

    SpotOwners(CameraPlugin plugin, PlayerStore store, CamSpots spots) {
        this.plugin = plugin;
        this.store = store;
        this.spots = spots;
    }

    /**
     * Reads who has spots, at the start and after a reload: on
     * {@code storage: file} from every file of a player, in the background;
     * whoever is online counts with what he has right now.
     */
    void index() {
        owners.clear();
        int reading = ++ownerReadings;
        if (!spots.inFile()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, PlayerStore.SpotCount> found = store.countSpots();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (reading != ownerReadings) {
                    return;
                }
                owners.clear();
                for (Map.Entry<UUID, PlayerStore.SpotCount> entry : found.entrySet()) {
                    owners.put(entry.getKey(), new Owner(entry.getKey(), entry.getValue().name(),
                            entry.getValue().spots()));
                }
                for (Player online : Bukkit.getOnlinePlayers()) {
                    count(online);
                }
            });
        });
    }

    /** Counts the spots of a player online anew, after he saved or deleted one. */
    void count(Player player) {
        count(player.getUniqueId(), player.getName(), spots.spotsOf(player).size());
    }

    private void count(UUID id, String name, int spots) {
        if (spots > 0) {
            owners.put(id, new Owner(id, name, spots));
        } else {
            owners.remove(id);
        }
    }

    /**
     * An admin deleted a spot of this owner, online or not: he is counted
     * anew - while he is counted at all.
     */
    void recount(UUID owner) {
        Owner before = owners.get(owner);
        if (before != null) {
            count(owner, before.name(), spots.spotsOf(owner).size());
        }
    }

    /** Everybody who has spots, online or not, by name. */
    List<Owner> all() {
        Map<UUID, Owner> all = new HashMap<>(owners);
        if (!spots.inFile()) {
            for (Map.Entry<UUID, Map<String, Spot>> entry : spots.inMemory().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    all.put(entry.getKey(), new Owner(entry.getKey(), name == null ? entry.getKey().toString() : name,
                            entry.getValue().size()));
                }
            }
        }
        List<Owner> list = new ArrayList<>(all.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }
}
