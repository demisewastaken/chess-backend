package com.chess.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SpectatorPromotionTest {

    private Game game;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    public void setUp() {
        game = new Game();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        game.setMessagingTemplate(messagingTemplate);
    }

    // 1. Existing spectator is promoted to BLACK after BLACK leaves.
    @Test
    public void test1_ExistingSpectatorPromotedToBlackAfterBlackLeaves() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        assertTrue(whiteToken.startsWith("WHITE-"));
        assertTrue(blackToken.startsWith("BLACK-"));

        String spectatorToken = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", spectatorToken);

        // BLACK leaves table
        boolean released = game.releasePlayerSeat(blackToken, "BLACK");
        assertTrue(released, "Black's seat should be successfully released");
        assertTrue(game.isTokenRevoked(blackToken));

        // Existing spectator refreshes or clicks Play Match
        String promotedToken = game.assignPlayer("SPECTATOR");
        assertNotNull(promotedToken);
        assertTrue(promotedToken.startsWith("BLACK-"), "Spectator should be promoted to BLACK");
        assertTrue(game.isPlayerTokenActive(promotedToken));
        assertEquals("BLACK", game.getPlayerColor(promotedToken));

        // White remains unaffected
        assertTrue(game.isPlayerTokenActive(whiteToken));
        assertEquals("WHITE", game.getPlayerColor(whiteToken));
    }

    // 2. Existing spectator is promoted to WHITE after WHITE leaves.
    @Test
    public void test2_ExistingSpectatorPromotedToWhiteAfterWhiteLeaves() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        String spectatorToken = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", spectatorToken);

        // WHITE leaves table
        boolean released = game.releasePlayerSeat(whiteToken, "WHITE");
        assertTrue(released, "White's seat should be successfully released");
        assertTrue(game.isTokenRevoked(whiteToken));

        // Existing spectator refreshes or clicks Play Match
        String promotedToken = game.assignPlayer("SPECTATOR");
        assertNotNull(promotedToken);
        assertTrue(promotedToken.startsWith("WHITE-"), "Spectator should be promoted to WHITE");
        assertTrue(game.isPlayerTokenActive(promotedToken));
        assertEquals("WHITE", game.getPlayerColor(promotedToken));

        // Black remains unaffected
        assertTrue(game.isPlayerTokenActive(blackToken));
        assertEquals("BLACK", game.getPlayerColor(blackToken));
    }

    // 3. Existing spectator is promoted to WHITE after both players leave.
    @Test
    public void test3_ExistingSpectatorPromotedToWhiteAfterBothPlayersLeave() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        game.assignPlayer("SPECTATOR");

        // Both players leave
        game.releasePlayerSeat(whiteToken, "WHITE");
        game.releasePlayerSeat(blackToken, "BLACK");

        // Existing spectator requests seat
        String promotedToken = game.assignPlayer("SPECTATOR");
        assertNotNull(promotedToken);
        assertTrue(promotedToken.startsWith("WHITE-"), "Should prioritize WHITE seat when both are open");
        assertTrue(game.isPlayerTokenActive(promotedToken));
        assertEquals("WHITE", game.getPlayerColor(promotedToken));
    }

    // 4. Existing spectator remains SPECTATOR while both seats are occupied.
    @Test
    public void test4_ExistingSpectatorRemainsSpectatorWhileBothSeatsOccupied() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        String spectator = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", spectator);

        // While both seats are still occupied, attempting promotion must return SPECTATOR
        String attempt = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", attempt);

        // Existing player seats are intact
        assertEquals(whiteToken, game.assignPlayer(whiteToken));
        assertEquals(blackToken, game.assignPlayer(blackToken));
        assertTrue(game.isPlayerTokenActive(whiteToken));
        assertTrue(game.isPlayerTokenActive(blackToken));
    }

    // 5. Promoted spectator receives a valid player token and role.
    @Test
    public void test5_PromotedSpectatorReceivesValidPlayerTokenAndRole() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        game.assignPlayer("SPECTATOR");

        game.releasePlayerSeat(blackToken, "BLACK");

        String promotedToken = game.assignPlayer("SPECTATOR");
        assertNotNull(promotedToken);
        assertTrue(promotedToken.startsWith("BLACK-"));
        assertTrue(game.isPlayerTokenActive(promotedToken));
        assertFalse(game.isTokenRevoked(promotedToken));
        assertEquals("BLACK", game.getPlayerColor(promotedToken));
    }

    // 6. Promoted spectator can perform normal player actions.
    @Test
    public void test6_PromotedSpectatorCanPerformNormalPlayerActions() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        game.assignPlayer("SPECTATOR");

        game.releasePlayerSeat(blackToken, "BLACK");
        String promotedToken = game.assignPlayer("SPECTATOR");
        assertEquals("BLACK", game.getPlayerColor(promotedToken));

        // Promoted spectator can ready up
        assertFalse(game.isBlackReady());
        game.setPlayerReady("BLACK");
        assertTrue(game.isBlackReady(), "Promoted player should be able to declare readiness");
    }

    // 7. Promoted spectator no longer has spectator-only restrictions.
    @Test
    public void test7_PromotedSpectatorNoLongerHasSpectatorOnlyRestrictions() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        game.assignPlayer("SPECTATOR");

        game.releasePlayerSeat(whiteToken, "WHITE");
        String promotedToken = game.assignPlayer("SPECTATOR");

        // The token is recognized as an active player token, not a spectator
        assertTrue(game.isPlayerTokenActive(promotedToken));
        assertEquals("WHITE", game.getPlayerColor(promotedToken));

        // Can declare readiness as WHITE
        game.setPlayerReady("WHITE");
        assertTrue(game.isWhiteReady());
    }

    // 8. Old revoked player tokens cannot be used to reclaim seats.
    @Test
    public void test8_OldRevokedPlayerTokensCannotBeUsedToReclaimSeats() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        game.releasePlayerSeat(whiteToken, "WHITE");
        assertTrue(game.isTokenRevoked(whiteToken));

        // Old White tries to reclaim seat with old revoked token
        String outcome = game.assignPlayer(whiteToken);
        assertEquals("SPECTATOR", outcome, "Old revoked token must never reclaim a seat");
        assertFalse(game.isPlayerTokenActive(whiteToken));

        // A valid spectator can claim the seat
        String newWhiteToken = game.assignPlayer("SPECTATOR");
        assertTrue(newWhiteToken.startsWith("WHITE-"));
        assertNotEquals(whiteToken, newWhiteToken);
        assertTrue(game.isPlayerTokenActive(newWhiteToken));
    }

    // 9. Existing opponent's token and readiness remain unchanged after promotion.
    @Test
    public void test9_ExistingOpponentTokenAndReadinessRemainUnchangedAfterPromotion() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        // White marks ready
        game.setPlayerReady("WHITE");
        assertTrue(game.isWhiteReady());

        // Black leaves
        game.releasePlayerSeat(blackToken, "BLACK");
        assertTrue(game.isWhiteReady(), "White readiness must persist after Black leaves");

        // Spectator promoted to Black
        String promotedBlack = game.assignPlayer("SPECTATOR");
        assertTrue(promotedBlack.startsWith("BLACK-"));

        // White's token and readiness are unchanged
        assertEquals(whiteToken, game.assignPlayer(whiteToken));
        assertTrue(game.isWhiteReady(), "White readiness must remain true after spectator promotion");
        assertFalse(game.isBlackReady(), "Promoted player must start with unready state");
    }

    // 10. Two spectators simultaneously attempting promotion cannot claim the same seat.
    @Test
    public void test10_TwoSpectatorsSimultaneouslyAttemptingPromotionCannotClaimSameSeat() throws Exception {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        // Register two spectators
        String spec1 = game.assignPlayer("SPECTATOR-1");
        String spec2 = game.assignPlayer("SPECTATOR-2");
        assertEquals("SPECTATOR", spec1);
        assertEquals("SPECTATOR", spec2);

        // Release Black seat
        game.releasePlayerSeat(blackToken, "BLACK");

        // Two concurrent threads try to claim the seat
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        Future<String> f1 = executor.submit(() -> {
            startLatch.await();
            return game.assignPlayer("SPECTATOR-1");
        });
        Future<String> f2 = executor.submit(() -> {
            startLatch.await();
            return game.assignPlayer("SPECTATOR-2");
        });

        startLatch.countDown();
        String res1 = f1.get(5, TimeUnit.SECONDS);
        String res2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one should be promoted to Black, and the other remains SPECTATOR
        boolean exactlyOnePromoted = (res1.startsWith("BLACK-") && res2.equals("SPECTATOR")) ||
                                     (res2.startsWith("BLACK-") && res1.equals("SPECTATOR"));
        assertTrue(exactlyOnePromoted, "Exactly one spectator should receive BLACK, the other must remain SPECTATOR");
        assertNotEquals(res1, res2);

        String winnerToken = res1.startsWith("BLACK-") ? res1 : res2;
        assertTrue(game.isPlayerTokenActive(winnerToken));
        assertEquals("BLACK", game.getPlayerColor(winnerToken));
    }

    // 11. A spectator cannot become a player by promotion during an active match if existing rules prohibit it.
    @Test
    public void test11_SpectatorCannotBecomePlayerByPromotionDuringActiveMatch() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        assertTrue(game.isMatchStarted(), "Match should be active");
        assertFalse(game.hasMatchEnded(), "Match should not be ended");

        // During an active match, promotion requests must be rejected
        String result = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", result);

        String result2 = game.assignPlayer(null);
        assertEquals("SPECTATOR", result2);
    }

    // 12. A promoted spectator can refresh and reconnect as their new player role.
    @Test
    public void test12_PromotedSpectatorCanRefreshAndReconnectAsNewPlayerRole() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        game.releasePlayerSeat(blackToken, "BLACK");

        String promotedToken = game.assignPlayer("SPECTATOR");
        assertTrue(promotedToken.startsWith("BLACK-"));

        // Browser refreshes and passes its newly assigned token
        String reconnectedToken = game.assignPlayer(promotedToken);
        assertEquals(promotedToken, reconnectedToken, "Should reconnect as BLACK with same token");
        assertTrue(game.isPlayerTokenActive(reconnectedToken));
        assertEquals("BLACK", game.getPlayerColor(reconnectedToken));
    }
}
