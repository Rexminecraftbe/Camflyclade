package de.elia.cameraplugin.movement;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The wall of {@code border-mode: barrier}: blocks of
 * {@code camera-mode.border-block} along the border of camera mode, which
 * only the camera player has.
 *
 * <p>They are sent to that one client with {@link Player#sendBlockChange} and
 * nowhere else. The world stays as it is: every other player sees what stands
 * there, and so does the server. The client runs into them like into any
 * block of the world, so the camera stops at the border by itself - no step
 * is cancelled, which is what makes the camera jerk back under
 * {@code push-back}.</p>
 *
 * <p>Two borders are built: {@code camera-mode.max-distance} around the body
 * (or the portal the player came out of, see
 * {@link CamMovementGuard#distanceAnchor}), and the biomes and structures
 * {@code cam-area} keeps the camera out of on level 2. The wall never reaches
 * past what those two allow: a block of it is set wherever the body would be
 * too far away or in a forbidden block if it touched that block at all.
 * {@link CamMovementGuard} keeps its own checks behind it, which a player who
 * stays in front of the wall can therefore never set off. They are left for a
 * client that ignores the wall - and for the few gaps it cannot close, see
 * {@link #holdsWall(Block)}.</p>
 *
 * <p>Only the front of the border is built, the blocks next to the space the
 * camera may use, and of those only the ones up to
 * {@code camera-mode.border-radius} away from the body. The wall moves along
 * with the player, and every block it leaves behind is given back as it
 * really is.</p>
 */
public final class CamBorderWall implements Listener {

    /**
     * Ticks between two full looks at the wall, which also send every block of
     * it again. The server overwrites a block of the wall in the client
     * whenever the real block there changes - water flowing in, grass growing
     * - and this puts it back.
     */
    private static final long REFRESH_TICKS = 20L;

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
     * How far past {@code border-radius} the blocks are sorted into open and
     * shut, in blocks: far enough for every neighbour of a block within reach,
     * which lies at most the diagonal of a block further out.
     */
    private static final double NEIGHBOUR_REACH = 2.0;

    /**
     * How close the body has to come to the wall to be told why it does not
     * get any further, in blocks. The client stops it right at the surface of
     * the block; this only leaves room for rounding.
     */
    private static final double TOUCH = 0.05;

    /**
     * How far past {@code border-radius} a block may lie from the feet and
     * still come within reach of the body, in blocks: the height of the body
     * and the diagonal of the block itself, generously rounded up. Only used
     * to skip the distance while the player is far inside it.
     */
    private static final double BODY_AND_BLOCK = 4.0;

    /**
     * How deep a block may reach into the body before it counts as standing
     * in it, in blocks. A body touching a block from outside is not in it.
     */
    private static final double OVERLAP_MARGIN = 1.0E-3;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, Wall> walls = new HashMap<>();

    /** One block position of the wall. */
    private record Cell(int x, int y, int z) {
    }

    /**
     * What the player is shown at one block of the wall, and what keeps the
     * camera out of it.
     *
     * @param area the forbidden biome or structure behind the wall, or
     *             {@code null} when it is the distance
     */
    private record Brick(BlockData shown, String area) {
    }

    /** The wall of one player: where it stands, and how it is kept up. */
    private static final class Wall {
        /** The world the blocks below were sent into. */
        private UUID world;
        private Map<Cell, Brick> bricks = new HashMap<>();
        /** Where the body stood when the wall was last looked at. */
        private Location builtAt;
        /** The block the wall is made of, and the same block standing in water. */
        private final BlockData dry;
        private final BlockData wet;
        private BukkitRunnable task;

        Wall(UUID world, BlockData dry, BlockData wet) {
            this.world = world;
            this.dry = dry;
            this.wet = wet;
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
                || (!settings.limitsDistance() && !settings.keepsOutOfAreas())) {
            return;
        }
        BlockData dry = settings.getBorderBlock();
        BlockData wet = dry;
        if (dry instanceof Waterlogged) {
            // A barrier stands in water like a fence does. Without this the
            // water would drain out of every block the wall stands in.
            Waterlogged inWater = (Waterlogged) dry.clone();
            inWater.setWaterlogged(true);
            wet = inWater;
        }
        Wall wall = new Wall(player.getWorld().getUID(), dry, wet);
        wall.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopFor(player);
                    return;
                }
                build(player, player.getLocation(), true);
            }
        };
        walls.put(id, wall);
        wall.task.runTaskTimer(plugin, 1L, REFRESH_TICKS);
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
            for (Cell cell : wall.bricks.keySet()) {
                restore(player, world, cell);
            }
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
            plugin.getMovementGuard().warnAtWall(player, to, touched.area());
        }
        Location last = wall.builtAt;
        double step = Math.min(REBUILD_MAX, Math.max(REBUILD_MIN, settings.getBorderRadius() * REBUILD_SHARE));
        if (last == null || !to.getWorld().equals(last.getWorld())
                || to.distanceSquared(last) >= step * step) {
            build(player, to, false);
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
                build(player, player.getLocation(), true);
            }
        });
    }

    /**
     * Builds the wall around this spot: gives back what is no longer part of
     * it and sends what is new.
     *
     * @param resend whether the blocks that stand already are sent again as
     *               well, in case the client has lost them
     */
    private void build(Player player, Location at, boolean resend) {
        Wall wall = walls.get(player.getUniqueId());
        CameraData data = cameraPlayers.get(player.getUniqueId());
        World world = at.getWorld();
        if (wall == null || data == null || world == null) {
            return;
        }
        if (!world.getUID().equals(wall.world)) {
            // Der Client hat die alte Welt samt allen Bloecken darin
            // weggeworfen, dort ist nichts mehr zurueckzugeben.
            wall.world = world.getUID();
            wall.bricks = new HashMap<>();
        }
        Map<Cell, Brick> wanted = plan(player, wall, data, at);
        for (Cell cell : wall.bricks.keySet()) {
            if (!wanted.containsKey(cell)) {
                restore(player, world, cell);
            }
        }
        for (Map.Entry<Cell, Brick> brick : wanted.entrySet()) {
            Brick before = wall.bricks.get(brick.getKey());
            if (resend || before == null || !before.shown().equals(brick.getValue().shown())) {
                show(player, world, brick.getKey(), brick.getValue().shown());
            }
        }
        wall.bricks = wanted;
        wall.builtAt = at.clone();
    }

    /**
     * Works out which blocks around this spot belong to the wall.
     *
     * <p>A block is shut when the body would be too far away or in a
     * forbidden area as soon as it touched it; of those, the wall takes the
     * ones next to a block that is not shut - the front of the border - up to
     * {@code border-radius} away from the body. The blocks deeper in cannot be
     * reached without going through the front first.</p>
     */
    private Map<Cell, Brick> plan(Player player, Wall wall, CameraData data, Location at) {
        Map<Cell, Brick> wanted = new HashMap<>();
        World world = at.getWorld();
        double reach = settings.getBorderRadius();
        Location anchor = distanceAnchor(data, at, reach);
        CamAreaRules rules = settings.getCamAreaRules();
        CamAreaRules.BlockLookup areas = settings.keepsOutOfAreas() && rules.forbidsBlocks()
                // Whoever stands in a forbidden area already may leave it in
                // every direction, the way CamMovementGuard allows it.
                && rules.forbiddenArea(at) == null ? rules.blockLookup(world) : null;
        if (anchor == null && areas == null) {
            return wanted;
        }
        double maxSquared = settings.getMaxDistance() * settings.getMaxDistance();
        double half = player.getWidth() / 2.0;
        double minX = at.getX() - half;
        double maxX = at.getX() + half;
        double minY = at.getY();
        double maxY = at.getY() + player.getHeight();
        double minZ = at.getZ() - half;
        double maxZ = at.getZ() + half;

        int x0 = Location.locToBlock(minX - reach);
        int y0 = Location.locToBlock(minY - reach);
        int z0 = Location.locToBlock(minZ - reach);
        int x1 = Location.locToBlock(maxX + reach);
        int y1 = Location.locToBlock(maxY + reach);
        int z1 = Location.locToBlock(maxZ + reach);
        // One block more on every side: whether a block of the front touches
        // open space is a question about its neighbours.
        int sizeX = x1 - x0 + 3;
        int sizeY = y1 - y0 + 3;
        int sizeZ = z1 - z0 + 3;
        boolean[] shut = new boolean[sizeX * sizeY * sizeZ];
        String[] areaOf = new String[shut.length];
        double sortedSquared = (reach + NEIGHBOUR_REACH) * (reach + NEIGHBOUR_REACH);
        for (int gx = 0; gx < sizeX; gx++) {
            int x = x0 - 1 + gx;
            double gapX = gap(x, minX, maxX);
            for (int gz = 0; gz < sizeZ; gz++) {
                int z = z0 - 1 + gz;
                double gapZ = gap(z, minZ, maxZ);
                for (int gy = 0; gy < sizeY; gy++) {
                    int y = y0 - 1 + gy;
                    double gapY = gap(y, minY, maxY);
                    if (gapX * gapX + gapY * gapY + gapZ * gapZ > sortedSquared) {
                        // A corner of the box, neighbour of no block within
                        // reach: nobody asks about it.
                        continue;
                    }
                    int i = (gx * sizeZ + gz) * sizeY + gy;
                    String area = areas == null ? null : areas.forbidden(x, y, z);
                    areaOf[i] = area;
                    shut[i] = area != null || (anchor != null && tooFar(anchor, maxSquared, x, y, z));
                }
            }
        }

        int bottom = Math.max(y0, world.getMinHeight());
        int top = Math.min(y1, world.getMaxHeight() - 1);
        double reachSquared = reach * reach;
        for (int x = x0; x <= x1; x++) {
            double gapX = gap(x, minX, maxX);
            for (int z = z0; z <= z1; z++) {
                double gapZ = gap(z, minZ, maxZ);
                for (int y = bottom; y <= top; y++) {
                    int gx = x - x0 + 1;
                    int gy = y - y0 + 1;
                    int gz = z - z0 + 1;
                    int i = (gx * sizeZ + gz) * sizeY + gy;
                    if (!shut[i]) {
                        continue;
                    }
                    double gapY = gap(y, minY, maxY);
                    if (gapX * gapX + gapY * gapY + gapZ * gapZ > reachSquared) {
                        continue;
                    }
                    if (inBody(x, minX, maxX) && inBody(y, minY, maxY) && inBody(z, minZ, maxZ)) {
                        // The body is in this block already, which only a
                        // teleport or a client that ignored the wall can
                        // have done. A block around it would lock it in.
                        continue;
                    }
                    if (!touchesOpen(shut, sizeY, sizeZ, gx, gy, gz)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!holdsWall(block)) {
                        continue;
                    }
                    BlockData shown = FlightMedium.WATER.fills(block) ? wall.wet : wall.dry;
                    wanted.put(new Cell(x, y, z), new Brick(shown, areaOf[i]));
                }
            }
        }
        return wanted;
    }

    /**
     * Where the distance is measured from, when the wall needs it around this
     * spot at all.
     *
     * <p>Not when {@code max-distance} is off, nor when there is nothing to
     * measure against or the feet are out there already: then
     * {@link CamMovementGuard} brings the player back with the next step. Nor
     * while the player is so far inside that no block within reach can lie
     * beyond the border.</p>
     */
    private Location distanceAnchor(CameraData data, Location at, double reach) {
        if (!settings.limitsDistance()) {
            return null;
        }
        Location anchor = plugin.getMovementGuard().distanceAnchor(data, at);
        if (anchor == null) {
            return null;
        }
        double max = settings.getMaxDistance();
        double away = at.distance(anchor);
        if (away > max || away + reach + BODY_AND_BLOCK < max) {
            return null;
        }
        return anchor;
    }

    /**
     * Whether any corner of this block lies beyond {@code max-distance}.
     * Measured at the corner farthest away and not at the middle, so that the
     * feet cannot get past the border either while the body keeps out of every
     * such block.
     */
    private static boolean tooFar(Location anchor, double maxSquared, int x, int y, int z) {
        double dx = Math.max(Math.abs(x - anchor.getX()), Math.abs(x + 1 - anchor.getX()));
        double dy = Math.max(Math.abs(y - anchor.getY()), Math.abs(y + 1 - anchor.getY()));
        double dz = Math.max(Math.abs(z - anchor.getZ()), Math.abs(z + 1 - anchor.getZ()));
        return dx * dx + dy * dy + dz * dz > maxSquared;
    }

    /** How far the block at this coordinate lies from the body along one axis. */
    private static double gap(int block, double min, double max) {
        return Math.max(0.0, Math.max(block - max, min - (block + 1)));
    }

    /** Whether the block at this coordinate reaches into the body along one axis. */
    private static boolean inBody(int block, double min, double max) {
        return block < max - OVERLAP_MARGIN && block + 1 > min + OVERLAP_MARGIN;
    }

    /** Whether any of the 26 blocks around this one is open. */
    private static boolean touchesOpen(boolean[] shut, int sizeY, int sizeZ, int gx, int gy, int gz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!shut[((gx + dx) * sizeZ + gz + dz) * sizeY + gy + dy]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether a block of the wall may stand in place of this one: only where
     * nothing stops the body yet, and only where the player loses nothing by
     * it.
     *
     * <p>Everything solid stays as it is. It stops the camera on its own, and
     * a see-through block in its place would open a window into the ground.
     * Of what can be flown through, four kinds stay as well, and leave a gap
     * the checks of {@link CamMovementGuard} close instead: whatever gives
     * light, whose glow would go out around the camera; powder snow, which
     * hides what lies behind it; and signs and banners, which lose their text
     * and their pattern in the client once another block has stood in their
     * place.</p>
     */
    private static boolean holdsWall(Block block) {
        if (!block.isPassable()) {
            return false;
        }
        BlockData data = block.getBlockData();
        Material type = data.getMaterial();
        return data.getLightEmission() == 0
                && type != Material.POWDER_SNOW
                && !Tag.ALL_SIGNS.isTagged(type)
                && !Tag.BANNERS.isTagged(type);
    }

    /**
     * The block of the wall the body touches at this spot, or {@code null}.
     * A forbidden area goes before the distance, as it does in
     * {@link CamMovementGuard}.
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
                    if (brick != null && (found == null || brick.area() != null)) {
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
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Cell cell = new Cell(clicked.getX() + dx, clicked.getY() + dy, clicked.getZ() + dz);
                    Brick brick = wall.bricks.get(cell);
                    if (brick != null) {
                        show(player, world, cell, brick.shown());
                    }
                }
            }
        }
    }

    private static void show(Player player, World world, Cell cell, BlockData shown) {
        player.sendBlockChange(new Location(world, cell.x(), cell.y(), cell.z()), shown);
    }

    /** Gives the player the real block back. */
    private static void restore(Player player, World world, Cell cell) {
        Block real = world.getBlockAt(cell.x(), cell.y(), cell.z());
        player.sendBlockChange(real.getLocation(), real.getBlockData());
    }
}
