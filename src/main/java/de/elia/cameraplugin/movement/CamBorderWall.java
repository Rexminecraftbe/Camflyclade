package de.elia.cameraplugin.movement;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wall of {@code border-mode: barrier}: blocks of
 * {@code camera-mode.border-block} along the border of camera mode, which
 * only the camera player has.
 *
 * <p>They are sent to that one client with {@link Player#sendBlockChanges} and
 * nowhere else. The world stays as it is: every other player sees what stands
 * there, and so does the server. The client runs into them like into any
 * block of the world, so the camera stops at the border by itself - no step
 * is cancelled, which is what makes the camera jerk back under
 * {@code push-back}.</p>
 *
 * <p>Three borders are built: {@code camera-mode.max-distance} around the
 * body (or the portal the player came out of, see
 * {@link CamMovementGuard#distanceAnchor}), the biomes and structures
 * {@code cam-area} keeps the camera out of on level 2, and lava, water and
 * powder snow where their switch shuts them, see {@link FlightMedium}. The
 * wall never reaches past what those allow: a block of it is set wherever the
 * body would be too far away, in a forbidden block or in a shut medium if it
 * touched that block at all. {@link CamMovementGuard} keeps its own checks
 * behind it, which a player who stays in front of the wall can therefore
 * never set off. They are left for a client that ignores the wall - and for
 * the few gaps it cannot close, see {@link #shownFor(Wall, Block)}.</p>
 *
 * <p>Only the front of the border is built, the blocks next to the space the
 * camera may use, and of those only the ones up to
 * {@code camera-mode.border-radius} away from the body. Whole blocks keep
 * their place in it, everything else gives way to the wall. The wall moves
 * along with the player, and every block it leaves behind is given back as it
 * really is. Where it stands is worked out by {@link WallSections}, which
 * keeps what it has read out of the world, so that a large radius costs
 * about as much as the border it shows and not as the room around it.</p>
 */
public final class CamBorderWall implements Listener {

    /**
     * Ticks between two full looks at the wall. A look reads again what has
     * grown old around the player, and the blocks of the wall are checked
     * against the world until the next one, a share of them each tick, see
     * {@link #dueForCheck}: the server overwrites a block of the wall in the
     * client whenever the real block there changes - water flowing in, grass
     * growing - and every block whose real block is no longer the one it
     * stood on is sent again.
     */
    private static final long REFRESH_TICKS = 20L;

    /**
     * Every how many looks the blocks checked are sent again whether they
     * have changed or not, in case the client has lost one of them some
     * other way.
     */
    private static final long FULL_RESEND_LOOKS = 10L;

    /**
     * How much further out a block of the wall has to lie for it to be
     * checked at one look fewer, in blocks, see {@link #dueForCheck}.
     */
    private static final double CHECK_SPREAD = 32.0;

    /**
     * How long the sections around the player may be read out of the world
     * at one look, in nanoseconds - two milliseconds of the fifty a tick has.
     * What does not fit follows in the next ticks, the farthest last; the
     * wall right in front of the player is read whatever it takes, see
     * {@link WallSections#update}.
     */
    private static final long READ_BUDGET_NANOS = 2_000_000L;

    /**
     * How far the body may move before the wall is looked at again, as a
     * share of {@code border-radius}. In between, the wall reaches that much
     * less far ahead of the player: a quarter leaves three quarters of the
     * radius to cover the time the blocks take to reach the client.
     */
    private static final double REBUILD_SHARE = 0.25;

    /** The shortest and the longest way between two looks at the wall, in blocks. */
    private static final double REBUILD_MIN = 0.1;
    private static final double REBUILD_MAX = 2.0;

    /**
     * How close the body has to come to the wall to be told why it does not
     * get any further, in blocks. The client stops it right at the surface of
     * the block; this only leaves room for rounding.
     */
    private static final double TOUCH = 0.05;

    /**
     * How much a block may lack at its sides and at its bottom and still
     * count as whole, see {@link #whole(Block)}: a sixteenth, as a cactus
     * does.
     */
    private static final double SIDE_SLIVER = 1.0 / 16.0;

    /** And how much at its top: two sixteenths, as soul sand and mud do. */
    private static final double TOP_SLIVER = 2.0 / 16.0;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, Wall> walls = new HashMap<>();
    /** Which kinds of block keep data of their own, see {@link #keepsData(Block)}. */
    private final Map<Material, Boolean> dataKinds = new HashMap<>();

    /** One block position of the wall. */
    private record Cell(int x, int y, int z) {
    }

    /**
     * What the player is shown at one block of the front of the border, and
     * what keeps the camera out of it. With neither an area nor a medium it
     * is the distance.
     *
     * @param shown  what the player sees there instead of the real block, or
     *               {@code null} where the real block keeps its place, see
     *               {@link #shownFor(Wall, Block)}
     * @param real   the real block that stood there when this was worked
     *               out: once it is another, the server has sent that one to
     *               the client and the wall has to be sent again
     * @param area   the forbidden biome or structure behind the wall, or
     *               {@code null}
     * @param medium the shut lava, water or powder snow in the block, or
     *               {@code null}
     */
    private record Brick(BlockData shown, BlockData real, String area, FlightMedium medium) {

        /** Which reason the message names when several walls meet, the highest first. */
        int rank() {
            return medium != null ? 3 : area != null ? 2 : 1;
        }
    }

    /** The wall of one player: where it stands, and how it is kept up. */
    private static final class Wall {
        /** The world the blocks below were sent into. */
        private UUID world;
        /** The front of the border within reach, see {@link Brick}. */
        private Map<Cell, Brick> bricks = new HashMap<>();
        /** Where the wall stands around the player, section by section. */
        private WallSections sections;
        /** Where the body stood when the wall was last looked at. */
        private Location builtAt;
        /** The block the wall is made of. */
        private final BlockData block;
        /** What it shows in place of water, lava and powder snow. */
        private final Map<FlightMedium, BlockData> media;
        /** Whether the forbidden areas counted at the last look, which they do not for a player standing in one. */
        private boolean areasCounted;
        private BukkitRunnable task;
        /** How many ticks the task has run so far. */
        private long ticks;
        /** The blocks to be checked until the next look, and how many of them are done. */
        private List<Cell> toCheck = List.of();
        private int checked;
        /** Whether the blocks checked until the next look are sent again in any case. */
        private boolean resending;

        Wall(World world, BlockData block, Map<FlightMedium, BlockData> media, WallSections sections) {
            this.world = world.getUID();
            this.block = block;
            this.media = media;
            this.sections = sections;
        }
    }

    public CamBorderWall(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Puts the wall up for a player who has just entered camera mode, when
     * there is one to put up: {@code border-mode: barrier} with a wall that
     * holds, and a border it holds at.
     */
    public void startFor(Player player) {
        UUID id = player.getUniqueId();
        if (walls.containsKey(id) || !settings.buildsBorderWall()
                || (!settings.limitsDistance() && !settings.keepsOutOfAreas()
                    && shutMedia().isEmpty())) {
            return;
        }
        Map<FlightMedium, BlockData> media = new EnumMap<>(FlightMedium.class);
        for (FlightMedium medium : FlightMedium.values()) {
            BlockData shown = settings.getBorderBlock(medium);
            if (medium == FlightMedium.WATER && shown instanceof Waterlogged inWater) {
                // A barrier or a pane stands in water like a fence does.
                // Without this the water would drain out of every block the
                // wall stands in.
                inWater.setWaterlogged(true);
            }
            media.put(medium, shown);
        }
        Wall wall = new Wall(player.getWorld(), settings.getBorderBlock(), media, sectionsIn(player.getWorld()));
        wall.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopFor(player);
                    return;
                }
                tick(player, wall);
            }
        };
        walls.put(id, wall);
        wall.task.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Takes the wall down when camera mode ends and gives the player every
     * block back as it really is.
     */
    public void stopFor(Player player) {
        Wall wall = walls.remove(player.getUniqueId());
        if (wall == null) {
            return;
        }
        wall.task.cancel();
        World world = player.getWorld();
        // In another world the client has let go of those blocks already,
        // and the same coordinates there belong to somewhere else.
        if (world.getUID().equals(wall.world)) {
            List<BlockState> real = new ArrayList<>();
            for (Map.Entry<Cell, Brick> brick : wall.bricks.entrySet()) {
                if (brick.getValue().shown() != null) {
                    real.add(stateAt(world, brick.getKey(), null));
                }
            }
            send(player, real);
        }
    }

    public void onDisable() {
        for (Wall wall : walls.values()) {
            wall.task.cancel();
        }
        walls.clear();
    }

    /**
     * Moves the wall along with the player, and says why the camera does not
     * get any further once the body touches the wall.
     *
     * <p>Watched last, so that only a step that really happens counts: one
     * that {@link CamMovementGuard} has cancelled leaves the player where the
     * wall was built already.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWallMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Wall wall = walls.get(player.getUniqueId());
        Location to = event.getTo();
        if (wall == null || to == null || to.getWorld() == null) {
            return;
        }
        Location from = event.getFrom();
        if (to.getX() == from.getX() && to.getY() == from.getY() && to.getZ() == from.getZ()) {
            // Only looked around.
            return;
        }
        Brick touched = touching(player, wall, to);
        if (touched != null) {
            plugin.getMovementGuard().warnAtWall(player, to, touched.area(), touched.medium());
        }
        Location last = wall.builtAt;
        double step = Math.min(REBUILD_MAX, Math.max(REBUILD_MIN, reachIn(to.getWorld()) * REBUILD_SHARE));
        if (last == null || !to.getWorld().equals(last.getWorld())
                || to.distanceSquared(last) >= step * step) {
            build(player, to, false, false);
        }
    }

    /**
     * Builds the wall anew after a teleport, once the player has arrived: a
     * lift off a happy ghast, being brought back from behind a portal, a
     * command.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWallTeleport(PlayerTeleportEvent event) {
        rebuildNextTick(event.getPlayer());
    }

    /**
     * The same after a trip through a portal, which moves the player into
     * another world without a teleport event of its own.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWallWorldChange(PlayerChangedWorldEvent event) {
        rebuildNextTick(event.getPlayer());
    }

    /**
     * Puts the wall back where the player clicked it.
     *
     * <p>The server answers every click on a block with the real block at
     * that spot - and a click it turns away, as camera mode turns them all
     * away, with the one next to it as well. In the client that takes the
     * wall down right where the player is looking at it.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWallClicked(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getAction() == Action.PHYSICAL
                || !walls.containsKey(player.getUniqueId())) {
            return;
        }
        // Answered by the server after this event, so sent a tick later.
        Bukkit.getScheduler().runTask(plugin, () -> resendAround(player, clicked));
    }

    private void rebuildNextTick(Player player) {
        if (!walls.containsKey(player.getUniqueId())) {
            return;
        }
        // Not right away: until the teleport is through the client still
        // stands at the old spot, and blocks sent into chunks it has not
        // been given yet are thrown away.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                build(player, player.getLocation(), true, false);
            }
        });
    }

    /**
     * One tick of the wall: a full look every {@link #REFRESH_TICKS}, in
     * between whatever is left of reading the sections, and a share of the
     * blocks checked against the world.
     */
    private void tick(Player player, Wall wall) {
        long tick = wall.ticks++;
        if (tick % REFRESH_TICKS == 0) {
            long look = tick / REFRESH_TICKS;
            build(player, player.getLocation(), false, true);
            wall.toCheck = dueForCheck(wall, player.getLocation(), look);
            wall.checked = 0;
            wall.resending = look > 0 && look % FULL_RESEND_LOOKS == 0;
        } else if (wall.sections.hasWork()) {
            build(player, player.getLocation(), false, false);
        }
        check(player, wall);
    }

    /**
     * The blocks of the wall to be checked until the next look: those near
     * the player at every look, those further out at every few, about one
     * more look for each {@link #CHECK_SPREAD} blocks. A block far out only
     * matters to the eye, and the player needs a while to get there.
     */
    private static List<Cell> dueForCheck(Wall wall, Location at, long look) {
        List<Cell> due = new ArrayList<>();
        for (Cell cell : wall.bricks.keySet()) {
            double dx = cell.x() + 0.5 - at.getX();
            double dy = cell.y() + 0.5 - at.getY();
            double dz = cell.z() + 0.5 - at.getZ();
            long every = 1 + (long) (Math.sqrt(dx * dx + dy * dy + dz * dz) / CHECK_SPREAD);
            if (Math.floorMod(look + cell.hashCode(), every) == 0) {
                due.add(cell);
            }
        }
        return due;
    }

    /**
     * Checks the next share of the wall against the world and sends again
     * every block whose real block has changed since it was put up: the
     * server has sent that one to the client in its place. A block whose
     * real one keeps its place is checked as well, so that the wall takes
     * that place once it is gone. Every {@link #FULL_RESEND_LOOKS} looks
     * the blocks checked are sent again in any case.
     */
    private void check(Player player, Wall wall) {
        if (wall.checked >= wall.toCheck.size()) {
            return;
        }
        World world = player.getWorld();
        if (!world.getUID().equals(wall.world)) {
            return;
        }
        int share = (int) ((wall.toCheck.size() + REFRESH_TICKS - 1) / REFRESH_TICKS);
        Set<FlightMedium> media = shutMedia();
        CamAreaRules.BlockLookup names = null;
        List<BlockState> again = new ArrayList<>();
        for (int n = 0; n < share && wall.checked < wall.toCheck.size(); n++) {
            Cell cell = wall.toCheck.get(wall.checked++);
            Brick brick = wall.bricks.get(cell);
            if (brick == null) {
                continue;
            }
            Block block = world.getBlockAt(cell.x(), cell.y(), cell.z());
            BlockData real = block.getBlockData();
            if (real.equals(brick.real())) {
                if (wall.resending && brick.shown() != null) {
                    again.add(stateAt(world, cell, brick.shown()));
                }
                continue;
            }
            if (names == null && wall.areasCounted) {
                names = settings.getCamAreaRules().blockLookup(world);
            }
            Brick now = brickAt(wall, block, real, names, media);
            wall.bricks.put(cell, now);
            if (now.shown() != null) {
                again.add(stateAt(world, cell, now.shown()));
            }
        }
        send(player, again);
    }

    /**
     * Builds the wall around this spot: gives back what is no longer part of
     * it and sends what is new, see {@link WallSections#forEachChange}. All
     * of it goes out at once, which the server packs into one packet for
     * each section of sixteen by sixteen by sixteen blocks.
     *
     * @param resend whether the blocks that stand already are sent again as
     *               well, because the client may have lost them
     * @param look   whether this is one of the full looks every
     *               {@link #REFRESH_TICKS}, see {@link WallSections#update}
     */
    private void build(Player player, Location at, boolean resend, boolean look) {
        Wall wall = walls.get(player.getUniqueId());
        CameraData data = cameraPlayers.get(player.getUniqueId());
        World world = at.getWorld();
        if (wall == null || data == null || world == null) {
            return;
        }
        if (!world.getUID().equals(wall.world)) {
            // The client has thrown the old world away with all its blocks,
            // there is nothing left to give back there.
            wall.world = world.getUID();
            wall.bricks = new HashMap<>();
            wall.sections = sectionsIn(world);
            wall.toCheck = List.of();
        }
        // What the player is to see at each block that changes: the wall,
        // or the real block where the value is null.
        Map<Cell, BlockData> changes = new HashMap<>();
        Location anchor = distanceAnchor(data, at);
        CamAreaRules rules = settings.getCamAreaRules();
        // Whoever stands in a forbidden area already may leave it in every
        // direction, the way CamMovementGuard allows it.
        wall.areasCounted = readsAreas() && rules.forbiddenArea(at) == null;
        Set<FlightMedium> media = shutMedia();
        if (anchor == null && !wall.areasCounted && media.isEmpty()) {
            // Nothing keeps the camera out around here: the wall comes down.
            for (Map.Entry<Cell, Brick> brick : wall.bricks.entrySet()) {
                if (brick.getValue().shown() != null) {
                    changes.put(brick.getKey(), null);
                }
            }
            wall.bricks = new HashMap<>();
            wall.sections = sectionsIn(world);
        } else {
            double half = player.getWidth() / 2.0;
            BoundingBox body = new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half,
                    at.getX() + half, at.getY() + player.getHeight(), at.getZ() + half);
            double reach = reachIn(world);
            wall.sections.prepare(anchor, settings.getMaxDistance(), wall.areasCounted);
            wall.sections.update(body, reach, wall.ticks, READ_BUDGET_NANOS, look);
            CamAreaRules.BlockLookup names = wall.areasCounted ? rules.blockLookup(world) : null;
            wall.sections.forEachChange(reach, new WallSections.Changes() {
                @Override
                public void entered(int x, int y, int z) {
                    remake(wall, world, new Cell(x, y, z), names, media, changes);
                }

                @Override
                public void renewed(int x, int y, int z) {
                    remake(wall, world, new Cell(x, y, z), names, media, changes);
                }

                @Override
                public void left(int x, int y, int z) {
                    Cell cell = new Cell(x, y, z);
                    Brick gone = wall.bricks.remove(cell);
                    if (gone != null && gone.shown() != null) {
                        changes.put(cell, null);
                    }
                }
            });
        }
        if (resend) {
            for (Map.Entry<Cell, Brick> brick : wall.bricks.entrySet()) {
                if (brick.getValue().shown() != null) {
                    changes.put(brick.getKey(), brick.getValue().shown());
                }
            }
        }
        List<BlockState> states = new ArrayList<>(changes.size());
        for (Map.Entry<Cell, BlockData> change : changes.entrySet()) {
            states.add(stateAt(world, change.getKey(), change.getValue()));
        }
        send(player, states);
        wall.builtAt = at.clone();
    }

    /**
     * Works out a block of the wall anew and notes what the player has to be
     * sent for it: the wall where it is new or has changed, the real block
     * where the wall gives way to it.
     */
    private void remake(Wall wall, World world, Cell cell, CamAreaRules.BlockLookup names,
                        Set<FlightMedium> media, Map<Cell, BlockData> changes) {
        Block block = world.getBlockAt(cell.x(), cell.y(), cell.z());
        Brick before = wall.bricks.get(cell);
        Brick now = brickAt(wall, block, block.getBlockData(), names, media);
        wall.bricks.put(cell, now);
        if (now.shown() != null) {
            if (before == null || !now.shown().equals(before.shown()) || !now.real().equals(before.real())) {
                changes.put(cell, now.shown());
            }
        } else if (before != null && before.shown() != null) {
            changes.put(cell, null);
        }
    }

    /** What the front of the border is at this block, see {@link Brick}. */
    private Brick brickAt(Wall wall, Block block, BlockData real, CamAreaRules.BlockLookup names,
                          Set<FlightMedium> media) {
        BlockData shown = shownFor(wall, block);
        if (shown == null) {
            return new Brick(null, real, null, null);
        }
        String area = names == null ? null : names.forbidden(block.getX(), block.getY(), block.getZ());
        FlightMedium medium = media.isEmpty() ? null : shutMediumIn(block, block.getType(), media);
        return new Brick(shown, real, area, medium);
    }

    /**
     * How far around the body the wall stands in this world, in blocks:
     * {@code camera-mode.border-radius}, but no further than the server
     * sends the world to the client. Blocks beyond that would only be thrown
     * away by it.
     */
    private double reachIn(World world) {
        return Math.min(settings.getBorderRadius(), world.getViewDistance() * 16.0);
    }

    /** What keeps the camera out of this world: the sections around one player start out empty. */
    private WallSections sectionsIn(World world) {
        return new WallSections(world, settings.getCamAreaRules(), readsAreas(), shutMedia());
    }

    /** Whether forbidden biomes or structures keep the camera out at all. */
    private boolean readsAreas() {
        return settings.keepsOutOfAreas() && settings.getCamAreaRules().forbidsBlocks();
    }

    /**
     * Where the distance is measured from, when the wall needs it around this
     * spot at all.
     *
     * <p>Not when {@code max-distance} is off, nor when there is nothing to
     * measure against or the feet are out there already: then
     * {@link CamMovementGuard} brings the player back with the next step.</p>
     */
    private Location distanceAnchor(CameraData data, Location at) {
        if (!settings.limitsDistance()) {
            return null;
        }
        Location anchor = plugin.getMovementGuard().distanceAnchor(data, at);
        if (anchor == null || at.distance(anchor) > settings.getMaxDistance()) {
            return null;
        }
        return anchor;
    }

    /** The media whose switch under {@code camera-mode} keeps the camera out. */
    private Set<FlightMedium> shutMedia() {
        Set<FlightMedium> shut = EnumSet.noneOf(FlightMedium.class);
        for (FlightMedium medium : FlightMedium.values()) {
            if (medium.isClosed(settings)) {
                shut.add(medium);
            }
        }
        return shut;
    }

    /**
     * The shut medium the block at this spot is filled with, or {@code null}
     * - the same question {@link FlightMedium#reachedInto} asks about every
     * block the body reaches into. Outside the height of the world there is
     * none.
     */
    static FlightMedium shutMediumAt(World world, int x, int y, int z, Set<FlightMedium> media) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return null;
        }
        // The kind is read first and on its own: around a camera most blocks
        // are air or stone, and neither needs a block of its own to tell.
        Material type = world.getType(x, y, z);
        return type.isAir() ? null : shutMediumIn(world.getBlockAt(x, y, z), type, media);
    }

    /** {@link #shutMediumAt} for a block whose kind has been read already. */
    private static FlightMedium shutMediumIn(Block block, Material type, Set<FlightMedium> media) {
        for (FlightMedium medium : media) {
            if (medium.fills(block, type)) {
                return medium;
            }
        }
        return null;
    }

    /**
     * What the wall shows in place of this block, or {@code null} when the
     * block stays as it is.
     *
     * <p>A whole block stays, see {@link #whole(Block)}: it stops the camera
     * on its own, and a see-through block in its place would open a window
     * into the ground. Everything else gives way to the wall - air, plants,
     * slabs, fences, pointed dripstone - and water, lava and powder snow to
     * the block of their own, see {@code camera-mode.border-block-water},
     * {@code -lava} and {@code -powder-snow}.</p>
     *
     * <p>Two kinds stay nevertheless and leave a gap, which the checks of
     * {@link CamMovementGuard} close instead: fire, which
     * {@link de.elia.cameraplugin.fire.CamFireGuard} hides from the camera
     * already, and every block that keeps data of its own - a sign keeps its
     * text, a banner its pattern, a head its skin. The client forgets all of
     * that as soon as another block has stood in the place. A gap of a single
     * block lets nobody through anyway.</p>
     */
    private BlockData shownFor(Wall wall, Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return wall.block;
        }
        if (whole(block) || type == Material.FIRE || type == Material.SOUL_FIRE || keepsData(block)) {
            return null;
        }
        for (Map.Entry<FlightMedium, BlockData> medium : wall.media.entrySet()) {
            if (medium.getKey().fills(block, type)) {
                return medium.getValue();
            }
        }
        return wall.block;
    }

    /**
     * Whether this block fills its space, or so nearly that nobody gets past
     * what is left of it: stone, glass, leaves, stairs - and soul sand, mud,
     * farmland, a cactus, which lack a sliver at the top or at the sides but
     * look whole all the same.
     */
    private static boolean whole(Block block) {
        Collection<BoundingBox> parts = block.getCollisionShape().getBoundingBoxes();
        if (parts.isEmpty()) {
            return false;
        }
        double minX = 1.0;
        double minY = 1.0;
        double minZ = 1.0;
        double maxX = 0.0;
        double maxY = 0.0;
        double maxZ = 0.0;
        for (BoundingBox part : parts) {
            minX = Math.min(minX, part.getMinX());
            minY = Math.min(minY, part.getMinY());
            minZ = Math.min(minZ, part.getMinZ());
            maxX = Math.max(maxX, part.getMaxX());
            maxY = Math.max(maxY, part.getMaxY());
            maxZ = Math.max(maxZ, part.getMaxZ());
        }
        return minX <= SIDE_SLIVER && maxX >= 1.0 - SIDE_SLIVER
                && minZ <= SIDE_SLIVER && maxZ >= 1.0 - SIDE_SLIVER
                && minY <= SIDE_SLIVER && maxY >= 1.0 - TOP_SLIVER;
    }

    /**
     * Whether blocks of this kind keep data of their own, as a sign keeps its
     * text. Asked once per kind of block and then kept: the answer does not
     * change, and it takes a copy of the block to find out.
     */
    private boolean keepsData(Block block) {
        return dataKinds.computeIfAbsent(block.getType(), kind -> block.getState() instanceof TileState);
    }

    /**
     * The block of the wall the body touches at this spot, or {@code null}.
     * A shut medium goes first, then a forbidden area, then the distance -
     * the order in which {@link CamMovementGuard} asks about a step.
     */
    private Brick touching(Player player, Wall wall, Location at) {
        if (wall.bricks.isEmpty() || !at.getWorld().getUID().equals(wall.world)) {
            return null;
        }
        double half = player.getWidth() / 2.0 + TOUCH;
        int x0 = Location.locToBlock(at.getX() - half);
        int x1 = Location.locToBlock(at.getX() + half);
        int y0 = Location.locToBlock(at.getY() - TOUCH);
        int y1 = Location.locToBlock(at.getY() + player.getHeight() + TOUCH);
        int z0 = Location.locToBlock(at.getZ() - half);
        int z1 = Location.locToBlock(at.getZ() + half);
        Brick found = null;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Brick brick = wall.bricks.get(new Cell(x, y, z));
                    if (brick != null && brick.shown() != null && (found == null || brick.rank() > found.rank())) {
                        found = brick;
                    }
                }
            }
        }
        return found;
    }

    /** Sends the blocks of the wall around a clicked block once more. */
    private void resendAround(Player player, Block clicked) {
        Wall wall = walls.get(player.getUniqueId());
        World world = clicked.getWorld();
        if (wall == null || !player.isOnline() || !world.getUID().equals(wall.world)) {
            return;
        }
        List<BlockState> again = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Cell cell = new Cell(clicked.getX() + dx, clicked.getY() + dy, clicked.getZ() + dz);
                    Brick brick = wall.bricks.get(cell);
                    if (brick != null && brick.shown() != null) {
                        again.add(stateAt(world, cell, brick.shown()));
                    }
                }
            }
        }
        send(player, again);
    }

    /**
     * The block at this cell the way the player is to see it: with
     * {@code shown} in its place, or as it really is when that is
     * {@code null}.
     */
    private static BlockState stateAt(World world, Cell cell, BlockData shown) {
        BlockState state = world.getBlockAt(cell.x(), cell.y(), cell.z()).getState();
        if (shown != null) {
            state.setBlockData(shown);
        }
        return state;
    }

    /** Sends these blocks to the player alone, all in one go. */
    private static void send(Player player, Collection<BlockState> blocks) {
        if (!blocks.isEmpty()) {
            player.sendBlockChanges(blocks);
        }
    }
}
