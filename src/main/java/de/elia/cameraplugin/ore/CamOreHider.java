package de.elia.cameraplugin.ore;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * No looking for ores with the camera, {@code hide-ores}: a camera player
 * may fly into caves, but the ores there look like the stone around them -
 * only on his own screen, nothing in the world changes, and everybody else
 * sees them as they are.
 *
 * <p>Hidden is an ore that lies open to a cave: next to a block that can be
 * seen through - air, water, glass, leaves - over which no block stands up
 * to the sky. An ore that lies open under the sky - on a cliff, at the
 * bottom of a ravine - stays as it is: it is there to be seen on foot as
 * well. One shut in on every side is no matter at all, nobody sees it
 * either way. In a world without a sky, the Nether, every open ore counts as
 * one of a cave.</p>
 *
 * <p>With {@code hide-ores.where: biomes} only the ores of the biomes of
 * {@code hide-ores.biomes} are hidden - the caves the game has as biomes of
 * their own, out of the box -, every one of them that lies open, under the
 * sky or not. The ores of an ordinary cave, which has the biome of the land
 * above it, stay as they are then.</p>
 *
 * <p>The client gets the chunks around the camera with every ore in them, so
 * the plugin tells it right after each chunk, block by block, that the ores
 * of its caves are the stone of {@code hide-ores.blocks}. Which they are is
 * worked out from a copy of the chunk, away from the main thread. Every start
 * of camera mode looks at the chunks anew; during the flight what was found
 * is kept - for a chunk that comes back, and for every camera player whose
 * flight started before it was looked at - until something in the chunk is
 * broken, built, burnt, pushed or blown up, or for {@value #KEEP_MINUTES}
 * minutes at most: what commands and other plugins change, {@code /fill} or
 * WorldEdit, comes without a word. When camera mode ends he is shown the
 * real blocks again.</p>
 *
 * <p>When a chunk reaches the client, Paper says so, and the ores of the
 * chunk go out just after it. On Spigot, which does not, the plugin looks
 * every half second which chunks came into his view and tells the client
 * twice, the second time a second later - a chunk the server sends later
 * than that shows its ores until the camera moves on.</p>
 */
public final class CamOreHider implements Listener {

    /** Paper's event for a chunk that reached a client, and the one for a chunk it dropped. */
    private static final String CHUNK_SENT = "io.papermc.paper.event.packet.PlayerChunkLoadEvent";
    private static final String CHUNK_DROPPED = "io.papermc.paper.event.packet.PlayerChunkUnloadEvent";

    /** How many chunks are copied for a look at their ores in one tick: a copy costs the main thread a little. */
    private static final int COPIES_PER_TICK = 8;

    /** How many chunks are kept looked at; the one used longest ago goes first. About a camera player's view, twice. */
    private static final int KEPT_CHUNKS = 2048;

    /**
     * How long a chunk looked at is trusted, in minutes. A block broken or
     * built by a player says so, one changed by a command or another plugin
     * - {@code /setblock}, WorldEdit - does not.
     */
    private static final long KEEP_MINUTES = 5L;
    private static final long KEEP_MILLIS = KEEP_MINUTES * 60_000L;

    /** On Spigot: how often the view of each camera player is looked at, in ticks. */
    private static final long WATCH_INTERVAL = 10L;

    /** On Spigot: when a chunk new in his view is told once more, in ticks. */
    private static final long TELL_AGAIN = 20L;

    /** The six sides of a block. */
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final CameraPlugin plugin;
    private final CamSettings settings;

    /** Every block that can be seen through, worked out once, on the main thread. */
    private Set<Material> clear = EnumSet.noneOf(Material.class);
    /** What each ore looks like for a camera player, from {@code hide-ores.blocks}, as it is when a look starts. */
    private Map<Material, BlockData> covers = Map.of();
    /**
     * The biomes whose ores alone are hidden, {@code hide-ores.where: biomes},
     * or {@code null} for the ores of every cave.
     */
    private Set<NamespacedKey> biomes;

    /** The ores of the caves of each chunk looked at, the one used last at the end. */
    private final LinkedHashMap<ChunkId, Looked> found = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkId, Looked> eldest) {
            return size() > KEPT_CHUNKS;
        }
    };
    /** The chunks waiting for a copy, nearest first as they came in, and who waits for each. */
    private final ArrayDeque<ChunkId> queue = new ArrayDeque<>();
    private final Map<ChunkId, Set<UUID>> waiting = new HashMap<>();
    /**
     * Counts the changes of each chunk, so that a look that started before a
     * change and comes back after it is not kept.
     */
    private final Map<ChunkId, Integer> changes = new HashMap<>();
    /** What each camera player is shown: by chunk, the ores hidden there. */
    private final Map<UUID, Shown> shown = new HashMap<>();

    /** Whether Paper says when a chunk reached a client; otherwise the view is watched, see {@link #watch()}. */
    private boolean paperEvents;
    /** {@code Player#getSentChunkKeys()} of Paper, or {@code null} on Spigot. */
    private Method sentChunks;
    private BukkitTask copies;
    private BukkitTask watcher;

    /** A chunk of a world. */
    record ChunkId(UUID world, int x, int z) {
        static ChunkId of(World world, int x, int z) {
            return new ChunkId(world.getUID(), x, z);
        }

        /** The chunk of a block. */
        static ChunkId ofBlock(World world, int x, int z) {
            return of(world, x >> 4, z >> 4);
        }
    }

    /** An ore of a cave, where it is and what it looks like for a camera player. */
    record Ore(int x, int y, int z, BlockData cover) {
    }

    /**
     * The ores of the caves of a chunk, and when it was looked at - when the
     * copy was taken -, as {@link System#currentTimeMillis()} counts.
     */
    private record Looked(List<Ore> ores, long at) {
    }

    /** What one camera player is shown. */
    private static final class Shown {
        final UUID world;
        /**
         * When his flight started: a chunk looked at before is looked at
         * anew, it may have been changed by a command since.
         */
        final long since = System.currentTimeMillis();
        /** The ores hidden in each chunk his client has, an empty list for one without any. */
        final Map<ChunkId, List<Ore>> chunks = new HashMap<>();

        Shown(UUID world) {
            this.world = world;
        }
    }

    public CamOreHider(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
    }

    /**
     * Hooks into the chunks that reach a client, on Paper; on Spigot the view
     * of the camera players is watched instead.
     */
    public void register() {
        try {
            Class<? extends Event> sent = Class.forName(CHUNK_SENT).asSubclass(Event.class);
            Class<? extends Event> dropped = Class.forName(CHUNK_DROPPED).asSubclass(Event.class);
            Method sentPlayer = sent.getMethod("getPlayer");
            Method droppedPlayer = dropped.getMethod("getPlayer");
            Listener owner = new Listener() {
            };
            plugin.getServer().getPluginManager().registerEvent(sent, owner, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (sent.isInstance(event)) {
                            chunkSent((Player) call(sentPlayer, event), ((ChunkEvent) event).getChunk());
                        }
                    }, plugin);
            plugin.getServer().getPluginManager().registerEvent(dropped, owner, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (dropped.isInstance(event)) {
                            chunkDropped((Player) call(droppedPlayer, event), ((ChunkEvent) event).getChunk());
                        }
                    }, plugin);
            paperEvents = true;
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException ex) {
            paperEvents = false;
        }
        try {
            sentChunks = Player.class.getMethod("getSentChunkKeys");
        } catch (NoSuchMethodException ex) {
            sentChunks = null;
        }
    }

    private static Object call(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    /**
     * After the config file was read, at the start and with every reload:
     * what is seen through, and what each ore looks like. What was looked at
     * before is forgotten - the ores may stand for something else now.
     */
    public void reload() {
        found.clear();
        covers = Map.copyOf(settings.getOreCovers());
        biomes = settings.getOreBiomes() == null ? null : Set.copyOf(settings.getOreBiomes());
        Set<Material> through = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            try {
                if (material.isBlock() && !material.isOccluding() && !covers.containsKey(material)) {
                    through.add(material);
                }
            } catch (RuntimeException ex) {
                // A legacy name of the old API: no block of this world.
            }
        }
        clear = through;
    }

    /** Whether ores are hidden at all. */
    private boolean active() {
        return settings.isHideOres() && !covers.isEmpty();
    }

    /**
     * The start of camera mode: the ores of the caves of every chunk his
     * client has are hidden, the nearest chunks first.
     */
    public void startFor(Player player) {
        if (!active()) {
            return;
        }
        World world = player.getWorld();
        shown.put(player.getUniqueId(), new Shown(world.getUID()));
        for (ChunkId id : chunksOf(player)) {
            want(player, id);
        }
        startTasks();
    }

    /**
     * The chunks his client has, nearest first: on Paper as the server knows
     * them, on Spigot those within his view.
     */
    private List<ChunkId> chunksOf(Player player) {
        World world = player.getWorld();
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;
        List<ChunkId> ids = new ArrayList<>();
        Object keys = sentChunks == null ? null : call(sentChunks, player);
        if (keys instanceof Collection<?> sent) {
            for (Object key : sent) {
                if (key instanceof Long packed) {
                    ids.add(ChunkId.of(world, (int) (long) packed, (int) (packed >>> 32)));
                }
            }
        } else {
            int radius = viewRadius(player);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    ids.add(ChunkId.of(world, centerX + x, centerZ + z));
                }
            }
        }
        ids.sort(Comparator.comparingInt(id -> Math.max(Math.abs(id.x() - centerX), Math.abs(id.z() - centerZ))));
        return ids;
    }

    /** How many chunks around him his client has: his own view, no more than the server sends. */
    private static int viewRadius(Player player) {
        return Math.max(2, Math.min(player.getClientViewDistance(), player.getWorld().getViewDistance()) + 1);
    }

    /**
     * The end of camera mode: he is shown the real blocks of every ore that
     * was hidden from him, in the chunks the server still has.
     */
    public void stopFor(Player player) {
        Shown was = shown.remove(player.getUniqueId());
        if (was == null) {
            return;
        }
        World world = Bukkit.getWorld(was.world);
        if (world == null || !world.equals(player.getWorld())) {
            // His client dropped those chunks with the world.
            return;
        }
        List<BlockState> real = new ArrayList<>();
        for (Map.Entry<ChunkId, List<Ore>> chunk : was.chunks.entrySet()) {
            if (!world.isChunkLoaded(chunk.getKey().x(), chunk.getKey().z())) {
                continue;
            }
            for (Ore ore : chunk.getValue()) {
                real.add(world.getBlockAt(ore.x(), ore.y(), ore.z()).getState());
            }
        }
        if (!real.isEmpty()) {
            player.sendBlockChanges(real);
        }
        stopTasksIfIdle();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        found.keySet().removeIf(id -> id.world().equals(world));
        queue.removeIf(id -> id.world().equals(world));
        waiting.keySet().removeIf(id -> id.world().equals(world));
    }

    /** Paper: a chunk reached the client of a player; in camera mode its ores go out right after it. */
    private void chunkSent(Player player, Chunk chunk) {
        if (player != null && shown.containsKey(player.getUniqueId())) {
            want(player, ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()));
        }
    }

    /** Paper: the client of a player dropped a chunk; it gets it whole again when it comes back. */
    private void chunkDropped(Player player, Chunk chunk) {
        Shown his = player == null ? null : shown.get(player.getUniqueId());
        if (his != null) {
            his.chunks.remove(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()));
        }
    }

    /**
     * He is to have the ores of a chunk hidden: at once while they are known,
     * otherwise once the chunk has been looked at.
     */
    private void want(Player player, ChunkId id) {
        Shown his = shown.get(player.getUniqueId());
        if (his == null) {
            return;
        }
        if (!his.world.equals(id.world())) {
            // Camera mode took him into another world: his client has none
            // of the old one any more.
            his = new Shown(id.world());
            shown.put(player.getUniqueId(), his);
        }
        Looked looked = found.get(id);
        if (looked != null && looked.at() >= his.since && System.currentTimeMillis() - looked.at() < KEEP_MILLIS) {
            show(player, his, id, looked.ores());
            return;
        }
        Set<UUID> waiters = waiting.get(id);
        if (waiters == null) {
            waiters = new LinkedHashSet<>();
            waiting.put(id, waiters);
            queue.add(id);
        }
        waiters.add(player.getUniqueId());
        startTasks();
    }

    /**
     * Shows him the ores of a chunk as their cover, and the real block of
     * any he was shown as a cover before that no longer is one: after a
     * change in the chunk, an ore that lies open under the sky now.
     */
    private void show(Player player, Shown his, ChunkId id, List<Ore> ores) {
        World world = Bukkit.getWorld(id.world());
        if (world == null || !world.equals(player.getWorld()) || !world.isChunkLoaded(id.x(), id.z())) {
            return;
        }
        List<BlockState> blocks = new ArrayList<>();
        Set<Long> now = new HashSet<>();
        for (Ore ore : ores) {
            Block block = world.getBlockAt(ore.x(), ore.y(), ore.z());
            // Mined since it was looked at: the server has told him already.
            if (covers.containsKey(block.getType())) {
                BlockState state = block.getState();
                state.setBlockData(ore.cover());
                blocks.add(state);
                now.add(pack(ore.x(), ore.y(), ore.z()));
            }
        }
        List<Ore> before = his.chunks.put(id, ores);
        if (before != null) {
            for (Ore ore : before) {
                if (!now.contains(pack(ore.x(), ore.y(), ore.z()))) {
                    blocks.add(world.getBlockAt(ore.x(), ore.y(), ore.z()).getState());
                }
            }
        }
        if (!blocks.isEmpty()) {
            player.sendBlockChanges(blocks);
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    // ------------------------------------------------------------ looking at chunks

    private void startTasks() {
        if (copies == null) {
            copies = Bukkit.getScheduler().runTaskTimer(plugin, this::copySome, 1L, 1L);
        }
        if (watcher == null && !paperEvents) {
            watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::watch, WATCH_INTERVAL, WATCH_INTERVAL);
        }
    }

    private void stopTasksIfIdle() {
        if (shown.isEmpty() && queue.isEmpty()) {
            stopTasks();
        }
    }

    private void stopTasks() {
        if (copies != null) {
            copies.cancel();
            copies = null;
        }
        if (watcher != null) {
            watcher.cancel();
            watcher = null;
        }
    }

    /**
     * Copies a few chunks waiting to be looked at, each looked at away from
     * the main thread and brought back to it, see {@link #arrived}. A chunk
     * the server has unloaded meanwhile is left: whoever wanted it does not
     * have it either.
     */
    private void copySome() {
        for (int done = 0; done < COPIES_PER_TICK && !queue.isEmpty(); done++) {
            ChunkId id = queue.poll();
            World world = Bukkit.getWorld(id.world());
            if (world == null || !world.isChunkLoaded(id.x(), id.z())) {
                waiting.remove(id);
                continue;
            }
            Set<NamespacedKey> among = biomes;
            ChunkSnapshot copy = world.getChunkAt(id.x(), id.z()).getChunkSnapshot(true, among != null, false);
            Map<Material, BlockData> lookFor = covers;
            Set<Material> through = clear;
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            boolean sky = skyCounts(world);
            int change = changes.getOrDefault(id, 0);
            long taken = System.currentTimeMillis();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Found> ores = OreScan.scan(copy, id.x(), id.z(), minY, maxY, sky, lookFor, through, among);
                Bukkit.getScheduler().runTask(plugin, () -> arrived(id, change, taken, ores));
            });
        }
        if (queue.isEmpty() && copies != null) {
            // Nothing left to look at; the next chunk wanted starts it again.
            copies.cancel();
            copies = null;
        }
    }

    /**
     * An ore the look at a copy found: where, as what, and the sides that
     * lie in the chunk next door, which only the main thread can look at.
     */
    record Found(int x, int y, int z, BlockData cover, boolean caveInside, List<int[]> outside) {
    }

    /**
     * A chunk has been looked at: the sides of its ores in the chunks next
     * door are looked at now, it is kept, and whoever waits for it gets it.
     * A look from before the last change of the chunk is thrown away, and
     * the chunk looked at again.
     */
    private void arrived(ChunkId id, int change, long taken, List<Found> scanned) {
        Set<UUID> waiters = waiting.remove(id);
        World world = Bukkit.getWorld(id.world());
        if (world == null) {
            return;
        }
        if (changes.getOrDefault(id, 0) != change) {
            if (waiters != null) {
                waiting.put(id, waiters);
                queue.add(id);
            }
            return;
        }
        List<Ore> ores = new ArrayList<>();
        for (Found ore : scanned) {
            if (hidden(world, ore)) {
                ores.add(new Ore(ore.x(), ore.y(), ore.z(), ore.cover()));
            }
        }
        List<Ore> kept = List.copyOf(ores);
        found.put(id, new Looked(kept, taken));
        if (waiters == null) {
            return;
        }
        for (UUID waiter : waiters) {
            Player player = Bukkit.getPlayer(waiter);
            Shown his = player == null ? null : shown.get(waiter);
            if (his != null && his.world.equals(id.world())) {
                show(player, his, id, kept);
            }
        }
    }

    /**
     * Whether an ore is one of a cave, with its sides next door looked at:
     * open to a cave on at least one side, open under the sky on none.
     */
    private boolean hidden(World world, Found ore) {
        boolean cave = ore.caveInside();
        for (int[] side : ore.outside()) {
            int x = side[0];
            int y = side[1];
            int z = side[2];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            if (!clear.contains(block.getType())) {
                continue;
            }
            if (skyCounts(world) && y > world.getHighestBlockYAt(x, z)) {
                return false;
            }
            cave = true;
        }
        return cave;
    }

    /**
     * Whether an ore open under the sky stays as it is: in a world with a
     * sky, unless only the ores of the biomes of {@code hide-ores.biomes} are
     * hidden - there the sky does not matter.
     */
    private boolean skyCounts(World world) {
        return biomes == null && world.hasSkyLight();
    }

    /**
     * Spigot, which does not say when a chunk reaches a client: the chunks
     * that came into the view of a camera player since the last look get
     * their ores hidden now, and once more a second later, should the server
     * have sent them only after that.
     */
    private void watch() {
        for (Map.Entry<UUID, Shown> entry : new ArrayList<>(shown.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            Shown his = entry.getValue();
            List<ChunkId> view = chunksOf(player);
            Set<ChunkId> inView = new HashSet<>(view);
            his.chunks.keySet().removeIf(id -> !inView.contains(id));
            for (ChunkId id : view) {
                if (!his.chunks.containsKey(id) && !waiting.containsKey(id)) {
                    want(player, id);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> tellAgain(player, id), TELL_AGAIN);
                }
            }
        }
    }

    /** Spigot: tells him the ores of a chunk once more, while he is still shown it. */
    private void tellAgain(Player player, ChunkId id) {
        Shown his = shown.get(player.getUniqueId());
        List<Ore> ores = his == null ? null : his.chunks.get(id);
        if (ores != null && player.isOnline()) {
            his.chunks.remove(id);
            show(player, his, id, ores);
        }
    }

    // ------------------------------------------------------------ changes in the world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        changed(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        changed(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        changed(event.getBlocks());
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        changed(event.getBlocks());
        changed(event.getBlock());
    }

    private void changed(Collection<Block> blocks) {
        for (Block block : blocks) {
            changed(block);
        }
    }

    /**
     * A block was broken, built, burnt, blown up or pushed: an ore next to
     * it may lie open now, or no longer. Its chunk - and the one next door,
     * for a block on the edge - is looked at again a tick later, once the
     * change is done, for whoever is shown it.
     */
    private void changed(Block block) {
        if (shown.isEmpty() && found.isEmpty()) {
            return;
        }
        World world = block.getWorld();
        Set<ChunkId> ids = new LinkedHashSet<>();
        for (int[] side : SIDES) {
            ids.add(ChunkId.ofBlock(world, block.getX() + side[0], block.getZ() + side[2]));
        }
        for (ChunkId id : ids) {
            found.remove(id);
            changes.merge(id, 1, Integer::sum);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ChunkId id : ids) {
                for (Map.Entry<UUID, Shown> entry : shown.entrySet()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null && entry.getValue().chunks.containsKey(id)) {
                        want(player, id);
                    }
                }
                if (!waiting.containsKey(id)) {
                    changes.remove(id);
                }
            }
        });
    }

    public void onDisable() {
        stopTasks();
        shown.clear();
        queue.clear();
        waiting.clear();
        found.clear();
        changes.clear();
    }
}
