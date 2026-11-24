package de.elia.cameraplugin.trail;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrailFile}: what the file of a kept flight says, that a flight comes
 * back from it the way it went in, and that its head can be read without its
 * line - also from files somebody edited by hand.
 */
class TrailFileTest {

    private static final double DELTA = 1e-9;

    private static final UUID PLAYER = UUID.fromString("0f4d6c2e-1b7a-4c3e-9d2f-8a5b6c7d8e9f");
    private static final UUID WORLD = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID NETHER = UUID.fromString("66666666-7777-8888-9999-000000000000");

    private static TrailFile.Data flight() {
        return new TrailFile.Data(PLAYER, "Steve", 1_700_000_000_000L, 1_700_000_090_000L, 123.456,
                new TrailFile.Place("world", WORLD, 10.5, 64.0, -20.25, 90.0f, -15.5f),
                new TrailFile.Place("world_nether", NETHER, 1.0, 70.0, 2.0, 0.0f, 0.0f),
                List.of(new TrailFile.Line("world", WORLD, new double[]{10.5, 65.0, -20.25, 11.5, 65.0, -20.25}),
                        new TrailFile.Line("world_nether", NETHER, new double[]{1.0, 71.0, 2.0})));
    }

    private static TrailFile.Data head(String text) throws IOException, InvalidConfigurationException {
        return TrailFile.readHead(new BufferedReader(new StringReader(text)));
    }

    @Test
    void flightComesBackAsItWentIn() throws InvalidConfigurationException {
        TrailFile.Data read = TrailFile.read(TrailFile.text(flight()));

        assertNotNull(read);
        assertEquals(PLAYER, read.player());
        assertEquals("Steve", read.name());
        assertEquals(1_700_000_000_000L, read.startedAt());
        assertEquals(1_700_000_090_000L, read.endedAt());
        // The distance to a tenth of a block.
        assertEquals(123.5, read.distance(), DELTA);
        assertEquals(new TrailFile.Place("world", WORLD, 10.5, 64.0, -20.25, 90.0f, -15.5f), read.start());
        assertEquals("world_nether", read.last().world());
        assertEquals(NETHER, read.last().worldId());
        assertEquals(2, read.pieces().size());
        assertEquals("world", read.pieces().get(0).world());
        assertEquals(WORLD, read.pieces().get(0).worldId());
        assertArrayEquals(new double[]{10.5, 65.0, -20.25, 11.5, 65.0, -20.25}, read.pieces().get(0).coords(), DELTA);
        assertArrayEquals(new double[]{1.0, 71.0, 2.0}, read.pieces().get(1).coords(), DELTA);
    }

    @Test
    void pointsAreKeptToTheHundredthOfABlock() throws InvalidConfigurationException {
        TrailFile.Data flight = new TrailFile.Data(PLAYER, "Steve", 5L, 6L, 0.0, flight().start(), flight().last(),
                List.of(new TrailFile.Line("world", WORLD, new double[]{0.123456, -64.0049, 29_999_999.999})));

        TrailFile.Data read = TrailFile.read(TrailFile.text(flight));

        assertArrayEquals(new double[]{0.12, -64.0, 30_000_000.0}, read.pieces().get(0).coords(), DELTA);
    }

    @Test
    void theLineComesLastSoTheHeadIsReadWithoutIt() throws IOException, InvalidConfigurationException {
        String text = TrailFile.text(flight());

        assertTrue(text.indexOf("\npieces:") > text.indexOf("\nlast:"), text);
        TrailFile.Data head = head(text);

        assertNotNull(head);
        assertEquals("Steve", head.name());
        assertEquals(1_700_000_090_000L, head.endedAt());
        assertEquals(new TrailFile.Place("world", WORLD, 10.5, 64.0, -20.25, 90.0f, -15.5f), head.start());
        assertEquals("world_nether", head.last().world());
        assertTrue(head.pieces().isEmpty());
    }

    @Test
    void theHeadDoesNotNeedTheLineToBeReadable() throws IOException, InvalidConfigurationException {
        String text = TrailFile.text(flight());
        String broken = text.substring(0, text.indexOf("\npieces:")) + "\npieces:\n- world: [not: closed\n";

        assertEquals("Steve", head(broken).name());
        assertThrows(InvalidConfigurationException.class, () -> TrailFile.read(broken));
    }

    @Test
    void theFileSaysWhoseItIsAndWhen() throws InvalidConfigurationException, IOException {
        String text = TrailFile.text(flight());

        assertTrue(text.startsWith("# CamFly: one flight of the camera of this player"), text);
        assertTrue(text.contains("\nformat: " + TrailFile.FORMAT + "\n"), text);
        assertTrue(text.contains("\nplayer: " + PLAYER + "\n"), text);
        // Without whose it is, or when it began, it is no flight.
        assertNull(TrailFile.read(text.replace("player: " + PLAYER, "player: somebody")));
        assertNull(head(text.replace("started: 1700000000000", "started: 0")));
        assertNull(head(text.replaceAll("(?m)^start:\\n(  .*\\n)*", "")));
    }

    @Test
    void thePathSaysWhoseItIsAndWhenItBegan() {
        UUID other = UUID.randomUUID();

        TrailFile.Data moved = flight().at(other, 1_700_000_100_000L);

        assertEquals(other, moved.player());
        assertEquals(1_700_000_100_000L, moved.startedAt());
        // It cannot have ended before it began.
        assertEquals(1_700_000_100_000L, moved.endedAt());
        assertEquals("Steve", moved.name());
    }

    @Test
    void aFileEditedByHandIsReadAsFarAsItMakesSense() throws IOException, InvalidConfigurationException {
        String text = """
                player: 0f4d6c2e-1b7a-4c3e-9d2f-8a5b6c7d8e9f
                started: 1000
                ended: 500
                distance: -4
                start:
                  world: world
                  x: 1
                  y: 2
                  z: 3
                pieces:
                - world: world
                  points: 1 2 3 4 5 x 7 8 9 10 11
                - points: 1 2 3
                - world: world
                  points: ''
                """;

        TrailFile.Data read = TrailFile.read(text);

        assertEquals(PLAYER.toString(), read.name());
        assertEquals(1000L, read.endedAt());
        assertEquals(0.0, read.distance(), DELTA);
        // Without its own end, it ended where it began.
        assertEquals(read.start(), read.last());
        assertNull(read.start().worldId());
        // A point with something else than a number in it goes, and so does one not whole; a piece without a
        // world, and one without a point, are left out.
        assertEquals(1, read.pieces().size());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 7.0, 8.0, 9.0}, read.pieces().get(0).coords(), DELTA);
        assertEquals(1000L, head(text).endedAt());
    }

    @Test
    void numbersOfALine() {
        assertArrayEquals(new double[0], TrailFile.coords(null));
        assertArrayEquals(new double[0], TrailFile.coords("  "));
        assertArrayEquals(new double[]{1.5, -2.0, 3.0}, TrailFile.coords(" 1.5  -2\t3 "), DELTA);
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, TrailFile.coords("1 2 3 NaN 5 6"), DELTA);
        assertEquals("1.5 -2.0 3.0", TrailFile.points(new double[]{1.5, -2.0, 3.0}));
    }
}
