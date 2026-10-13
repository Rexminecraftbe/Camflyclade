package de.elia.cameraplugin.movement;

import de.elia.cameraplugin.area.CamAreaRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the wall of {@link CamBorderWall} stands around one camera player,
 * worked out section by section - sixteen by sixteen by sixteen blocks, the
 * way the world itself is cut up - and kept, so that a step of the player
 * only adds what has come into reach.
 *
 * <p>A block is shut when the body would be too far away, in a forbidden
 * area or in shut lava, water or powder snow as soon as it touched it. Only
 * the last two depend on the world, so only they are read out of it: once
 * for each section, and again once it has grown old, see
 * {@link #maxAge(double)}. The distance is pure arithmetic and is worked out
 * when it is asked, kept for as long as the spot it is measured from stays
 * where it is.</p>
 *
 * <p>The wall itself is the front of the shut blocks: each one next to a
 * block the camera can be in, which is neither shut nor whole, see
 * {@link WholeBlocks}. Where the border runs through rock, or along the
 * bottom of a lake, nothing stands next to it that the camera could come
 * from, and no wall is built there. That front is kept for every section as
 * well, until something in or around it changes, and looked over again
 * every so often, see {@link #FRONT_AGE_FACTOR}: a block broken or set next
 * to it changes it as well.</p>
 *
 * <p>Most sections hold no border at all: they lie well inside
 * {@code max-distance} or far out beyond it, and hold neither a forbidden
 * area nor a shut medium. Those cost next to nothing, so that the work grows
 * with the size of the border in reach and not with the room around the
 * player.</p>
 *
 * <p>A section is kept as one entry for each of its columns, at
 * {@code x * 16 + z}, with one bit for each block from its bottom up. No
 * array stands for a section with no such block, {@link #FULL} for one made
 * of nothing else.</p>
 */
final class WallSections {

    /**
     * How far past the reach of the wall the blocks are sorted into open and
     * shut, in blocks: far enough for every neighbour of a block within
     * reach, which lies at most the diagonal of a block further out.
     */
    private static final double NEIGHBOUR_REACH = 2.0;

    /**
     * How deep a block may reach into the body before it counts as standing
     * in it, in blocks. A body touching a block from outside is not in it.
     */
    private static final double OVERLAP_MARGIN = 1.0E-3;

    /**
     * How long a section is kept before it is read out of the world again,
     * in ticks, at the least - for the sections right around the player, so
     * that water flowing in counts as soon as it used to. Further out it is
     * kept longer, see {@link #maxAge(double)}.
     */
    private static final long MIN_AGE = 20L;

    /**
     * How many times longer the forbidden areas of a section are kept than
     * its media. Water flows, lava spreads - a biome changes only by a
     * command, and a structure never: it is placed once, when its chunk is
     * made.
     */
    private static final long AREA_AGE_FACTOR = 10L;

    /**
     * How much faster the time a front is kept grows with the distance than
     * that of the media, see {@link Section#edge}: as if it lay that many
     * times further out. A block broken or set next to the wall matters to
     * the camera only near it - further out the wall is only there to be
     * seen, and is looked over again long before the player gets there. A
     * second right around the player, ten at 32 blocks, about forty at 64.
     */
    private static final double FRONT_AGE_FACTOR = 2.0;

    /**
     * How the time a section is kept grows past {@link #ALWAYS_REACH}: by
     * one tick for every so many blocks of the distance squared. A minute at
     * 160 blocks, about ten seconds at 64, three at 40.
     */
    private static final double AGE_SPREAD = 16.0;

    /**
     * How close a section has to lie to the body to be read out of the world
     * whatever the time it takes, in blocks. The wall right in front of the
     * player has to stand; the far parts of a large wall are only there to be
     * seen and may follow a few ticks later.
     */
    private static final double ALWAYS_REACH = 12.0;

    /**
     * How far the body may move before the sections around it are looked
     * over again, in blocks, at the most: a quarter of the reach below that,
     * so that a small wall does not read sections it will never get to.
     * Until then those up to that much past the reach count as within it:
     * what lies beyond the reach is sorted out block by block anyway, see
     * {@link #withinReach}.
     */
    private static final double SCAN_MARGIN = 4.0;

    /** How far past what is needed a section stays kept before it is let go, in blocks. */
    private static final double KEEP_MARGIN = 16.0;

    /**
     * The 26 blocks around one, the six beside it first, the one above it
     * leading: where the camera can be next to a wall it mostly is right
     * above it or beside it, and the search ends at the first such block.
     */
    private static final int[][] NEIGHBOURS = neighbours();

    /** A section made of nothing but such blocks. Never written to. */
    private static final char[] FULL = new char[256];

    /** The front of a section that cannot be worked out yet, see {@link #front(Pos, Section)}. */
    private static final char[] UNKNOWN = new char[0];

    static {
        Arrays.fill(FULL, (char) 0xFFFF);
    }

    /** One section, counted in sections. */
    private record Pos(int x, int y, int z) {

        // Written out: the ones a record brings along go through a general
        // lookup, and every look asks the maps thousands of times.
        @Override
        public boolean equals(Object other) {
            return other instanceof Pos pos && pos.x == x && pos.y == y && pos.z == z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    /** One section and what has been worked out about it. */
    private static final class Section {
        /** The blocks of forbidden biomes and structures, see {@link CamAreaRules.BlockLookup#markSection}. */
        private char[] area;
        /** The blocks of shut lava, water and powder snow. */
        private char[] medium;
        /** When those two were read out of the world, see {@link #update}. */
        private long areaReadAt;
        private long mediumReadAt;
        /** The blocks beyond {@code max-distance}, once worked out. */
        private char[] distance;
        private boolean distanceKnown;
        /** The front of the shut blocks, the wall, once worked out. */
        private char[] front;
        private boolean frontKnown;
        /** When the front was last looked over, see {@link #scan}. */
        private long frontAt;
        /**
         * The shut blocks touching open ones, whole or not, and the open
         * blocks around the section they were worked out from. Kept, so that
         * the front can be looked over again from them alone, see
         * {@link #reachable}: whether a block next to the wall is whole can
         * change without anything else changing.
         */
        private char[] edge;
        private int[] open;
        /** Whether the front is due to be looked over again. */
        private boolean recheck;
    }

    /**
     * A section to be read out of the world, how far it lies from the body,
     * and which of its parts: all of it when it is new.
     */
    private record Pending(Pos pos, double gap, boolean area, boolean medium) {
    }

    /** Receives what has changed in the wall, see {@link #forEachChange}. */
    interface Changes {
        /** A block that has joined the wall. */
        void entered(int x, int y, int z);

        /**
         * A block that stays in the wall, but whose section has been read
         * anew with another result: what was made for it has to be made
         * anew.
         */
        void renewed(int x, int y, int z);

        /** A block that is no longer part of the wall. */
        void left(int x, int y, int z);
    }

    private final World world;
    private final CamAreaRules rules;
    private final WholeBlocks wholeBlocks;
    /** Whether forbidden areas are looked for at all, see {@link #areasCounted}. */
    private final boolean areasRead;
    private final Set<FlightMedium> media;
    private final Map<Pos, Section> sections = new HashMap<>();

    /** The sections still to be read out of the world, the nearest first. */
    private List<Pending> pending = new ArrayList<>();
    /** The sections that may hold blocks within reach, see {@link #scan}. */
    private final List<Pos> inReach = new ArrayList<>();
    private final Set<Pos> inReachSet = new HashSet<>();
    /**
     * The blocks of the wall handed out so far, for each section: what
     * {@link #forEachChange} compares against. Kept apart from the sections,
     * which may be let go before their blocks have left the wall.
     */
    private final Map<Pos, char[]> handed = new HashMap<>();
    /** The sections read out anew with another result since they were last handed out. */
    private final Set<Pos> renewed = new HashSet<>();
    /** Whether everything is to be handed out as renewed, see {@link #prepare}. */
    private boolean allRenewed = true;

    /** The body at the last look, and how far the sections are needed around it. */
    private BoundingBox body;
    private double needed;
    /** Where the body stood at the last {@link #scan}, and the reach then. */
    private BoundingBox scannedAt;
    private double scannedReach;
    /** The tick counter of the wall at the last {@link #update}. */
    private long now;

    /** The spot the distance is measured from, or {@code null}, and how far it reaches. */
    private Location anchor;
    private double maxDistance;
    /** Whether the forbidden areas count right now, which they do not for a player standing in one. */
    private boolean areasCounted;

    /**
     * @param areasRead whether forbidden areas keep the camera out at all:
     *                  {@code cam-area} on level 2, with a biome or structure
     *                  to look for
     * @param media     the media whose switch keeps the camera out
     * @param whole     which blocks the camera cannot be in
     */
    WallSections(World world, CamAreaRules rules, boolean areasRead, Set<FlightMedium> media,
                 WholeBlocks whole) {
        this.world = world;
        this.rules = rules;
        this.wholeBlocks = whole;
        this.areasRead = areasRead;
        this.media = media;
    }

    /**
     * Says what counts at this look, before {@link #update}: where the
     * distance is measured from, and whether the forbidden areas count. A
     * change in either throws away everything worked out from it.
     *
     * @param anchor the spot {@code max-distance} is measured from, or
     *               {@code null} when the distance does not count
     */
    void prepare(Location anchor, double maxDistance, boolean areasCounted) {
        boolean moved = !sameSpot(this.anchor, anchor) || this.maxDistance != maxDistance;
        if (!moved && this.areasCounted == areasCounted) {
            return;
        }
        this.anchor = anchor == null ? null : anchor.clone();
        this.maxDistance = maxDistance;
        this.areasCounted = areasCounted;
        for (Section section : sections.values()) {
            if (moved) {
                section.distanceKnown = false;
                section.distance = null;
            }
            section.frontKnown = false;
            section.front = null;
        }
        allRenewed = true;
    }

    private static boolean sameSpot(Location a, Location b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getWorld() == b.getWorld() && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    /**
     * Brings the sections around the body up to date: looks them over when
     * this is a full look or the body has moved {@link #margin} since the
     * last time, and reads out of the world as many of those missing or
     * grown old as the time allows, the nearest first.
     *
     * @param reach  how far around the body the wall stands, in blocks
     * @param now    the tick counter of the wall, see {@link #maxAge(double)}
     * @param budget how long reading may take, in nanoseconds; the sections
     *               within {@link #ALWAYS_REACH} are read whatever it takes
     * @param look   whether this is a full look, which also notes the
     *               sections grown old and lets go of those far out
     */
    void update(BoundingBox body, double reach, long now, long budget, boolean look) {
        this.body = body.clone();
        this.needed = reach + NEIGHBOUR_REACH;
        this.now = now;
        if (look || scannedAt == null || scannedReach != reach
                || Math.abs(body.getMinX() - scannedAt.getMinX()) + Math.abs(body.getMinY() - scannedAt.getMinY())
                + Math.abs(body.getMinZ() - scannedAt.getMinZ()) >= margin(reach)) {
            scan(reach, now, look);
        }
        work(now, budget);
    }

    /**
     * Looks over the sections around the body: notes the ones within reach,
     * the ones missing, and on a full look the ones grown old - and lets go
     * of those far out.
     */
    private void scan(double reach, long now, boolean look) {
        scannedAt = body.clone();
        scannedReach = reach;
        double margin = margin(reach);
        double range = needed + margin;
        if (look) {
            Iterator<Map.Entry<Pos, Section>> kept = sections.entrySet().iterator();
            while (kept.hasNext()) {
                Map.Entry<Pos, Section> entry = kept.next();
                if (gap(entry.getKey()) > range + KEEP_MARGIN) {
                    kept.remove();
                    forgetFrontsAround(entry.getKey());
                }
            }
        }
        inReach.clear();
        inReachSet.clear();
        List<Pending> missing = new ArrayList<>();
        int x0 = Math.floorDiv((int) Math.floor(body.getMinX() - range), 16);
        int x1 = Math.floorDiv((int) Math.floor(body.getMaxX() + range), 16);
        int y0 = Math.floorDiv((int) Math.floor(body.getMinY() - range), 16);
        int y1 = Math.floorDiv((int) Math.floor(body.getMaxY() + range), 16);
        int z0 = Math.floorDiv((int) Math.floor(body.getMinZ() - range), 16);
        int z1 = Math.floorDiv((int) Math.floor(body.getMaxZ() + range), 16);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Pos pos = new Pos(x, y, z);
                    double gap = gap(pos);
                    if (gap > range) {
                        continue;
                    }
                    Section section = sections.get(pos);
                    if (gap <= reach + margin) {
                        inReach.add(pos);
                        inReachSet.add(pos);
                        if (look && section != null && section.frontKnown && section.edge != null
                                && now - section.frontAt >= maxAge(gap * FRONT_AGE_FACTOR)) {
                            // Looked over again on the next hand-out: a block
                            // next to it may have been broken or set.
                            section.recheck = true;
                        }
                    }
                    if (section == null) {
                        missing.add(new Pending(pos, gap, true, true));
                    } else if (look) {
                        boolean area = areasRead && now - section.areaReadAt >= maxAge(gap) * AREA_AGE_FACTOR;
                        boolean medium = !media.isEmpty() && now - section.mediumReadAt >= maxAge(gap);
                        if (area || medium) {
                            missing.add(new Pending(pos, gap, area, medium));
                        }
                    }
                }
            }
        }
        if (!look) {
            // What the last full look found grown old and could not read
            // yet stays in line - a player who keeps moving would otherwise
            // never let it come up.
            for (Pending old : pending) {
                if (sections.containsKey(old.pos())) {
                    missing.add(old);
                }
            }
        }
        missing.sort(Comparator.comparingDouble(Pending::gap));
        pending = missing;
    }

    /**
     * Reads the next sections out of the world, the nearest first, as many as
     * the time allows - and the ones within {@link #ALWAYS_REACH} whatever it
     * takes.
     */
    private void work(long now, long budget) {
        long start = System.nanoTime();
        CamAreaRules.BlockLookup lookup = areasRead ? rules.blockLookup(world) : null;
        int done = 0;
        for (Pending next : pending) {
            if (next.gap() > ALWAYS_REACH && System.nanoTime() - start > budget) {
                break;
            }
            done++;
            read(next, lookup, now);
        }
        pending = new ArrayList<>(pending.subList(done, pending.size()));
    }

    /** Whether sections are still waiting to be read out of the world. */
    boolean hasWork() {
        return !pending.isEmpty();
    }

    /**
     * Has the media of the section holding this block read out of the world
     * again on the next update, ahead of all others: the block has changed in
     * a way the wall cannot judge on its own - water that ran off out of it
     * may have left it open.
     */
    void reread(int x, int y, int z) {
        Pos pos = new Pos(x >> 4, y >> 4, z >> 4);
        Section section = sections.get(pos);
        if (section == null || media.isEmpty()) {
            return;
        }
        // Grown old at once, so that a full look still finds it when this
        // request is passed over.
        section.mediumReadAt = Long.MIN_VALUE / 2;
        pending.add(0, new Pending(pos, 0.0, false, true));
    }

    /**
     * How long a section is kept before it is read again, in ticks:
     * {@link #MIN_AGE} within {@link #ALWAYS_REACH}, and growing with the
     * square of the distance past it, see {@link #AGE_SPREAD}. A far section
     * only shows the wall, and the player needs a while to get there - by
     * then it has come closer and grown old sooner.
     */
    private static long maxAge(double gap) {
        double beyond = Math.max(0.0, gap - ALWAYS_REACH);
        return MIN_AGE + (long) (beyond * beyond / AGE_SPREAD);
    }

    /**
     * Reads one section out of the world, or the parts of it grown old, and
     * keeps it. When that changes what is known about it, the front around
     * it is worked out anew, and its blocks of the wall are made anew, see
     * {@link Changes#renewed}.
     */
    private void read(Pending next, CamAreaRules.BlockLookup lookup, long now) {
        Pos pos = next.pos();
        boolean inWorld = (pos.y() << 4) + 15 >= world.getMinHeight() && (pos.y() << 4) < world.getMaxHeight();
        if (inWorld && !world.isChunkLoaded(pos.x(), pos.z())) {
            // Not now: nobody can be there without the chunk, and reading
            // it would load it. Until it is loaded the sections next to it
            // build no wall, see front().
            return;
        }
        Section section = sections.get(pos);
        boolean added = section == null;
        if (added) {
            section = new Section();
            sections.put(pos, section);
        }
        char[] area = section.area;
        if (added || next.area()) {
            section.areaReadAt = now;
            area = null;
            if (inWorld && lookup != null) {
                char[] marked = new char[256];
                if (lookup.markSection(pos.x(), pos.y(), pos.z(), marked)) {
                    area = compact(marked);
                }
            }
        }
        char[] medium = section.medium;
        if (added || next.medium()) {
            section.mediumReadAt = now;
            medium = inWorld && !media.isEmpty() ? readMedia(pos) : null;
        }
        if (!added && Arrays.equals(section.area, area) && Arrays.equals(section.medium, medium)) {
            return;
        }
        section.area = area;
        section.medium = medium;
        renewed.add(pos);
        forgetFrontsAround(pos);
    }

    /** How far the body may move before the sections are looked over again, see {@link #SCAN_MARGIN}. */
    private static double margin(double reach) {
        return Math.max(1.0, Math.min(SCAN_MARGIN, reach / 4.0));
    }

    /**
     * The blocks of a section filled with a shut medium. A column is read only
     * up to its highest block that is not air: everything above it is air,
     * and over open land that is most of what lies around a camera.
     */
    private char[] readMedia(Pos pos) {
        int x0 = pos.x() << 4;
        int y0 = pos.y() << 4;
        int z0 = pos.z() << 4;
        int bottom = Math.max(y0, world.getMinHeight());
        int top = Math.min(y0 + 15, world.getMaxHeight() - 1);
        char[] bits = null;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int highest = Math.min(top, world.getHighestBlockYAt(x0 + x, z0 + z, HeightMap.WORLD_SURFACE));
                for (int y = bottom; y <= highest; y++) {
                    if (CamBorderWall.shutMediumAt(world, x0 + x, y, z0 + z, media) != null) {
                        if (bits == null) {
                            bits = new char[256];
                        }
                        bits[x * 16 + z] |= (char) (1 << (y - y0));
                    }
                }
            }
        }
        return bits == null ? null : compact(bits);
    }

    /** {@link #FULL} for a section with every bit set, the array itself otherwise. */
    private static char[] compact(char[] bits) {
        for (char column : bits) {
            if (column != 0xFFFF) {
                return bits;
            }
        }
        return FULL;
    }

    /** Forgets the front of this section and of the 26 around it, which all look at it. */
    private void forgetFrontsAround(Pos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Section section = sections.get(new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    if (section != null) {
                        section.frontKnown = false;
                        section.front = null;
                    }
                }
            }
        }
    }

    /**
     * Hands out what has changed in the wall within reach of the body of the
     * last {@link #update}: the blocks that have joined it, those that have
     * left it, and those whose section has been read anew. The wall is the
     * front of the shut blocks, inside the height of the world and not in
     * the body itself.
     *
     * <p>A section that lies within reach as a whole, as most of a large wall
     * does, is passed over as long as its front stays as it was: a step of
     * the player costs what it changes, not what the wall holds. A section
     * next to one that has not been read yet keeps what it had until it has
     * been.</p>
     */
    void forEachChange(double reach, Changes changes) {
        Iterator<Map.Entry<Pos, char[]>> out = handed.entrySet().iterator();
        while (out.hasNext()) {
            Map.Entry<Pos, char[]> entry = out.next();
            if (!inReachSet.contains(entry.getKey())) {
                report(entry.getKey(), entry.getValue(), null, false, changes);
                out.remove();
            }
        }
        for (Pos pos : inReach) {
            Section section = sections.get(pos);
            char[] front = section == null ? UNKNOWN : front(pos, section);
            if (front == UNKNOWN) {
                continue;
            }
            char[] before = handed.get(pos);
            char[] now = front == null ? null : withinReach(pos, front, reach);
            boolean fresh = allRenewed || renewed.contains(pos);
            if (!fresh && (now == before || (now != null && before != null && Arrays.equals(now, before)))) {
                if (now != null) {
                    handed.put(pos, now);
                }
                continue;
            }
            report(pos, before, now, fresh, changes);
            if (now == null) {
                handed.remove(pos);
            } else {
                handed.put(pos, now);
            }
        }
        renewed.clear();
        allRenewed = false;
    }

    /**
     * Hands out the difference between what a section had in the wall and
     * what it has now, and with {@code fresh} every block it keeps as well.
     */
    private static void report(Pos pos, char[] before, char[] now, boolean fresh, Changes changes) {
        int x0 = pos.x() << 4;
        int y0 = pos.y() << 4;
        int z0 = pos.z() << 4;
        for (int i = 0; i < 256; i++) {
            int was = bits(before, i);
            int is = bits(now, i);
            int left = was & ~is;
            int entered = is & ~was;
            int kept = fresh ? is & was : 0;
            if ((left | entered | kept) == 0) {
                continue;
            }
            int x = x0 + (i >> 4);
            int z = z0 + (i & 15);
            for (int y = 0; y < 16; y++) {
                int bit = 1 << y;
                if ((left & bit) != 0) {
                    changes.left(x, y0 + y, z);
                } else if ((entered & bit) != 0) {
                    changes.entered(x, y0 + y, z);
                } else if ((kept & bit) != 0) {
                    changes.renewed(x, y0 + y, z);
                }
            }
        }
    }

    /**
     * The part of a front within reach of the body of the last look, inside
     * the height of the world and not in the body itself - {@code null} when
     * nothing is left of it. When all of the section lies that way, which
     * is what makes an unchanged one cheap, it is the front itself.
     */
    private char[] withinReach(Pos pos, char[] front, double reach) {
        int x0 = pos.x() << 4;
        int y0 = pos.y() << 4;
        int z0 = pos.z() << 4;
        int bottom = world.getMinHeight();
        int top = world.getMaxHeight() - 1;
        double reachSquared = reach * reach;
        double fx = Math.max(gap(x0, body.getMinX(), body.getMaxX()), gap(x0 + 15, body.getMinX(), body.getMaxX()));
        double fy = Math.max(gap(y0, body.getMinY(), body.getMaxY()), gap(y0 + 15, body.getMinY(), body.getMaxY()));
        double fz = Math.max(gap(z0, body.getMinZ(), body.getMaxZ()), gap(z0 + 15, body.getMinZ(), body.getMaxZ()));
        boolean touchesBody = x0 < body.getMaxX() - OVERLAP_MARGIN && x0 + 16 > body.getMinX() + OVERLAP_MARGIN
                && y0 < body.getMaxY() - OVERLAP_MARGIN && y0 + 16 > body.getMinY() + OVERLAP_MARGIN
                && z0 < body.getMaxZ() - OVERLAP_MARGIN && z0 + 16 > body.getMinZ() + OVERLAP_MARGIN;
        if (y0 >= bottom && y0 + 15 <= top && !touchesBody && fx * fx + fy * fy + fz * fz <= reachSquared) {
            return front;
        }
        char[] kept = null;
        for (int lx = 0; lx < 16; lx++) {
            int x = x0 + lx;
            double gapX = gap(x, body.getMinX(), body.getMaxX());
            for (int lz = 0; lz < 16; lz++) {
                int column = front[lx * 16 + lz];
                if (column == 0) {
                    continue;
                }
                int z = z0 + lz;
                double gapZ = gap(z, body.getMinZ(), body.getMaxZ());
                if (gapX * gapX + gapZ * gapZ > reachSquared) {
                    continue;
                }
                int within = 0;
                for (int ly = 0; ly < 16; ly++) {
                    int y = y0 + ly;
                    if ((column & (1 << ly)) == 0 || y < bottom || y > top) {
                        continue;
                    }
                    double gapY = gap(y, body.getMinY(), body.getMaxY());
                    if (gapX * gapX + gapY * gapY + gapZ * gapZ > reachSquared) {
                        continue;
                    }
                    if (inBody(x, body.getMinX(), body.getMaxX()) && inBody(y, body.getMinY(), body.getMaxY())
                            && inBody(z, body.getMinZ(), body.getMaxZ())) {
                        // The body is in this block already, which a
                        // teleport, a client that ignored the wall or water
                        // flowing over the camera can have done. A block
                        // around it would lock it in.
                        continue;
                    }
                    within |= 1 << ly;
                }
                if (within != 0) {
                    if (kept == null) {
                        kept = new char[256];
                    }
                    kept[lx * 16 + lz] = (char) within;
                }
            }
        }
        return kept;
    }

    /**
     * The front of the shut blocks in this section: each shut block with a
     * block the camera can be in among the 26 around it. {@code null} when
     * there is none, and {@link #UNKNOWN} when a section next to it is
     * needed but has not been read yet.
     */
    private char[] front(Pos pos, Section section) {
        if (section.frontKnown) {
            if (section.recheck) {
                recheck(pos, section);
            }
            return section.front;
        }
        char[][] shut = new char[27][];
        boolean empty = true;
        boolean full = true;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Pos around = new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    Section other = sections.get(around);
                    char[] bits;
                    if (other != null) {
                        bits = shut(around, other);
                    } else if (gap(around) <= needed) {
                        // Needed, but not read yet.
                        return UNKNOWN;
                    } else {
                        // Too far out to touch any block within reach, so
                        // what it holds does not matter. Counted as shut:
                        // open, it would give the blocks next to it a front
                        // that is only sorted out again further on.
                        bits = FULL;
                    }
                    int i = ((dx + 1) * 3 + dy + 1) * 3 + dz + 1;
                    shut[i] = bits;
                    if (i == 13) {
                        empty = bits == null;
                    }
                    full &= bits == FULL;
                }
            }
        }
        section.frontKnown = true;
        section.frontAt = now;
        section.recheck = false;
        section.front = null;
        section.edge = null;
        section.open = null;
        if (empty || full) {
            return null;
        }
        int[] open = openAround(shut);
        char[] edge = edge(shut[13], open);
        if (edge != null) {
            section.edge = edge;
            section.open = open;
            section.front = reachable(pos, edge, open);
        }
        return section.front;
    }

    /**
     * Looks the front of a section over again from its edge, see
     * {@link Section#edge}. Kept as it was when nothing has changed, so that
     * {@link #forEachChange} passes it over.
     */
    private void recheck(Pos pos, Section section) {
        section.recheck = false;
        section.frontAt = now;
        char[] front = reachable(pos, section.edge, section.open);
        if (!Arrays.equals(front, section.front)) {
            section.front = front;
        }
    }

    /**
     * The open blocks of the 18 by 18 columns around the middle one of 27
     * sections, one block deeper on every side, given what is shut in each
     * of them: index {@code ((dx + 1) * 3 + dy + 1) * 3 + dz + 1}. Bit 0 of
     * a column is the block below the section, bit 17 the one above it.
     */
    private static int[] openAround(char[][] shut) {
        int[] open = new int[18 * 18];
        for (int px = 0; px < 18; px++) {
            int dx = px == 0 ? -1 : px == 17 ? 1 : 0;
            int lx = (px - 1) & 15;
            for (int pz = 0; pz < 18; pz++) {
                int dz = pz == 0 ? -1 : pz == 17 ? 1 : 0;
                int lz = (pz - 1) & 15;
                int column = lx * 16 + lz;
                int below = bits(shut[((dx + 1) * 3) * 3 + dz + 1], column);
                int middle = bits(shut[((dx + 1) * 3 + 1) * 3 + dz + 1], column);
                int above = bits(shut[((dx + 1) * 3 + 2) * 3 + dz + 1], column);
                int closed = (below >>> 15) & 1 | middle << 1 | (above & 1) << 17;
                open[px * 18 + pz] = ~closed & 0x3FFFF;
            }
        }
        return open;
    }

    /**
     * The shut blocks of the middle section with an open one among the 26
     * around them, whole or not.
     *
     * <p>Worked out a column at a time: the open blocks around are widened
     * by one in every direction - along the column by shifting the bits,
     * across it by joining nine neighbouring columns - and what of that
     * falls on a shut block of the section is the edge.</p>
     */
    private static char[] edge(char[] middle, int[] open) {
        char[] edge = null;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int column = bits(middle, x * 16 + z);
                if (column == 0) {
                    continue;
                }
                int near = 0;
                for (int px = x; px <= x + 2; px++) {
                    for (int pz = z; pz <= z + 2; pz++) {
                        near |= open[px * 18 + pz];
                    }
                }
                near |= near << 1 | near >>> 1;
                int touching = (near >>> 1) & column;
                if (touching != 0) {
                    if (edge == null) {
                        edge = new char[256];
                    }
                    edge[x * 16 + z] = (char) touching;
                }
            }
        }
        return edge;
    }

    /**
     * The blocks of the edge the camera can actually get into: those with
     * an open block around them it can be in, see {@link #passable}. A shut
     * block that touches nothing but whole blocks and its own kind lies
     * behind them, out of reach - a wall there would stop nobody.
     *
     * <p>Each open block is looked at in the world once, however many
     * blocks of the edge it lies next to.</p>
     */
    private char[] reachable(Pos pos, char[] edge, int[] open) {
        int x0 = (pos.x() << 4) - 1;
        int y0 = (pos.y() << 4) - 1;
        int z0 = (pos.z() << 4) - 1;
        // Laid out like open: which blocks have been looked at, and which of
        // those the camera can be in.
        int[] looked = new int[18 * 18];
        int[] passable = new int[18 * 18];
        // The highest block that is not air in each column, once asked:
        // everything above it is air, and needs no look of its own.
        int[] surface = new int[18 * 18];
        Arrays.fill(surface, Integer.MIN_VALUE);
        char[] reachable = null;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int column = edge[x * 16 + z];
                if (column == 0) {
                    continue;
                }
                int kept = 0;
                for (int y = 0; y < 16; y++) {
                    if ((column & (1 << y)) == 0) {
                        continue;
                    }
                    for (int[] step : NEIGHBOURS) {
                        int px = x + 1 + step[0];
                        int py = y + 1 + step[1];
                        int pz = z + 1 + step[2];
                        int i = px * 18 + pz;
                        int bit = 1 << py;
                        if ((open[i] & bit) == 0) {
                            continue;
                        }
                        if ((looked[i] & bit) == 0) {
                            looked[i] |= bit;
                            if (surface[i] == Integer.MIN_VALUE) {
                                surface[i] = surface(x0 + px, z0 + pz);
                            }
                            if (y0 + py > surface[i] || passable(x0 + px, y0 + py, z0 + pz)) {
                                passable[i] |= bit;
                            }
                        }
                        if ((passable[i] & bit) != 0) {
                            kept |= 1 << y;
                            break;
                        }
                    }
                }
                if (kept != 0) {
                    if (reachable == null) {
                        reachable = new char[256];
                    }
                    reachable[x * 16 + z] = (char) kept;
                }
            }
        }
        return reachable;
    }

    /**
     * Whether the camera can be in the block at this spot: anything short of
     * a whole block. A spot that cannot be looked at - outside the height of
     * the world, or in a chunk that is not loaded - counts as one it can be
     * in: better a wall too many than a gap.
     */
    private boolean passable(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return true;
        }
        return world.getType(x, y, z).isAir() || !wholeBlocks.whole(world.getBlockAt(x, y, z));
    }

    /**
     * The highest block of this column that is not air, or the top of the
     * world when the chunk is not loaded - then every block is looked at on
     * its own, see {@link #passable}.
     */
    private int surface(int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return world.getMaxHeight();
        }
        return world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
    }

    /** The 26 steps to the blocks around one, in the order of {@link #NEIGHBOURS}. */
    private static int[][] neighbours() {
        List<int[]> steps = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        steps.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        steps.sort(Comparator.<int[]>comparingInt(step -> Math.abs(step[0]) + Math.abs(step[1]) + Math.abs(step[2]))
                .thenComparingInt(step -> -step[1]));
        return steps.toArray(new int[0][]);
    }

    private static int bits(char[] section, int column) {
        return section == null ? 0 : section[column];
    }

    /** What is shut in a section right now: forbidden areas if they count, shut media, and the distance. */
    private char[] shut(Pos pos, Section section) {
        char[] area = areasCounted ? section.area : null;
        char[] medium = section.medium;
        char[] distance = distance(pos, section);
        if (area == FULL || medium == FULL || distance == FULL) {
            return FULL;
        }
        if (medium == null && distance == null) {
            return area;
        }
        if (area == null && distance == null) {
            return medium;
        }
        if (area == null && medium == null) {
            return distance;
        }
        char[] joined = new char[256];
        for (int i = 0; i < 256; i++) {
            joined[i] = (char) (bits(area, i) | bits(medium, i) | bits(distance, i));
        }
        return compact(joined);
    }

    /** The blocks of a section lying beyond {@code max-distance}, see {@link #tooFar}. */
    private char[] distance(Pos pos, Section section) {
        if (section.distanceKnown) {
            return section.distance;
        }
        section.distanceKnown = true;
        section.distance = null;
        if (anchor == null) {
            return null;
        }
        double maxSquared = maxDistance * maxDistance;
        int x0 = pos.x() << 4;
        int y0 = pos.y() << 4;
        int z0 = pos.z() << 4;
        double ax = anchor.getX();
        double ay = anchor.getY();
        double az = anchor.getZ();
        // The nearest and the farthest point of the section: beyond the one,
        // every block of it is too far, within the other none is.
        double nx = Math.max(0.0, Math.max(x0 - ax, ax - (x0 + 16)));
        double ny = Math.max(0.0, Math.max(y0 - ay, ay - (y0 + 16)));
        double nz = Math.max(0.0, Math.max(z0 - az, az - (z0 + 16)));
        if (nx * nx + ny * ny + nz * nz > maxSquared) {
            section.distance = FULL;
            return FULL;
        }
        double fx = Math.max(Math.abs(x0 - ax), Math.abs(x0 + 16 - ax));
        double fy = Math.max(Math.abs(y0 - ay), Math.abs(y0 + 16 - ay));
        double fz = Math.max(Math.abs(z0 - az), Math.abs(z0 + 16 - az));
        if (fx * fx + fy * fy + fz * fz <= maxSquared) {
            return null;
        }
        char[] bits = new char[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int column = 0;
                for (int y = 0; y < 16; y++) {
                    if (tooFar(anchor, maxSquared, x0 + x, y0 + y, z0 + z)) {
                        column |= 1 << y;
                    }
                }
                bits[x * 16 + z] = (char) column;
            }
        }
        section.distance = compact(bits);
        return section.distance;
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

    /** How far the nearest block of this section lies from the body of the last look. */
    private double gap(Pos pos) {
        double gx = sectionGap(pos.x(), body.getMinX(), body.getMaxX());
        double gy = sectionGap(pos.y(), body.getMinY(), body.getMaxY());
        double gz = sectionGap(pos.z(), body.getMinZ(), body.getMaxZ());
        return Math.sqrt(gx * gx + gy * gy + gz * gz);
    }

    /** How far the blocks of a section lie from the body along one axis, the nearest of them. */
    private static double sectionGap(int section, double min, double max) {
        int from = section << 4;
        return Math.max(0.0, Math.max(from - max, min - (from + 16)));
    }

    /** How far the block at this coordinate lies from the body along one axis. */
    private static double gap(int block, double min, double max) {
        return Math.max(0.0, Math.max(block - max, min - (block + 1)));
    }

    /** Whether the block at this coordinate reaches into the body along one axis. */
    private static boolean inBody(int block, double min, double max) {
        return block < max - OVERLAP_MARGIN && block + 1 > min + OVERLAP_MARGIN;
    }
}
