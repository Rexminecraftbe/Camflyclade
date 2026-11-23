package de.elia.cameraplugin.spot;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Share}: who may use a spot somebody shares - everybody while it is
 * public, a single player only once he accepted it, its owner never.
 */
class ShareTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final Share share = new Share(owner, "Alex",
            new Spot("tower", "world", 0, 64, 0, 0f, 0f, 0L, null, null));

    @Test
    void newShareIsForNobody() {
        assertFalse(share.isFor(friend));
        assertFalse(share.isFor(owner));
        assertTrue(share.isEmpty());
    }

    @Test
    void offeredSpotIsForHimOnlyOnceHeAccepted() {
        share.addPending(friend, "Sam");

        assertTrue(share.isOfferedTo(friend));
        assertFalse(share.isFor(friend));
        assertFalse(share.isEmpty());

        share.addPlayer(friend, "Sam");

        assertFalse(share.isOfferedTo(friend));
        assertTrue(share.isFor(friend));
        assertTrue(share.isAcceptedBy(friend));
        assertFalse(share.isFor(stranger));
    }

    @Test
    void publicSpotIsForEverybodyButItsOwner() {
        share.setEveryone(true);

        assertTrue(share.isFor(friend));
        assertTrue(share.isFor(stranger));
        assertFalse(share.isFor(owner));
        // Found among the public ones, not accepted by him himself.
        assertFalse(share.isAcceptedBy(stranger));
    }

    @Test
    void takenBackFromAPlayerWhetherOfferedOrAccepted() {
        share.addPending(friend, "Sam");
        share.addPlayer(stranger, "Kim");

        assertTrue(share.removePlayer(friend));
        assertTrue(share.removePlayer(stranger));
        assertFalse(share.removePlayer(friend));
        assertFalse(share.isFor(stranger));
        assertTrue(share.isEmpty());
    }

    @Test
    void likesStayWhileTheSpotIsPrivateForAWhile() {
        share.setEveryone(true);
        share.addLike(friend);
        share.setEveryone(false);

        assertFalse(share.isEmpty());
        assertTrue(share.isLikedBy(friend));

        share.clearPlayers();

        assertEquals(1, share.likes());
    }

    @Test
    void likeCountsOncePerPlayer() {
        share.addLike(friend);
        share.addLike(friend);
        share.addLike(stranger);

        assertEquals(2, share.likes());
        assertTrue(share.removeLike(friend));
        assertFalse(share.removeLike(friend));
        assertEquals(1, share.likes());
    }

    @Test
    void labelNamesOwnerAndSpot() {
        assertEquals("Alex:tower", share.label());

        share.setOwnerName("Alexandra");
        share.setSpot(share.getSpot().withName("castle"));

        assertEquals("Alexandra:castle", share.label());
    }
}
