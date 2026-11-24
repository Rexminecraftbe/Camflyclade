package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * The orders of the lists of {@link CamMenu} - the tabs of the spots, the
 * spots, the players -, and who stands in them. How each player has a list
 * sorted, while he is online, the menu keeps.
 */
final class MenuSorts {

    /** The orders of the spots of a player on the admin page, the first being where it starts. */
    static final List<String> ADMIN_SPOT_SORTS = List.of("date", "name", "likes", "views", "near");

    /**
     * The orders of the page that shares a spot, the first being where it
     * starts: those it is shared with first, by name, the nearest first.
     */
    static final List<String> SHARE_SORTS = List.of("shared", "name", "near");

    private MenuSorts() {
    }

    /**
     * The tabs of the spots: all his own, the public ones of everybody and
     * those shared with him - each with its icon, its slot in the last row
     * and the orders it can be sorted in, the first being where it starts.
     */
    enum SpotTab {
        PRIVATE("private", Material.FILLED_MAP, 48, List.of("date", "name", "views", "near")),
        PUBLIC("public", Material.GLOBE_BANNER_PATTERN, 49,
                List.of("date", "name", "owner", "likes", "views", "near")),
        SHARED("shared", Material.MAP, 50, List.of("date", "name", "owner", "views", "near"));

        final String key;
        final Material icon;
        final int slot;
        final List<String> sorts;

        SpotTab(String key, Material icon, int slot, List<String> sorts) {
            this.key = key;
            this.icon = icon;
            this.slot = slot;
            this.sorts = sorts;
        }
    }

    /**
     * The orders of the spots: the newest first, by name, by the name of the
     * owner, the most liked first, the most viewed first, the nearest first.
     */
    enum SpotSort {
        DATE("date"), NAME("name"), OWNER("owner"), LIKES("likes"), VIEWS("views"), NEAR("near");

        final String key;

        SpotSort(String key) {
            this.key = key;
        }

        static SpotSort of(String key) {
            for (SpotSort sort : values()) {
                if (sort.key.equals(key)) {
                    return sort;
                }
            }
            return DATE;
        }
    }

    /** The orders of the lists of players on the admin pages: by name, the last flight first, or the nearest first. */
    enum PlayerSort {
        NAME("name"), LAST_FLIGHT("last-flight"), NEAR("near");

        static final List<String> KEYS = List.of("name", "last-flight", "near");

        final String key;

        PlayerSort(String key) {
            this.key = key;
        }

        static PlayerSort of(String key) {
            for (PlayerSort sort : values()) {
                if (sort.key.equals(key)) {
                    return sort;
                }
            }
            return NAME;
        }
    }

    /** A spot shared by its owner, as an entry of a list: the same spot offered and accepted is the same one. */
    static String shareKey(Share share) {
        return share.getOwner() + "/" + share.getSpot().key();
    }

    /** Whom he may share a spot with on its page: every other player online he can see, by name. */
    static Map<UUID, String> sharePeople(Player player) {
        Map<UUID, String> people = new LinkedHashMap<>();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (Player other : online) {
            if (!other.equals(player) && player.canSee(other)) {
                people.put(other.getUniqueId(), other.getName());
            }
        }
        return people;
    }

    /**
     * Where a player comes when those a spot is shared with come first: who
     * accepted it, then who it is offered to - or, a new spot, to be offered
     * once it is saved -, then the rest.
     *
     * @param state how it stands with him, as {@link SpotPages#sharePage} has it
     */
    static int shareRank(String state) {
        return switch (state) {
            case "on" -> 0;
            case "offered", "invited" -> 1;
            default -> 2;
        };
    }

    /**
     * How far another player is from him, squared - endlessly far while the
     * other is offline, in another world or hidden from him. Each of them
     * where he is - in camera mode where his camera is -, as on the page of
     * the tour.
     *
     * @param other the other player, {@code null} while he is offline
     */
    static double distance(Player viewer, Player other) {
        if (other == null || !other.getWorld().equals(viewer.getWorld())
                || !other.equals(viewer) && !viewer.canSee(other)) {
            return Double.MAX_VALUE;
        }
        return other.getLocation().distanceSquared(viewer.getLocation());
    }

    /**
     * Spots in an order: the newest first - those without a date, saved
     * before dates were kept, last -, by name, by owner, the most liked
     * first, the most viewed first, or the nearest first - those in another
     * world last. What is the same in one order goes by name, then by owner.
     *
     * @param views how often each was looked at, see {@link de.elia.cameraplugin.spot.SpotViews}
     * @param from  what "nearest first" measures from, see {@link SpotPages#nearFrom}
     */
    static <T> List<T> sorted(Collection<T> items, SpotSort sort, Function<T, Spot> spot,
                                      Function<T, String> owner, ToIntFunction<T> likes, ToLongFunction<T> views,
                                      Location from) {
        Comparator<T> byName = Comparator.comparing(item -> spot.apply(item).name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<T> byOwner = Comparator.comparing(owner, String.CASE_INSENSITIVE_ORDER);
        Comparator<T> newest = Comparator.comparingLong((T item) -> spot.apply(item).created()).reversed();
        Comparator<T> order = switch (sort) {
            case DATE -> newest.thenComparing(byName).thenComparing(byOwner);
            case NAME -> byName.thenComparing(byOwner);
            case OWNER -> byOwner.thenComparing(byName);
            case LIKES -> Comparator.comparingInt(likes).reversed().thenComparing(newest).thenComparing(byName);
            case VIEWS -> Comparator.comparingLong(views).reversed().thenComparing(newest).thenComparing(byName);
            case NEAR -> Comparator.comparingDouble((T item) -> distance(spot.apply(item), from))
                    .thenComparing(byName).thenComparing(byOwner);
        };
        List<T> list = new ArrayList<>(items);
        list.sort(order);
        return list;
    }

    /** How far a spot is from a place, squared - endlessly far in another world. */
    static double distance(Spot spot, Location from) {
        if (from.getWorld() == null || !from.getWorld().getName().equals(spot.world())) {
            return Double.MAX_VALUE;
        }
        double dx = spot.x() - from.getX();
        double dy = spot.y() - from.getY();
        double dz = spot.z() - from.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The players online he can see, by name, for the admin pages. */
    static List<Player> onlineSorted(Player viewer) {
        List<Player> online = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer) || viewer.canSee(other)) {
                online.add(other);
            }
        }
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return online;
    }
}
