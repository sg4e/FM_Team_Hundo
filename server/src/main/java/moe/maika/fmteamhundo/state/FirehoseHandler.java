package moe.maika.fmteamhundo.state;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * Text WebSocket handler that tracks sessions for a single endpoint.
 */
@Slf4j
@Component
@Scope("prototype")
public class FirehoseHandler extends TextWebSocketHandler {

    private final String endpointPath;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public FirehoseHandler(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket connected: endpoint={}, sessionId={}, remoteAddress={}", endpointPath, session.getId(),
                session.getRemoteAddress());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket disconnected: endpoint={}, sessionId={}, status={}", endpointPath, session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sendMessage(session, new TextMessage(message.getPayload()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error: endpoint={}, sessionId={}", endpointPath, session.getId(), exception);
        sessions.remove(session);
        closeQuietly(session);
    }

    /**
     * Sends a text message to every currently connected session for this endpoint.
     *
     * @param message message payload to send
     */
    public void broadcast(String message) {
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            sendMessage(session, textMessage);
        }
    }

    /**
     * Returns the endpoint path this handler instance is registered for.
     *
     * @return endpoint path
     */
    public String getEndpointPath() {
        return endpointPath;
    }

    /**
     * Returns the number of currently tracked sessions.
     *
     * @return connected session count
     */
    public int getSessionCount() {
        return sessions.size();
    }

    private void sendMessage(WebSocketSession session, TextMessage message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                } else {
                    sessions.remove(session);
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send WebSocket message: endpoint={}, sessionId={}", endpointPath, session.getId(), e);
            sessions.remove(session);
            closeQuietly(session);
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException e) {
            log.debug("Failed to close WebSocket session cleanly: endpoint={}, sessionId={}", endpointPath, session.getId(), e);
        }
    }
}
