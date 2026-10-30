package com.chess.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RematchSystemTest {

    private Game game;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    public void setUp() {
        game = new Game();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        game.setMessagingTemplate(messagingTemplate);
    }

    private void startAndEndGame() {
        game.assignPlayer(null); // White
        game.assignPlayer(null); // Black
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // Make at least one move to ensure board and move history have content
        game.playerMove(1, 4, 3, 4, null); // 1. e4
        game.addMoveToHistory(Map.of("san", "e4", "status", "White moved"));
        // White resigns to end the game cleanly
        game.resign("WHITE");
        assertTrue(game.hasMatchEnded(), "Game should be ended");
        assertFalse(game.isMatchStarted(), "Match should no longer be active");
    }

    // Scenario 1: White requests, Black accepts -> starts new match, identities preserved
    @Test
    public void testWhiteRequestsBlackAcceptsStartsNewMatch() {
        startAndEndGame();
        String originalWhiteToken = game.assignPlayer(null);
        String originalBlackToken = game.assignPlayer(null);

        Map<String, Object> reqResult = game.requestRematch("WHITE");
        assertTrue((Boolean) reqResult.get("success"), "White request should succeed");
        assertFalse((Boolean) reqResult.get("matchStarted"), "Match should not start until Black accepts");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());
        assertEquals("WHITE", game.getRematchRequester());
        assertEquals("BLACK", game.getRematchTarget());

        boolean accepted = game.acceptRematch("BLACK");
        assertTrue(accepted, "Black accept should succeed");
        assertTrue(game.isMatchStarted(), "Match should start after Black accepts");
        assertFalse(game.hasMatchEnded(), "New match should not be ended");

        // Player seats and tokens preserved
        assertEquals(originalWhiteToken, game.assignPlayer(originalWhiteToken));
        assertEquals(originalBlackToken, game.assignPlayer(originalBlackToken));
        assertEquals(Game.RematchStatus.NONE, game.getRematchStatus());
        assertEquals(Color.WHITE, game.getCurrentTurn());
    }

    // Scenario 2: Black requests, White accepts (symmetrical test)
    @Test
    public void testBlackRequestsWhiteAcceptsStartsNewMatch() {
        startAndEndGame();

        Map<String, Object> reqResult = game.requestRematch("BLACK");
        assertTrue((Boolean) reqResult.get("success"), "Black request should succeed");
        assertFalse((Boolean) reqResult.get("matchStarted"), "Match should not start until White accepts");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());
        assertEquals("BLACK", game.getRematchRequester());
        assertEquals("WHITE", game.getRematchTarget());

        boolean accepted = game.acceptRematch("WHITE");
        assertTrue(accepted, "White accept should succeed");
        assertTrue(game.isMatchStarted(), "Match should start after White accepts");
        assertFalse(game.hasMatchEnded(), "New match should not be ended");
        assertEquals(Game.RematchStatus.NONE, game.getRematchStatus());
    }

    // Scenario 3: White requests, Black declines -> no reset, board preserved
    @Test
    public void testWhiteRequestsBlackDeclines() {
        startAndEndGame();
        int initialHistorySize = game.getMoveHistory().size();
        assertTrue(initialHistorySize > 0, "Move history should have moves");

        game.requestRematch("WHITE");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());

        boolean declined = game.declineRematch("BLACK");
        assertTrue(declined, "Black should be able to decline");
        assertEquals(Game.RematchStatus.DECLINED, game.getRematchStatus());
        assertFalse(game.isMatchStarted(), "Match must NOT start when declined");
        assertTrue(game.hasMatchEnded(), "Game remains ended");

        // Board and move history preserved for analysis
        assertEquals(initialHistorySize, game.getMoveHistory().size());
    }

    // Scenario 4: Black requests, White declines (symmetrical test)
    @Test
    public void testBlackRequestsWhiteDeclines() {
        startAndEndGame();
        int initialHistorySize = game.getMoveHistory().size();

        game.requestRematch("BLACK");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());

        boolean declined = game.declineRematch("WHITE");
        assertTrue(declined, "White should be able to decline");
        assertEquals(Game.RematchStatus.DECLINED, game.getRematchStatus());
        assertFalse(game.isMatchStarted(), "Match must NOT start when declined");
        assertEquals(initialHistorySize, game.getMoveHistory().size());
    }

    // Scenario 5: Expiration after 15 seconds -> request invalidated, no reset
    @Test
    public void testRematchRequestExpiresAfter15Seconds() {
        startAndEndGame();

        game.requestRematch("WHITE");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());

        // Simulate 15-second deadline expiration
        game.expireRematchForTesting();

        assertEquals(Game.RematchStatus.EXPIRED, game.getRematchStatus());
        assertFalse(game.isMatchStarted(), "Expired request must not start match");
        assertTrue(game.hasMatchEnded(), "Match remains ended");
    }

    // Scenario 6: Requester can cancel pending request -> opponent card removed, no reset
    @Test
    public void testRequesterCanCancelPendingRematch() {
        startAndEndGame();

        game.requestRematch("WHITE");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());

        boolean cancelled = game.cancelRematch("WHITE");
        assertTrue(cancelled, "Requester should be able to cancel request");
        assertEquals(Game.RematchStatus.CANCELLED, game.getRematchStatus());

        // Target cannot accept cancelled request
        boolean accepted = game.acceptRematch("BLACK");
        assertFalse(accepted, "Target cannot accept a cancelled request");
        assertFalse(game.isMatchStarted());
    }

    // Scenario 7: Duplicate request handling -> repeated request does not create duplicate timer or overwrite
    @Test
    public void testDuplicateRequestHandlingIdempotent() {
        startAndEndGame();

        Map<String, Object> firstReq = game.requestRematch("WHITE");
        assertEquals(Game.RematchStatus.PENDING, game.getRematchStatus());
        long firstDeadline = game.getRematchDeadline();

        Map<String, Object> secondReq = game.requestRematch("WHITE");
        assertTrue((Boolean) secondReq.get("alreadyPending"), "Should detect request already pending");
        assertEquals(firstDeadline, game.getRematchDeadline(), "Deadline should not be overwritten");
    }

    // Scenario 8: Late acceptance after expiry is rejected
    @Test
    public void testLateAcceptanceAfterExpiryRejected() {
        startAndEndGame();

        game.requestRematch("WHITE");
        game.expireRematchForTesting();
        assertEquals(Game.RematchStatus.EXPIRED, game.getRematchStatus());

        boolean accepted = game.acceptRematch("BLACK");
        assertFalse(accepted, "Accepting an expired request must fail");
        assertFalse(game.isMatchStarted());
    }

    // Scenario 9: Simultaneous reciprocal requests cleanly accept and start match
    @Test
    public void testSimultaneousReciprocalRequestsCleanlyAccepts() {
        startAndEndGame();

        Map<String, Object> whiteReq = game.requestRematch("WHITE");
        assertFalse((Boolean) whiteReq.get("matchStarted"));

        // Black clicks Rematch while White's is pending
        Map<String, Object> blackReq = game.requestRematch("BLACK");
        assertTrue((Boolean) blackReq.get("matchStarted"), "Reciprocal request should accept and start match");
        assertTrue(game.isMatchStarted());
    }

    // Scenario 10: Reconnection state synchronization via getRematchState
    @Test
    public void testReconnectionStateSynchronization() {
        startAndEndGame();

        game.requestRematch("WHITE");
        Map<String, Object> state = game.getRematchState();

        assertEquals("PENDING", state.get("status"));
        assertEquals("WHITE", state.get("requester"));
        assertEquals("BLACK", state.get("target"));
        assertTrue((Long) state.get("deadline") > 0);
        assertTrue((Integer) state.get("remainingSeconds") > 0);
    }

    // Scenario 11: Spectator rejection -> cannot request, accept, or decline
    @Test
    public void testSpectatorCannotRequestAcceptOrDecline() {
        startAndEndGame();

        Map<String, Object> specReq = game.requestRematch("SPECTATOR");
        assertFalse((Boolean) specReq.get("success"), "Spectator cannot request rematch");

        game.requestRematch("WHITE");
        boolean specAccept = game.acceptRematch("SPECTATOR");
        assertFalse(specAccept, "Spectator cannot accept rematch");

        boolean specDecline = game.declineRematch("SPECTATOR");
        assertFalse(specDecline, "Spectator cannot decline rematch");

        boolean specCancel = game.cancelRematch("SPECTATOR");
        assertFalse(specCancel, "Spectator cannot cancel rematch");
    }

    // Scenario 12: ChessController integration with mutual confirmation endpoints
    @Test
    public void testChessControllerEndpointsMutualConfirmation() {
        ChessController controller = new ChessController();
        controller.setMessagingTemplate(messagingTemplate);
        Game ctrlGame = controller.getGame();
        ctrlGame.setMessagingTemplate(messagingTemplate);

        // Setup ended game
        ctrlGame.assignPlayer(null);
        ctrlGame.assignPlayer(null);
        ctrlGame.setPlayerReady("WHITE");
        ctrlGame.setPlayerReady("BLACK");
        ctrlGame.startAbortTimer(messagingTemplate);
        ctrlGame.resign("WHITE");

        // White calls /rematch
        Map<String, Object> whiteRes = controller.playerRematch("WHITE");
        assertEquals("PENDING", whiteRes.get("status"));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/game"), any(Object.class));

        // Black calls /rematch/accept
        Map<String, Object> acceptRes = controller.acceptRematchEndpoint("BLACK");
        assertTrue((Boolean) acceptRes.get("success"));
        assertTrue((Boolean) acceptRes.get("matchStarted"));
        assertTrue(ctrlGame.isMatchStarted());
    }
}
