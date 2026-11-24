package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.sky.CamSky;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spots, {@code /cam spot}: places the camera jumps to, each looking a way of
 * its own - for a build looked at from the same spot over and over.
 *
 * <p>A spot is saved where the player is, on foot or as a camera, so only a
 * place he has been to himself becomes one. The camera jumps to it only
 * where it would be allowed to fly, see {@link JumpCheck}: in the world of its
 * body, within {@code max-distance} of it, where the signal reaches, outside the areas {@code cam-area} keeps it out of, out
 * of shut lava, water and powder snow and not into a block that has been put
 * there since.</p>
 *
 * <p>Each spot of his is private, or - with {@code viewpoints.sharing} -
 * public, {@code /cam spot share <spot> all}: then everybody finds it among
 * the public spots, and may like it, {@code /cam spot like}. He may also
 * offer it to single players, {@code /cam spot share <spot> <player>}; it is
 * theirs once they accept it, {@code /cam spot accept}, and they may turn it
 * down or drop it again, {@code /cam spot decline}, see {@link SharedSpots}.
 * Every jump to a spot is a view of it, see
 * {@link SpotViews}: the menu shows them under each spot and sorts by
 * them. How often his spots were looked at and liked, he hears from the
 * guestbook, see {@link SpotGuestbook}.
 * Others jump to a spot they may use as to one of their own,
 * {@code /cam spot Owner:spot} - by the same rules, held from their own
 * body.</p>
 *
 * <p>Where they are kept is {@code viewpoints.storage}: in the file of the
 * player, or in memory only, which {@code /cam reload} and a restart
 * forget.</p>
 *
 * <p>This class keeps the spots themselves and every public method. Sharing
 * them is up to {@link SpotSharing}, the list in the chat to {@link SpotList},
 * and who has spots, for the admin page, to {@link SpotOwners}.</p>
 */
public final class CamSpots {

    /**
     * The words after {@code /cam spot} that are no spot. A name of a spot
     * may begin with one all the same: {@code /cam spot set list} saves a
     * spot called list. The command then takes it for the word, so the
     * button of the list jumps there as {@code Owner:spot}, see
     * {@link SpotList#jumpCommand}.
     */
    public static final Set<String> WORDS = Set.of("set", "delete", "list", "share", "unshare", "accept",
            "decline", "like");

    /** The word that makes a spot public, {@code /cam spot share <spot> all}. */
    static final String EVERYBODY = "all";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final SharedSpots shared;
    private final SpotViews views;
    private final SpotGuestbook guestbook;
    private final JumpCheck jumpCheck;
    /** The spots of each player, by {@link Spot#key()}, on {@code storage: memory}. */
    private final Map<UUID, Map<String, Spot>> inMemory = new HashMap<>();
    /** {@code share}, {@code unshare}, {@code accept}, {@code decline} and {@code like}, see {@link SpotSharing}. */
    private final SpotSharing sharing;
    /** {@code /cam spot list} in the chat, see {@link SpotList}. */
    private final SpotList list;
    /** Who has spots, with how many, for the admin page, see {@link SpotOwners}. */
    private final SpotOwners ownerIndex;

    public CamSpots(CameraPlugin plugin, PlayerStore store, SharedSpots shared, SpotViews views,
                    SpotGuestbook guestbook, JumpCheck jumpCheck) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
        this.shared = shared;
        this.views = views;
        this.guestbook = guestbook;
        this.jumpCheck = jumpCheck;
        this.list = new SpotList(plugin, this);
        this.sharing = new SpotSharing(plugin, shared, guestbook, this, list);
        this.ownerIndex = new SpotOwners(plugin, store, this);
    }

    /** How often a spot of this owner was looked at, see {@link SpotViews}. */
    public long views(UUID owner, Spot spot) {
        return views.of(owner, spot.key());
    }

    /**
     * A player looked at a spot of this owner - jumped there -,
     * see {@link SpotViews}. A view that counts goes into the guestbook of
     * the owner, see {@link SpotGuestbook}.
     */
    public void viewed(Player viewer, UUID owner, Spot spot) {
        if (views.count(owner, spot.key(), viewer.getUniqueId())) {
            guestbook.viewed(owner, spot, viewer);
        }
    }

    /** Whether {@code /cam spot} is there at all. */
    public boolean isEnabled() {
        return settings.spots().enabled();
    }

    boolean inFile() {
        return settings.spots().storage() == StorageMode.FILE;
    }

    /** The spots of a player, by {@link Spot#key()}, in the order he saved them; not to be changed. */
    Map<String, Spot> spotsOf(Player player) {
        if (inFile()) {
            return store.get(player.getUniqueId()).getSpots();
        }
        return Collections.unmodifiableMap(inMemory.getOrDefault(player.getUniqueId(), Map.of()));
    }

    /** His own spots, private and public, in the order he saved them. */
    public Collection<Spot> own(Player player) {
        return spotsOf(player).values();
    }

    /** The share of one of his spots, or {@code null} while it is shared with nobody. */
    public Share shareOf(Player player, Spot spot) {
        return settings.spots().sharing() ? shared.get(player.getUniqueId(), spot.key()) : null;
    }

    /** What others share with him: the public spots and those he accepted, for the tab completion. */
    public List<Share> sharedWith(Player player) {
        return settings.spots().sharing() ? shared.sharedWith(player.getUniqueId()) : List.of();
    }

    /** Every public spot, his own among them, for the page of the public spots. */
    public List<Share> publicSpots() {
        return settings.spots().sharing() ? shared.publicSpots() : List.of();
    }

    /** What others offered him and he has not accepted yet. */
    public List<Share> offers(Player player) {
        return settings.spots().sharing() ? shared.offeredTo(player.getUniqueId()) : List.of();
    }

    /** What others share with him by name and he accepted, for the page of the shared spots. */
    public List<Share> accepted(Player player) {
        return settings.spots().sharing() ? shared.acceptedBy(player.getUniqueId()) : List.of();
    }

    /** The names of his spots, for the tab completion. */
    public List<String> names(Player player) {
        List<String> names = new ArrayList<>();
        for (Spot spot : spotsOf(player).values()) {
            names.add(spot.name());
        }
        return names;
    }

    /** {@code Owner:spot} of every spot he may use, for the tab completion. */
    public List<String> sharedLabels(Player player) {
        return labels(sharedWith(player));
    }

    /** {@code Owner:spot} of what was offered to him, for the tab completion of {@code accept}. */
    public List<String> offerLabels(Player player) {
        return labels(offers(player));
    }

    /** {@code Owner:spot} of what he may decline: the offers and what he accepted. */
    public List<String> declinableLabels(Player player) {
        List<String> labels = labels(offers(player));
        labels.addAll(labels(accepted(player)));
        return labels;
    }

    /** {@code Owner:spot} of the public spots of others, for the tab completion of {@code like}. */
    public List<String> likeableLabels(Player player) {
        List<String> labels = new ArrayList<>();
        for (Share share : publicSpots()) {
            if (!share.getOwner().equals(player.getUniqueId())) {
                labels.add(share.label());
            }
        }
        return labels;
    }

    private static List<String> labels(List<Share> shares) {
        List<String> labels = new ArrayList<>();
        for (Share share : shares) {
            labels.add(share.label());
        }
        return labels;
    }

    /**
     * Whom one of his spots is shared with, for the tab completion of
     * {@code /cam spot unshare}: {@code all} while it is public, and the
     * names, accepted or offered.
     */
    public List<String> sharedNames(Player player, String name) {
        Share share = settings.spots().sharing() ? shared.get(player.getUniqueId(), Spot.key(name)) : null;
        List<String> names = new ArrayList<>();
        if (share != null) {
            if (share.isEveryone()) {
                names.add(EVERYBODY);
            }
            names.addAll(share.getPlayers().values());
            names.addAll(share.getPending().values());
        }
        return names;
    }

    /** {@code /cam spot}, with what follows it. */
    public void command(Player player, String[] args) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(player, "spot-off");
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list.show(player);
            return;
        }
        // A name may have spaces: it is all the words after the one of the
        // command - after share and unshare all but the last, the player.
        String first = args[0].toLowerCase(Locale.ROOT);
        String rest = words(args, 1, args.length);
        if (args.length >= 2 && first.equals("set")) {
            set(player, rest);
        } else if (args.length >= 2 && first.equals("delete")) {
            delete(player, rest);
        } else if (args.length >= 3 && first.equals("share")) {
            sharing.share(player, words(args, 1, args.length - 1), args[args.length - 1]);
        } else if (args.length >= 3 && first.equals("unshare")) {
            sharing.unshare(player, words(args, 1, args.length - 1), args[args.length - 1]);
        } else if (args.length >= 2 && first.equals("accept")) {
            sharing.accept(player, rest);
        } else if (args.length >= 2 && first.equals("decline")) {
            sharing.decline(player, rest);
        } else if (args.length >= 2 && first.equals("like")) {
            sharing.like(player, rest);
        } else if (!WORDS.contains(first)) {
            jump(player, words(args, 0, args.length));
        } else {
            messages.sendConfiguredMessage(player, "spot-usage");
        }
    }

    /** The words of a command from one to before another, with a space between them: a name with spaces. */
    private static String words(String[] args, int from, int to) {
        return String.join(" ", Arrays.copyOfRange(args, from, to));
    }

    /**
     * Saves the spot where he is, looking the way he looks, under a name none
     * of his spots has yet: a name he has already is turned down, so that two
     * of his spots are never mistaken for each other - nor one overwritten by
     * another. Moving a spot is for its page of options, see {@link #move}.
     *
     * @return the spot, or {@code null} when it was not saved; he has been told why
     */
    public Spot set(Player player, String typed) {
        if (!maySetHere(player)) {
            return null;
        }
        return save(player, typed, player.getLocation(), lookOf(player), null);
    }

    /**
     * Saves a new spot of his - from {@code /cam spot set}, or from the menu,
     * where it was named a moment before -: under a name none of his spots
     * has yet, while no restriction keeps him from it and he has room for one
     * more. If not, he has been told why.
     *
     * @param where where it is, looking its way
     * @param look  what it shows, see {@link #lookOf}
     * @param icon  its icon, see {@link #icons}; {@code null} for none of its own
     * @return the spot, or {@code null} when it was not saved
     */
    public Spot save(Player player, String typed, Location where, SpotLook look, String icon) {
        String name = checkName(player, player.getUniqueId(), typed, null);
        if (name == null || !plugin.getSpotModeration().checkNew(player, SpotBans.Kind.PRIVATE)
                || !hasRoom(player)) {
            return null;
        }
        Spot spot = Spot.at(name, where, System.currentTimeMillis(), look, icon);
        if (inFile()) {
            store.get(player.getUniqueId()).putSpot(player.getName(), spot);
            store.save(player.getUniqueId());
        } else {
            inMemory.computeIfAbsent(player.getUniqueId(), id -> new LinkedHashMap<>()).put(spot.key(), spot);
        }
        ownerIndex.count(player);
        messages.sendConfiguredMessage(player, "spot-saved", "{spot}", name);
        tellLook(player, name, look);
        return spot;
    }

    /** After a spot was saved or moved, while it takes along a look of its own: what it shows, {@code spot-saved-look}. */
    private void tellLook(Player player, String name, SpotLook look) {
        String described = describe(look);
        if (!described.isEmpty()) {
            messages.sendMessage(player, "spot-saved-look", "{spot}", name, "{look}", described);
        }
    }

    /**
     * Whether he has room for one more spot under
     * {@code viewpoints.max-per-player}. If not, he has been told.
     */
    public boolean hasRoom(Player player) {
        if (settings.spots().limited() && spotsOf(player).size() >= settings.spots().max()) {
            messages.sendConfiguredMessage(player, "spot-full", "{max}",
                    String.valueOf(settings.spots().max()));
            return false;
        }
        return true;
    }

    /**
     * A name as it is typed, made into the name of a spot of this owner: one
     * of 1 to {@value Spot#MAX_NAME} characters that none of his other spots
     * has - capitals and extra spaces not counting, as the spots are found by
     * their names. If not, whoever typed it has been told why.
     *
     * @param keep the {@link Spot#key()} of the spot that is being renamed,
     *             which may keep its own name in other capitals; {@code null}
     *             for a new spot
     * @return the name, or {@code null}
     */
    public String checkName(CommandSender to, UUID owner, String typed, String keep) {
        String name = Spot.validName(typed);
        if (name == null) {
            messages.sendConfiguredMessage(to, "spot-bad-name");
            return null;
        }
        Spot other = spotsOf(owner).get(Spot.key(name));
        if (other != null && !other.key().equals(keep)) {
            messages.sendConfiguredMessage(to, "spot-name-taken", "{spot}", other.name());
            return null;
        }
        return name;
    }

    /**
     * Renames a spot of an owner, online or not - by himself or by an admin -,
     * to a name none of his other spots has. Whoever it is shared with finds
     * it under its new name; its views and likes, and what the guestbook has
     * for it, go along. If not, {@code to} has been told why.
     *
     * @param key the {@link Spot#key()} it has now
     * @return the spot under its new name, or {@code null}
     */
    public Spot rename(CommandSender to, UUID owner, String key, String typed) {
        Spot spot = spotsOf(owner).get(key);
        if (spot == null) {
            messages.sendConfiguredMessage(to, "spot-unknown", "{spot}", typed);
            return null;
        }
        String name = checkName(to, owner, typed, key);
        if (name == null) {
            return null;
        }
        Spot renamed = spot.withName(name);
        return replace(owner, key, renamed) ? renamed : null;
    }

    /** Renames one of his own spots, from its page of options; he hears how it went. */
    public Spot renameOwn(Player player, String key, String typed) {
        Spot before = spotsOf(player).get(key);
        Spot renamed = rename(player, player.getUniqueId(), key, typed);
        if (renamed != null) {
            messages.sendConfiguredMessage(player, "spot-renamed", "{old}", before.name(), "{spot}", renamed.name());
        }
        return renamed;
    }

    /** Gives one of his own spots another icon, from the menu; he hears about it. */
    public Spot changeOwnIcon(Player player, String key, String icon) {
        Spot changed = changeIcon(player.getUniqueId(), key, icon);
        if (changed == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", key);
            return null;
        }
        messages.sendConfiguredMessage(player, "spot-icon-changed", "{spot}", changed.name(),
                "{icon}", iconName(icon));
        return changed;
    }

    /**
     * Gives a spot of an owner, online or not, another icon - by himself or by
     * an admin. Whoever it is shared with sees it so as well.
     *
     * @param icon one of {@link #icons}
     * @return the spot with its new icon, or {@code null} while there is no such spot
     */
    public Spot changeIcon(UUID owner, String key, String icon) {
        Spot spot = spotsOf(owner).get(key);
        if (spot == null) {
            return null;
        }
        Spot changed = spot.withIcon(icon);
        return replace(owner, key, changed) ? changed : null;
    }

    /**
     * Moves one of his spots to where his camera is, looking the way it
     * looks, with the look he has picked for himself now - only in camera
     * mode, where a spot is saved as well. It keeps its name, its date, its
     * icon, its views and likes, and whoever it is shared with finds it
     * there. If not, he has been told why.
     *
     * @return the spot where it is now, or {@code null}
     */
    public Spot move(Player player, String key) {
        Spot spot = spotsOf(player).get(key);
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", key);
            return null;
        }
        if (!plugin.isInCameraMode(player)) {
            messages.sendConfiguredMessage(player, "spot-move-cam-only", "{spot}", spot.name());
            return null;
        }
        SpotLook look = lookOf(player);
        Spot moved = Spot.at(spot.name(), player.getLocation(), spot.created(), look, spot.icon());
        if (!replace(player.getUniqueId(), key, moved)) {
            return null;
        }
        shared.moved(player.getUniqueId(), player.getName(), moved);
        messages.sendConfiguredMessage(player, "spot-moved", "{spot}", moved.name());
        tellLook(player, moved.name(), look);
        return moved;
    }

    /**
     * Puts a spot of an owner, online or not, in the place of one of his -
     * renamed, moved, with another icon -, and lets whatever goes by its name
     * follow it: whom it is shared with, its views, its guestbook.
     *
     * @param key the {@link Spot#key()} of the one it replaces
     * @return whether he had that one
     */
    private boolean replace(UUID owner, String key, Spot spot) {
        boolean replaced;
        if (inFile()) {
            replaced = store.change(owner, record -> record.replaceSpot(key, spot));
        } else {
            Map<String, Spot> own = inMemory.get(owner);
            replaced = own != null && own.containsKey(key);
            if (replaced) {
                Map<String, Spot> before = new LinkedHashMap<>(own);
                own.clear();
                for (Map.Entry<String, Spot> entry : before.entrySet()) {
                    own.putIfAbsent(entry.getKey().equals(key) ? spot.key() : entry.getKey(),
                            entry.getKey().equals(key) ? spot : entry.getValue());
                }
            }
        }
        if (!replaced) {
            return false;
        }
        shared.changed(owner, key, spot);
        views.renamed(owner, key, spot.key());
        guestbook.renamed(owner, key, spot.key());
        return true;
    }

    // ------------------------------------------------------------ icons

    /**
     * The icons a spot may have: those of {@code viewpoints.icons} this
     * server knows, in their order - up to 26.2 the filled map and the empty
     * one, from 26.3 the maps of the structures as well, each an item of its
     * own there.
     */
    public List<String> icons() {
        List<String> known = new ArrayList<>();
        for (String key : settings.spots().icons()) {
            if (iconItem(key) != null) {
                known.add(key);
            }
        }
        return known;
    }

    /** The icon a new spot of the menu starts with: the first of {@link #icons}, or {@code null} for none. */
    public String firstIcon() {
        List<String> known = icons();
        return known.isEmpty() ? null : known.get(0);
    }

    /** The item of an icon, or {@code null} for one this server does not know. */
    public static Material iconItem(String key) {
        if (key == null) {
            return null;
        }
        Material material = Material.matchMaterial(key);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    /**
     * The item the menu shows a spot as: its icon, or {@code fallback} for one
     * without - or with one this server does not know, from a later version.
     */
    public static Material iconOf(Spot spot, Material fallback) {
        Material own = iconItem(spot.icon());
        return own != null ? own : fallback;
    }

    /**
     * What an icon is called, {@code map-names} in the language file; for
     * none of its own, what the filled map is called, as which a spot without
     * one shows.
     */
    public String iconName(String key) {
        String icon = key == null ? "filled_map" : key;
        return messages.getName("map-names", icon, icon.replace('_', ' '));
    }

    /**
     * Whether he may save a spot where he is: anywhere, or with
     * {@code viewpoints.set-in-cam-only} only in camera mode, where the
     * camera is. If not, he has been told so.
     */
    public boolean maySetHere(Player player) {
        if (settings.spots().setInCamOnly() && !plugin.isInCameraMode(player)) {
            messages.sendConfiguredMessage(player, "spot-set-cam-only");
            return false;
        }
        return true;
    }

    /**
     * What a spot saved now takes along, see {@link SpotLook}: the time, the
     * weather and the night vision he picked for himself - or that a spot he
     * was put down at, or the guide he follows, shows him - in camera mode, as
     * far as the config file lets anybody pick them. Out of camera mode he
     * sees the time and weather of the world, and the spot takes nothing.
     * Also what the end of a flight keeps for {@code /cam resume}.
     */
    public SpotLook lookOf(Player player) {
        if (!plugin.isInCameraMode(player)) {
            return SpotLook.NONE;
        }
        SpotLook look = new SpotLook(plugin.getSky().seenTime(player), plugin.getSky().seenWeather(player),
                plugin.getNightVision().chosenMode(player));
        return look.isEmpty() ? SpotLook.NONE : look;
    }

    /**
     * The look of a spot written out for a message, the parts joined by
     * {@code spot-look-separator}: what holds for whoever comes there now -
     * a part the config file switched off is left out, as it is when
     * somebody jumps there. Empty for a spot that shows nothing of its own.
     */
    public String describe(SpotLook look) {
        List<String> parts = new ArrayList<>();
        if (look.time() != null && settings.isCamTimeEnabled()) {
            String word = CamSky.timeWord(look.time());
            parts.add(messages.getMessage("spot-look-time").replace("{time}",
                    word != null ? messages.getMessage("spot-look-time-" + word) : String.valueOf(look.time())));
        }
        if (look.weather() != null && settings.isCamWeatherEnabled()) {
            parts.add(messages.getMessage("spot-look-weather").replace("{weather}",
                    messages.getMessage("spot-look-weather-" + SpotLook.weatherWord(look.weather()))));
        }
        if (look.nightVision() != null && settings.isNightVisionPlayerChoice()) {
            parts.add(messages.getMessage("spot-look-nightvision").replace("{mode}",
                    messages.getMessage(switch (look.nightVision()) {
                        case ALWAYS -> "nightvision-always";
                        case OFF -> "nightvision-never";
                        case DARK -> "nightvision-dark";
                    })));
        }
        parts.removeIf(String::isEmpty);
        return String.join(messages.getMessage("spot-look-separator"), parts);
    }

    /**
     * His camera was put down at a spot - he jumped there -:
     * its time, weather and night vision stand in for his own, see
     * {@link SpotLook}.
     */
    public void showLook(Player player, Spot spot) {
        showLook(player, spot.look());
    }

    /**
     * His camera was put down where it shows this look - at a spot, or at
     * the end of his last flight, see {@link de.elia.cameraplugin.resume.CamResume}
     * -: its time, weather and night vision stand in for his own, as those of
     * a spot do.
     */
    public void showLook(Player player, SpotLook look) {
        plugin.getSky().showSpot(player, look);
        plugin.getNightVision().showSpot(player, look.nightVision());
    }

    /**
     * His camera was put down somewhere that is no spot - back at his body,
     * at a player he follows -: his own time, weather and night vision hold
     * again.
     */
    public void dropLook(Player player) {
        plugin.getSky().leaveSpot(player);
        plugin.getNightVision().leaveSpot(player);
    }

    public void delete(Player player, String name) {
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (inFile()) {
            PlayerRecord record = store.get(player.getUniqueId());
            record.removeSpot(spot.key());
            store.save(player.getUniqueId());
        } else {
            inMemory.get(player.getUniqueId()).remove(spot.key());
        }
        // A spot that is gone is shared with nobody.
        shared.deleted(player.getUniqueId(), spot.key());
        views.deleted(player.getUniqueId(), spot.key());
        guestbook.deleted(player.getUniqueId(), spot.key());
        ownerIndex.count(player);
        messages.sendConfiguredMessage(player, "spot-deleted", "{spot}", spot.name());
    }
    // ------------------------------------------------------------ sharing, see SpotSharing

    /** Offers one of his spots to a player, from the menu, see {@link SpotSharing#shareWith}. */
    public void shareWith(Player player, Spot spot, OfflinePlayer other) {
        sharing.shareWith(player, spot, other);
    }

    /** Takes a share back from a player, from the menu, see {@link SpotSharing#unshareWith}. */
    public void unshareWith(Player player, Spot spot, UUID otherId, String otherName) {
        sharing.unshareWith(player, spot, otherId, otherName);
    }

    /** Makes one of his spots public, or private again, from the menu, see {@link SpotSharing#makePublic}. */
    public void makePublic(Player player, Spot spot, boolean everybody) {
        sharing.makePublic(player, spot, everybody);
    }

    /** Takes a spot offered to him, from the menu, see {@link SpotSharing#accept(Player, Share)}. */
    public void accept(Player player, Share share) {
        sharing.accept(player, share);
    }

    /** Turns down an offer or drops a spot he accepted, from the menu, see {@link SpotSharing#decline(Player, Share)}. */
    public void decline(Player player, Share share) {
        sharing.decline(player, share);
    }

    /** Likes a public spot, or no longer does, from the menu, see {@link SpotSharing#like(Player, Share)}. */
    public void like(Player player, Share share) {
        sharing.like(player, share);
    }

    /** Who accepted a share, written out. */
    public String whom(Share share) {
        return String.join(", ", share.getPlayers().values());
    }

    /** Whom a share is offered to and who has not accepted it yet, written out. */
    public String offeredTo(Share share) {
        return String.join(", ", share.getPending().values());
    }

    /**
     * One of his own spots by what follows {@code /cam spot}: its name, or
     * {@code <he>:<name>} as others write it, see {@link SpotList#jumpCommand}.
     * The name itself comes first, so a spot of his whose name has a
     * {@code :} is found as well.
     */
    private Spot ownSpot(Player player, String name) {
        Map<String, Spot> spots = spotsOf(player);
        Spot spot = spots.get(Spot.key(name));
        int colon = name.indexOf(':');
        if (spot == null && colon > 0 && name.substring(0, colon).equalsIgnoreCase(player.getName())) {
            spot = spots.get(Spot.key(name.substring(colon + 1)));
        }
        return spot;
    }

    /**
     * Puts the camera down at one of his spots, or at one shared with him -
     * {@code Owner:spot} -, if it may be there. Out of camera mode, it starts
     * first, where he stands, if the spot is no further than
     * {@code viewpoints.start-distance} from him - with every check and cost
     * of a start of {@code /cam}.
     */
    public void jump(Player player, String name) {
        Spot spot = ownSpot(player, name);
        String label;
        UUID owner;
        if (spot == null && name.indexOf(':') >= 0) {
            Share share = settings.spots().sharing() ? shared.find(player.getUniqueId(), name) : null;
            if (share == null) {
                messages.sendConfiguredMessage(player, "spot-shared-unknown", "{spot}", name);
                return;
            }
            if (!plugin.getSpotModeration().checkLook(player, share)) {
                return;
            }
            spot = share.getSpot();
            label = sharing.shown(share);
            owner = share.getOwner();
        } else {
            if (spot == null) {
                messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
                return;
            }
            if (!plugin.getSpotModeration().checkLook(player)) {
                return;
            }
            label = spot.name();
            owner = player.getUniqueId();
        }
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (data == null) {
            startThenJump(player, name, spot, label);
            return;
        }
        Location target = spot.toLocation();
        if (target == null || !target.getWorld().equals(data.getBody().getWorld())) {
            messages.sendConfiguredMessage(player, "spot-other-world", "{spot}", label);
            return;
        }
        CamMovementGuard guard = plugin.getMovementGuard();
        if (!jumpCheck.mayGo(player, guard.home(data), guard.homeEyes(player, data), target, "spot",
                "{spot}", label)) {
            return;
        }
        // A jump of his own takes him off a tour he follows.
        plugin.getTour().leave(player);
        if (!guard.jumpTo(player, data, target)) {
            return;
        }
        showLook(player, spot);
        viewed(player, owner, spot);
        settings.sounds().spot().play(player);
        if (messages.isMessageEnabled("spot-jumped")) {
            plugin.getActionBar().flash(player, messages.getMessage("spot-jumped").replace("{spot}", label));
        }
    }

    /**
     * A jump out of camera mode: if the spot is close enough, camera mode
     * starts where he stands, and the camera jumps from there. What a jump
     * from there would turn down is asked before anything starts - his body
     * will stand where he stands -, and once more by the jump itself.
     */
    private void startThenJump(Player player, String name, Spot spot, String label) {
        double reach = settings.spots().startDistance();
        if (reach <= 0.0) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        Location target = spot.toLocation();
        if (target == null || !target.getWorld().equals(player.getWorld())) {
            messages.sendConfiguredMessage(player, "spot-other-world", "{spot}", label);
            return;
        }
        double distance = target.distance(player.getLocation());
        if (distance > reach) {
            messages.sendConfiguredMessage(player, "spot-start-too-far", "{spot}", label,
                    "{blocks}", String.valueOf(blocksTooFar(distance, reach)));
            return;
        }
        if (!jumpCheck.mayGo(player, player.getLocation(), player.getEyeLocation(), target, "spot",
                "{spot}", label)) {
            return;
        }
        plugin.getStarter().startThen(player, () -> {
            if (plugin.isInCameraMode(player)) {
                jump(player, name);
            }
        });
    }

    /** How many blocks too far away he is, rounded up: never 0 for whoever is too far at all. */
    public static long blocksTooFar(double distance, double reach) {
        return Math.max(1L, (long) Math.ceil(distance - reach));
    }

    /** After a reload: on {@code storage: memory} every spot is forgotten. */
    public void onReload() {
        inMemory.clear();
    }

    // ------------------------------------------------------------ the admin page

    /**
     * A player who has spots, for the admin page of the spots: his name and
     * how many he has.
     */
    public record Owner(UUID id, String name, int spots) {
    }

    /**
     * Reads who has spots, at the start and after a reload, see
     * {@link SpotOwners#index()}.
     */
    public void indexOwners() {
        ownerIndex.index();
    }

    /** Everybody who has spots, online or not, by name. */
    public List<Owner> owners() {
        return ownerIndex.all();
    }

    /** The spots of each player on {@code storage: memory}, by {@link Spot#key()}; not to be changed. */
    Map<UUID, Map<String, Spot>> inMemory() {
        return Collections.unmodifiableMap(inMemory);
    }

    /**
     * The spots of a player, online or not, by {@link Spot#key()}, for the
     * admin page: read from his file while he is offline.
     */
    public Map<String, Spot> spotsOf(UUID owner) {
        if (inFile()) {
            return store.look(owner).getSpots();
        }
        return Collections.unmodifiableMap(inMemory.getOrDefault(owner, Map.of()));
    }

    /** The share of one of his spots, or {@code null} while it is shared with nobody, for the admin page. */
    public Share shareOf(UUID owner, String key) {
        return shared.get(owner, key);
    }

    /** What one player shares, public or by name, for the admin page. */
    public List<Share> sharesOf(UUID owner) {
        return shared.of(owner);
    }

    /**
     * An admin takes one of his spots down: no longer public, shared with
     * nobody, offered to nobody.
     *
     * @return whether it was shared at all
     */
    public boolean takeDown(UUID owner, String key) {
        return shared.takeBack(owner, key);
    }

    /**
     * An admin deletes one of his spots, whether he is online or not: out of
     * his file, shared with nobody, its views gone.
     *
     * @return whether he had it
     */
    public boolean deleteOf(UUID owner, String key) {
        boolean removed;
        if (inFile()) {
            removed = store.change(owner, record -> record.removeSpot(key));
        } else {
            Map<String, Spot> own = inMemory.get(owner);
            removed = own != null && own.remove(key) != null;
        }
        if (!removed) {
            return false;
        }
        shared.deleted(owner, key);
        views.deleted(owner, key);
        guestbook.deleted(owner, key);
        ownerIndex.recount(owner);
        return true;
    }
}
