package com.chess.engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import java.util.Map;
import java.util.concurrent.*;

@Component
public class WebSocketEventListener {

    // Inject your existing game logic service here
    // private final GameService gameService;
    private final SimpMessageSendingOperations messagingTemplate;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ChessController chessController;

    // A highly efficient thread pool just for background countdowns
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Maps a player's color ("WHITE" or "BLACK") to their active countdown timer
    private final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();

    // Maps a WebSocket Session ID to a player's Color
    private final Map<String, String> sessionToColorMap = new ConcurrentHashMap<>();

    public WebSocketEventListener(SimpMessageSendingOperations messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // ==========================================
    // 1. HANDLE DISCONNECTS (The 60-Second Timer)
    // ==========================================
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        // Find out who just lost connection
        String droppedColor = sessionToColorMap.get(sessionId);
        if (droppedColor == null || droppedColor.equals("SPECTATOR")) {
            return; // We don't care if a spectator drops
        }

        System.out.println("⚠️ WARNING: " + droppedColor + " disconnected. Starting 60-second grace period...");

        // Start the 60-second Doomsday Clock
        // Start the 60-second Doomsday Clock
        ScheduledFuture<?> doomsdayClock = scheduler.schedule(() -> {

            // IF THIS CODE RUNS: They did not reconnect in time!
            System.out.println("💥 TIMEOUT: " + droppedColor + " abandoned the match. Awarding win to opponent...");

            // THE FIX: Trigger the official win logic!
            chessController.handleAbandonment(droppedColor);

            disconnectTimers.remove(droppedColor);
            sessionToColorMap.remove(sessionId);

        }, 60, TimeUnit.SECONDS);

        // Store the timer so we can cancel it if they come back!
        disconnectTimers.put(droppedColor, doomsdayClock);
    }

    // ==========================================
    // 2. HANDLE RECONNECTS (Canceling the Timer)
    // ==========================================
    public void registerPlayerSession(String sessionId, String color) {
        // Save their new session ID
        sessionToColorMap.put(sessionId, color);

        // Check if they were on death row
        ScheduledFuture<?> activeTimer = disconnectTimers.get(color);

        if (activeTimer != null) {
            // CANCEL THE TIMER! They made it back.
            activeTimer.cancel(false);
            disconnectTimers.remove(color);
            System.out.println("✅ RECONNECTED: " + color + " returned to the game. Timer canceled.");
        }
    }
}