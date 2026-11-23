package de.elia.cameraplugin.signal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link SignalPath}: how strong the signal of {@code max-distance-mode: signal}
 * arrives, and which blocks between body and camera it goes through.
 *
 * <p>Without a server, a {@link Material} cannot say whether it is a block or
 * whether it can be seen through - that needs the registries of the server.
 * The costs are therefore given for every block the test world holds, so
 * that {@link SignalCosts#of} never has to ask.</p>
 */
class SignalPathTest {

    private static final double RANGE = 100.0;
    private static final double WALL = 5.0;
    private static final double DELTA = 1e-9;

    private final TestWorld world = new TestWorld();
    private final SignalCosts costs = costs(WALL, Map.of(
            Material.AIR, 0.0,
            Material.STONE, WALL,
            Material.GLASS, 0.0,
            Material.BEDROCK, SignalCosts.STOP));

    @Test
    void inTheOpenTheSignalFadesWithTheDistance() {
        assertEquals(0.9, strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5)), DELTA);
        assertEquals(1.0, strength(at(0.5, 64.5, 0.5), at(0.5, 64.5, 0.5)), DELTA);
    }

    @Test
    void beyondTheRangeThereIsNoSignal() {
        assertEquals(0.0, strength(at(0.5, 64.5, 0.5), at(150.5, 64.5, 0.5)), DELTA);
        assertEquals(0.0, SignalPath.strength(at(0.5, 64.5, 0.5), at(1.5, 64.5, 0.5), 0.0, costs), DELTA);
    }

    @Test
    void wallTakesItsCostOffTheRange() {
        world.set(5, 64, 0, Material.STONE);

        assertEquals((RANGE - 10 - WALL) / RANGE, strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5)), DELTA);
    }

    @Test
    void whatCanBeSeenThroughCostsNothing() {
        world.set(5, 64, 0, Material.GLASS);

        assertEquals(0.9, strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5)), DELTA);
    }

    @Test
    void stopCutsTheSignalOff() {
        world.set(5, 64, 0, Material.BEDROCK);

        assertEquals(0.0, strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5)), DELTA);
    }

    @Test
    void blocksOfBodyAndCameraDoNotCount() {
        world.set(0, 64, 0, Material.STONE);
        world.set(10, 64, 0, Material.STONE);

        assertEquals(0.9, strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5)), DELTA);
    }

    @Test
    void blockInAChunkThatIsNotLoadedCostsNothing() {
        world.set(20, 64, 0, Material.STONE);
        Location body = at(0.5, 64.5, 0.5);
        Location camera = at(40.5, 64.5, 0.5);

        assertEquals(0.55, strength(body, camera), DELTA);
        world.unload(1, 0);
        assertEquals(0.6, strength(body, camera), DELTA);
    }

    @Test
    void nothingAboveOrBelowTheWorldIsLookedAt() {
        world.fill(Material.STONE);

        // From just below the top of the world to far above it.
        double cost = SignalPath.costBetween(at(0.5, 318.5, 0.5), at(3.5, 330.5, 0.5), costs, Double.MAX_VALUE);

        for (int[] block : world.looked) {
            assertTrue(block[1] >= TestWorld.MIN_Y && block[1] < TestWorld.MAX_Y, "looked at y " + block[1]);
        }
        assertEquals(WALL * world.looked.size(), cost, DELTA);
    }

    @Test
    void countingStopsOnceTheRangeIsUsedUp() {
        world.fill(Material.STONE);

        double cost = SignalPath.costBetween(at(0.5, 64.5, 0.5), at(20.5, 64.5, 0.5), costs, 12.0);

        assertEquals(3 * WALL, cost, DELTA);
    }

    @Test
    void freeSignalLooksAtNoBlock() {
        SignalCosts free = costs(0.0, Map.of(Material.AIR, 0.0));
        world.fill(Material.STONE);

        assertEquals(0.9, SignalPath.strength(at(0.5, 64.5, 0.5), at(10.5, 64.5, 0.5), RANGE, free), DELTA);
        assertEquals(List.of(), world.looked);
    }

    /**
     * On lines in every direction: each block in between is looked at once,
     * each is next to the one before - side by side, not just at a corner -,
     * from the block of the body to the block of the camera, and the line
     * really passes through each.
     */
    @Test
    void lineGoesThroughEveryBlockOnItsWayOnce() {
        Random random = new Random(20261123L);
        for (int run = 0; run < 500; run++) {
            world.looked.clear();
            Location from = at(random.nextDouble() * 24 - 12, 64 + random.nextDouble() * 24 - 12,
                    random.nextDouble() * 24 - 12);
            Location to = at(random.nextDouble() * 24 - 12, 64 + random.nextDouble() * 24 - 12,
                    random.nextDouble() * 24 - 12);

            SignalPath.costBetween(from, to, costs, Double.MAX_VALUE);

            String line = "line " + run + " from " + describe(from) + " to " + describe(to);
            List<int[]> path = new ArrayList<>();
            path.add(new int[]{from.getBlockX(), from.getBlockY(), from.getBlockZ()});
            path.addAll(world.looked);
            path.add(new int[]{to.getBlockX(), to.getBlockY(), to.getBlockZ()});
            if (path.getFirst()[0] == path.getLast()[0] && path.getFirst()[1] == path.getLast()[1]
                    && path.getFirst()[2] == path.getLast()[2]) {
                assertEquals(List.of(), world.looked, line);
                continue;
            }
            Set<List<Integer>> seen = new HashSet<>();
            for (int i = 0; i < path.size(); i++) {
                int[] block = path.get(i);
                assertTrue(seen.add(List.of(block[0], block[1], block[2])), line + ": a block twice");
                if (i > 0) {
                    int[] before = path.get(i - 1);
                    int apart = Math.abs(block[0] - before[0]) + Math.abs(block[1] - before[1])
                            + Math.abs(block[2] - before[2]);
                    assertEquals(1, apart, line + ": a gap after block " + i);
                }
                if (!passesThrough(from, to, block)) {
                    fail(line + ": does not pass through block " + List.of(block[0], block[1], block[2]));
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private double strength(Location from, Location to) {
        return SignalPath.strength(from, to, RANGE, costs);
    }

    private Location at(double x, double y, double z) {
        return new Location(world.world, x, y, z);
    }

    private static String describe(Location at) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", at.getX(), at.getY(), at.getZ());
    }

    /** Whether the line touches the block, by the slab test, a little generously for the edges. */
    private static boolean passesThrough(Location from, Location to, int[] block) {
        double[] start = {from.getX(), from.getY(), from.getZ()};
        double[] way = {to.getX() - start[0], to.getY() - start[1], to.getZ() - start[2]};
        double enter = 0.0;
        double leave = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double low = block[axis] - 1e-7;
            double high = block[axis] + 1 + 1e-7;
            if (way[axis] == 0.0) {
                if (start[axis] < low || start[axis] > high) {
                    return false;
                }
                continue;
            }
            double a = (low - start[axis]) / way[axis];
            double b = (high - start[axis]) / way[axis];
            enter = Math.max(enter, Math.min(a, b));
            leave = Math.min(leave, Math.max(a, b));
        }
        return enter <= leave;
    }

    /**
     * Costs as {@code signal.block-costs} would give them. {@link SignalCosts#read}
     * would ask each block whether it is one, which needs a server.
     */
    private static SignalCosts costs(double occluding, Map<Material, Double> listed) {
        try {
            Constructor<SignalCosts> constructor = SignalCosts.class.getDeclaredConstructor(double.class, Map.class);
            constructor.setAccessible(true);
            return constructor.newInstance(occluding, new EnumMap<>(listed));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("SignalCosts can no longer be made the way this test makes it", ex);
        }
    }

    /** A world of air between y -64 and 320, with what a test puts into it. */
    private static final class TestWorld {
        static final int MIN_Y = -64;
        static final int MAX_Y = 320;

        final Map<List<Integer>, Material> blocks = new HashMap<>();
        final Set<List<Integer>> unloaded = new HashSet<>();
        /** Every block whose type was asked for, in order. */
        final List<int[]> looked = new ArrayList<>();
        Material everywhere = Material.AIR;
        final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, this::answer);

        void set(int x, int y, int z, Material type) {
            blocks.put(List.of(x, y, z), type);
        }

        void fill(Material type) {
            everywhere = type;
        }

        void unload(int chunkX, int chunkZ) {
            unloaded.add(List.of(chunkX, chunkZ));
        }

        private Object answer(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getMinHeight":
                    return MIN_Y;
                case "getMaxHeight":
                    return MAX_Y;
                case "isChunkLoaded":
                    if (args != null && args.length == 2 && args[0] instanceof Integer x) {
                        return !unloaded.contains(List.of(x, (Integer) args[1]));
                    }
                    break;
                case "getType":
                    if (args != null && args.length == 3 && args[0] instanceof Integer x) {
                        int y = (Integer) args[1];
                        int z = (Integer) args[2];
                        looked.add(new int[]{x, y, z});
                        return blocks.getOrDefault(List.of(x, y, z), everywhere);
                    }
                    break;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "TestWorld";
                default:
                    break;
            }
            throw new UnsupportedOperationException("the test world cannot " + method);
        }
    }
}
