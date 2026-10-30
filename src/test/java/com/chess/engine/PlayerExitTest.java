package com.chess.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PlayerExitTest {

    private Game game;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    public void setUp() {
        game = new Game();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        game.setMessagingTemplate(messagingTemplate);
    }

    // 1. WHITE leaves before the match starts; their old token becomes invalid.
    @Test
    public void test1_WhiteLeavesBeforeMatchStarts_OldTokenInvalid() {
        String whiteToken = game.assignPlayer(null);
        assertTrue(whiteToken.startsWith("WHITE-"));
        assertTrue(game.isPlayerTokenActive(whiteToken));

        boolean released = game.releasePlayerSeat(whiteToken, "WHITE");
        assertTrue(released, "White's seat should be successfully released");
        assertTrue(game.isTokenRevoked(whiteToken), "Old token should be recorded as revoked");
        assertFalse(game.isPlayerTokenActive(whiteToken), "Old token must no longer be an active player token");
        assertFalse(game.isWhiteReady(), "White readiness should be reset");
    }

    // 2. The same browser joins again and receives a new token.
    @Test
    public void test2_SameBrowserJoinsAgain_ReceivesNewToken() {
        String oldWhiteToken = game.assignPlayer(null);
        game.releasePlayerSeat(oldWhiteToken, "WHITE");

        // Browser calls /join with empty token or with oldToken
        String newToken = game.assignPlayer(null);
        assertTrue(newToken.startsWith("WHITE-"));
        assertNotEquals(oldWhiteToken, newToken, "Must be issued a brand new token");
        assertTrue(game.isPlayerTokenActive(newToken));
        assertFalse(game.isPlayerTokenActive(oldWhiteToken));
    }

    // 3. WHITE leaves while BLACK remains; a new player receives WHITE.
    @Test
    public void test3_WhiteLeavesWhileBlackRemains_NewPlayerReceivesWhite() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        boolean released = game.releasePlayerSeat(whiteToken, "WHITE");
        assertTrue(released);

        // Black seat still occupied by Black
        assertTrue(game.isPlayerTokenActive(blackToken));
        assertEquals(blackToken, game.assignPlayer(blackToken));

        // New player joins -> receives WHITE
        String newWhiteToken = game.assignPlayer(null);
        assertTrue(newWhiteToken.startsWith("WHITE-"));
        assertNotEquals(whiteToken, newWhiteToken);
    }

    // 4. BLACK leaves while WHITE remains; a new player receives BLACK.
    @Test
    public void test4_BlackLeavesWhileWhiteRemains_NewPlayerReceivesBlack() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        boolean released = game.releasePlayerSeat(blackToken, "BLACK");
        assertTrue(released);

        // White seat still occupied by White
        assertTrue(game.isPlayerTokenActive(whiteToken));
        assertEquals(whiteToken, game.assignPlayer(whiteToken));

        // New player joins -> receives BLACK
        String newBlackToken = game.assignPlayer(null);
        assertTrue(newBlackToken.startsWith("BLACK-"));
        assertNotEquals(blackToken, newBlackToken);
    }

    // 5. Both players leave; the next player receives a clean WHITE seat and initial lobby state.
    @Test
    public void test5_BothPlayersLeave_NextPlayerReceivesCleanWhiteSeatAndInitialLobby() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        game.setPlayerReady("WHITE");
        game.releasePlayerSeat(whiteToken, "WHITE");
        game.releasePlayerSeat(blackToken, "BLACK");

        // Verify both seats empty
        assertFalse(game.isPlayerTokenActive(whiteToken));
        assertFalse(game.isPlayerTokenActive(blackToken));
        assertFalse(game.isWhiteReady());
        assertFalse(game.isBlackReady());
        assertFalse(game.isMatchStarted());
        assertFalse(game.hasMatchEnded());
        assertEquals("White's Turn", game.getCurrentStatus());
        assertEquals(0, game.getMoveHistory().size());

        // Next player joins -> gets clean WHITE seat
        String nextToken = game.assignPlayer(null);
        assertTrue(nextToken.startsWith("WHITE-"));
        assertEquals("White's Turn", game.getCurrentStatus());
    }

    // 6. Both seats are occupied; a third player receives spectator mode.
    @Test
    public void test6_BothSeatsOccupied_ThirdPlayerReceivesSpectator() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);
        assertTrue(whiteToken.startsWith("WHITE-"));
        assertTrue(blackToken.startsWith("BLACK-"));

        String thirdPlayer = game.assignPlayer(null);
        assertEquals("SPECTATOR", thirdPlayer, "Third player must receive SPECTATOR");
    }

    // 7. A spectator cannot accidentally claim a player seat.
    @Test
    public void test7_SpectatorCannotClaimPlayerSeat() {
        String whiteToken = game.assignPlayer(null);
        String blackToken = game.assignPlayer(null);

        // Requesting with SPECTATOR token
        String result = game.assignPlayer("SPECTATOR");
        assertEquals("SPECTATOR", result);

        // Even with invalid random spectator token
        String result2 = game.assignPlayer("SPECTATOR-random-id");
        assertEquals("SPECTATOR", result2);
    }

    // 8. An old token cannot reconnect after its seat has been released.
    @Test
    public void test8_OldTokenCannotReconnectAfterSeatReleased() {
        String whiteToken = game.assignPlayer(null);
        game.releasePlayerSeat(whiteToken, "WHITE");

        // Suppose someone else claimed White and Black
        String newWhite = game.assignPlayer(null);
        String newBlack = game.assignPlayer(null);

        // Old White tries to reconnect using old token
        String outcome = game.assignPlayer(whiteToken);
        assertEquals("SPECTATOR", outcome, "Old released token must not reclaim seat; treated as spectator");
        assertNotEquals(whiteToken, newWhite);
        assertFalse(game.isPlayerTokenActive(whiteToken));
    }

    // 9. Calling the leave endpoint twice does not release another player's seat.
    @Test
    public void test9_CallingLeaveTwiceDoesNotReleaseAnotherPlayerSeat() {
        String white1 = game.assignPlayer(null);
        assertTrue(game.releasePlayerSeat(white1, "WHITE"));

        // Second call with same token returns false
        assertFalse(game.releasePlayerSeat(white1, "WHITE"), "Second call on released seat must return false");

        // Now a new player claims White
        String white2 = game.assignPlayer(null);
        assertTrue(white2.startsWith("WHITE-"));

        // Old player calls release again with old token -> must NOT release white2's seat!
        assertFalse(game.releasePlayerSeat(white1, "WHITE"));
        assertTrue(game.isPlayerTokenActive(white2), "New player's seat must remain intact");
    }

    // 10. A player cannot use the pre-match leave endpoint to abandon an active match.
    @Test
    public void test10_CannotUsePreMatchLeaveToAbandonActiveMatch() {
        String white = game.assignPlayer(null);
        String black = game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        assertTrue(game.isMatchStarted());
        assertFalse(game.hasMatchEnded());

        // Attempt pre-match leave while game is actively running
        assertFalse(game.releasePlayerSeat(white, "WHITE"), "Must be rejected when match is active");
        assertFalse(game.releasePlayerSeat(black, "BLACK"), "Must be rejected when match is active");
        assertTrue(game.isPlayerTokenActive(white));
        assertTrue(game.isPlayerTokenActive(black));
    }

    // 11. The remaining player's readiness and connection are preserved when the other player leaves.
    @Test
    public void test11_RemainingPlayerReadinessPreservedWhenOtherLeaves() {
        String white = game.assignPlayer(null);
        String black = game.assignPlayer(null);

        // White clicks ready and enters waiting state
        game.setPlayerReady("WHITE");
        assertTrue(game.isWhiteReady());
        assertFalse(game.isBlackReady());

        // Black leaves before readying up
        assertTrue(game.releasePlayerSeat(black, "BLACK"));

        // White readiness MUST be preserved
        assertTrue(game.isWhiteReady(), "White must remain ready and waiting");
        assertTrue(game.isPlayerTokenActive(white), "White token must remain valid");

        // New Black joins and clicks ready
        String black2 = game.assignPlayer(null);
        boolean started = game.setPlayerReady("BLACK");
        assertTrue(started, "Match should start because White was already ready");
        assertTrue(game.isMatchStarted());
    }

    // 12. Post-game Leave Table still behaves exactly as before.
    @Test
    public void test12_PostGameLeaveTableBehavesExactlyAsBefore() {
        String white = game.assignPlayer(null);
        String black = game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        assertTrue(game.isMatchStarted());

        // End the game
        game.resign("BLACK");
        assertTrue(game.hasMatchEnded());
        assertFalse(game.isMatchStarted());

        // Call resetGame() as done by /leave
        game.resetGame();

        // Verify game reset
        assertFalse(game.isMatchStarted());
        assertFalse(game.hasMatchEnded());
        assertEquals("White's Turn", game.getCurrentStatus());
        assertEquals(0, game.getMoveHistory().size());
        assertEquals(Color.WHITE, game.getCurrentTurn());
    }
}
