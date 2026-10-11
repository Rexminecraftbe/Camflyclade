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
 * where it is. The wall itself is the front of the shut blocks - each one
 * next to a block that is not shut - and that front is kept for every section
 * as well, until something in or around it changes.</p>
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
     * that water flowing in counts as soon as it used to.
     */
    private static final long MIN_AGE = 20L;

    /**
     * How far a section has to lie from the body before it is worked out
     * whatever the time it takes, in blocks. The wall right in front of the
     * player has to stand; the far parts of a large wall are only there to be
     * seen and may follow a few ticks later.
     */
    private static final double ALWAYS_REACH = 12.0;

    /** How far past what is needed a section stays kept before it is let go, in blocks. */
    private static final double KEEP_MARGIN = 16.0;

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
        private long readAt;
        /** The blocks beyond {@code max-distance}, once worked out. */
        private char[] distance;
        private boolean distanceKnown;
        /** The front of the shut blocks, the wall, once worked out. */
        private char[] front;
        private boolean frontKnown;
    }

    /** A section to be read out of the world, and how far it lies from the body. */
    private record Pending(Pos pos, double gap) {
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
    /** Whether forbidden areas are looked for at all, see {@link #areasCounted}. */
    private final boolean areasRead;
    private final Set<FlightMedium> media;
    private final Map<Pos, Section> sections = new HashMap<>();

    /** The sections still to be read out of the world, the nearest first. */
    private List<Pending> pending = new ArrayList<>();
    /** The sections that may hold blocks within reach of the last look, see {@link #update}. */
    private final List<Pos> inReach = new ArrayList<>();
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

    /** The body at the last look, and how far the sections were needed around it. */
    private BoundingBox body;
    private double needed;

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
     */
    WallSections(World world, CamAreaRules rules, boolean areasRead, Set<FlightMedium> media) {
        this.world = world;
        this.rules = rules;
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
     * Looks at the sections around the body: lets go of those far out,
     * notes the ones missing or grown old, and reads out of the world as
     * many of those as the time allows, the nearest first.
     *
     * @param reach  how far around the body the wall stands, in blocks
     * @param now    the tick counter of the wall, see {@link #maxAge(double)}
     * @param budget how long reading may take, in nanoseconds; the sections
     *               within {@link #ALWAYS_REACH} are read whatever it takes
     */
    void update(BoundingBox body, double reach, long now, long budget) {
        this.body = body.clone();
        this.needed = reach + NEIGHBOUR_REACH;
        inReach.clear();
        List<Pending> missing = new ArrayList<>();

        Iterator<Map.Entry<Pos, Section>> kept = sections.entrySet().iterator();
        while (kept.hasNext()) {
            Map.Entry<Pos, Section> entry = kept.next();
            if (gap(entry.getKey()) > needed + KEEP_MARGIN) {
                kept.remove();
                forgetFrontsAround(entry.getKey());
            }
        }

        int x0 = Math.floorDiv((int) Math.floor(body.getMinX() - needed), 16);
        int x1 = Math.floorDiv((int) Math.floor(body.getMaxX() + needed), 16);
        int y0 = Math.floorDiv((int) Math.floor(body.getMinY() - needed), 16);
        int y1 = Math.floorDiv((int) Math.floor(body.getMaxY() + needed), 16);
        int z0 = Math.floorDiv((int) Math.floor(body.getMinZ() - needed), 16);
        int z1 = Math.floorDiv((int) Math.floor(body.getMaxZ() + needed), 16);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Pos pos = new Pos(x, y, z);
                    double gap = gap(pos);
                    if (gap > needed) {
                        continue;
                    }
                    if (gap <= reach) {
                        inReach.add(pos);
                    }
                    Section section = sections.get(pos);
                    if (section == null || now - section.readAt >= maxAge(gap)) {
                        missing.add(new Pending(pos, gap));
                    }
                }
            }
        }
        missing.sort(Comparator.comparingDouble(Pending::gap));
        pending = missing;
        work(now, budget);
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
            read(next.pos(), lookup, now);
        }
        pending = new ArrayList<>(pending.subList(done, pending.size()));
    }

    /** Whether sections are still waiting to be read out of the world. */
    boolean hasWork() {
        return !pending.isEmpty();
    }

    /**
     * How long a section is kept before it is read again, in ticks: about as
     * many as it lies blocks away from the body, and never less than
     * {@link #MIN_AGE}. The player cannot get there any sooner.
     */
    private static long maxAge(double gap) {
        return Math.max(MIN_AGE, (long) gap);
    }

    /**
     * Reads one section out of the world and keeps it. When that changes what
     * is known about it, the front around it is worked out anew, and its
     * blocks of the wall are made anew, see {@link Changes#renewed}.
     */
    private void read(Pos pos, CamAreaRules.BlockLookup lookup, long now) {
        boolean inWorld = (pos.y() << 4) + 15 >= world.getMinHeight() && (pos.y() << 4) < world.getMaxHeight();
        if (inWorld && !world.isChunkLoaded(pos.x(), pos.z())) {
            // Not now: nobody can be there without the chunk, and reading
            // it would load it. Until it is loaded the sections next to it
            // build no wall, see front().
            return;
        }
        char[] area = null;
        if (inWorld && lookup != null) {
            char[] marked = new char[256];
            if (lookup.markSection(pos.x(), pos.y(), pos.z(), marked)) {
                area = compact(marked);
            }
        }
        char[] medium = inWorld && !media.isEmpty() ? readMedia(pos) : null;
        Section section = sections.get(pos);
        if (section != null) {
            section.readAt = now;
            if (Arrays.equals(section.area, area) && Arrays.equals(section.medium, medium)) {
                return;
            }
        } else {
            section = new Section();
            section.readAt = now;
            sections.put(pos, section);
        }
        section.area = area;
        section.medium = medium;
        renewed.add(pos);
        forgetFrontsAround(pos);
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
        Set<Pos> reachable = new HashSet<>(inReach);
        Iterator<Map.Entry<Pos, char[]>> out = handed.entrySet().iterator();
        while (out.hasNext()) {
            Map.Entry<Pos, char[]> entry = out.next();
            if (!reachable.contains(entry.getKey())) {
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
     * The front of the shut blocks in this section: each shut block with an
     * open one among the 26 around it. {@code null} when there is none, and
     * {@link #UNKNOWN} when a section next to it is needed but has not been
     * read yet.
     */
    private char[] front(Pos pos, Section section) {
        if (section.frontKnown) {
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
        section.front = empty || full ? null : front(shut);
        return section.front;
    }

    /**
     * The front of the middle one of 27 sections, given what is shut in each
     * of them: index {@code ((dx + 1) * 3 + dy + 1) * 3 + dz + 1}.
     *
     * <p>Worked out a column at a time. The open blocks of the 18 by 18
     * columns around the section, one block deeper on every side, are
     * widened by one in every direction - along the column by shifting the
     * bits, across it by joining nine neighbouring columns - and what of
     * that falls on a shut block of the section is the front.</p>
     */
    private static char[] front(char[][] shut) {
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
                // Bit 0 is the block below the section, 17 the one above it.
                int closed = (below >>> 15) & 1 | middle << 1 | (above & 1) << 17;
                open[px * 18 + pz] = ~closed & 0x3FFFF;
            }
        }
        char[] middle = shut[13];
        char[] front = null;
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
                int edge = (near >>> 1) & column;
                if (edge != 0) {
                    if (front == null) {
                        front = new char[256];
                    }
                    front[x * 16 + z] = (char) edge;
                }
            }
        }
        return front;
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
