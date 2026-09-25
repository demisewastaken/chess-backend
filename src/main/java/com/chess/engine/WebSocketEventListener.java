package com.chess.engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class WebSocketEventListener {

    private final SimpMessageSendingOperations messagingTemplate;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ChessController chessController;

    // Dedicated thread pool for background countdowns
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Maps a player's color ("WHITE" or "BLACK") to their active countdown timer
    public static final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();

    // Maps a WebSocket Session ID to a player's color string
    private final Map<String, String> sessionToColorMap = new ConcurrentHashMap<>();

    public WebSocketEventListener(SimpMessageSendingOperations messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // ==========================================
    // 1. HANDLE DISCONNECTS (The 60-Second Grace Period)
    // ==========================================
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        String droppedColor = sessionToColorMap.get(sessionId);
        if (droppedColor == null || droppedColor.equals("SPECTATOR")) {
            return; // Spectators dropping don't affect the game
        }

        // Only start the timer if a game is actually in progress
        if (!chessController.isMatchActive()) {
            System.out.println("ℹ️ " + droppedColor + " disconnected but match is not active. No timer started.");
            sessionToColorMap.remove(sessionId);
            return;
        }

        System.out.println("⚠️ WARNING: " + droppedColor + " disconnected. Starting 60-second grace period...");

        Map<String, String> warningPayload = new HashMap<>();
        warningPayload.put("type", "DISCONNECT_WARNING");
        warningPayload.put("color", droppedColor);
        messagingTemplate.convertAndSend("/topic/game", (Object) warningPayload);

        // Start the 60-second Doomsday Clock
        ScheduledFuture<?> doomsdayClock = scheduler.schedule(() -> {
            System.out.println("💥 TIMEOUT: " + droppedColor + " abandoned the match. Awarding win to opponent...");
            chessController.handleAbandonment(droppedColor);
            disconnectTimers.remove(droppedColor);
            sessionToColorMap.remove(sessionId);
        }, 60, TimeUnit.SECONDS);

        disconnectTimers.put(droppedColor, doomsdayClock);
    }

    // ==========================================
    // 2. HANDLE RECONNECTS (Canceling the Timer)
    // ==========================================
    public void registerPlayerSession(String sessionId, String color) {
        // Save the new session ID (replaces the old one on reconnect)
        sessionToColorMap.put(sessionId, color);

        // Check if they were on death row
        ScheduledFuture<?> activeTimer = disconnectTimers.get(color);

        if (activeTimer != null) {
            activeTimer.cancel(false);
            disconnectTimers.remove(color);
            System.out.println("✅ RECONNECTED: " + color + " returned to the game. Timer canceled.");

            Map<String, String> reconnectPayload = new HashMap<>();
            reconnectPayload.put("type", "RECONNECT_SUCCESS");
            reconnectPayload.put("color", color);
            messagingTemplate.convertAndSend("/topic/game", (Object) reconnectPayload);
        }
    }
}