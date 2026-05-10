package moe.maika.fmteamhundo.livestats.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class FirehoseClient implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(FirehoseClient.class);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(2);

    private final String name;
    private final URI uri;
    private final Consumer<String> messageConsumer;
    private final Consumer<Boolean> connectionConsumer;
    private final ScheduledExecutorService executorService;
    private volatile boolean closed;
    private volatile WebSocketClient client;

    public FirehoseClient(String name, URI uri, Consumer<String> messageConsumer, Consumer<Boolean> connectionConsumer) {
        this.name = Objects.requireNonNull(name);
        this.uri = Objects.requireNonNull(uri);
        this.messageConsumer = Objects.requireNonNull(messageConsumer);
        this.connectionConsumer = Objects.requireNonNull(connectionConsumer);
        this.executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "livestats-" + name + "-firehose");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executorService.execute(this::connect);
    }

    @Override
    public void close() {
        closed = true;
        WebSocketClient current = client;
        if (current != null) {
            current.close();
        }
        executorService.shutdownNow();
    }

    private void connect() {
        if (closed) {
            return;
        }
        WebSocketClient nextClient = new ReconnectingWebSocketClient(uri);
        client = nextClient;
        try {
            nextClient.connect();
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to connect {} firehose", name, ex);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!closed) {
            connectionConsumer.accept(Boolean.FALSE);
            executorService.schedule(this::connect, RECONNECT_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private final class ReconnectingWebSocketClient extends WebSocketClient {
        private ReconnectingWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakeData) {
            connectionConsumer.accept(Boolean.TRUE);
        }

        @Override
        public void onMessage(String message) {
            messageConsumer.accept(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            LOGGER.info("{} firehose closed: code={}, reason={}, remote={}", name, code, reason, remote);
            scheduleReconnect();
        }

        @Override
        public void onError(Exception ex) {
            LOGGER.warn("{} firehose error", name, ex);
            scheduleReconnect();
        }
    }
}
