package moe.maika.fmteamhundo.livestats;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.PlayerUpdate;
import moe.maika.fmteamhundo.livestats.client.FirehoseClient;
import moe.maika.fmteamhundo.livestats.client.JsonSupport;
import moe.maika.fmteamhundo.livestats.client.LiveStatsApiClient;
import moe.maika.fmteamhundo.livestats.client.LiveStatsConfig;
import moe.maika.fmteamhundo.livestats.model.LiveStatsState;
import moe.maika.fmteamhundo.livestats.model.HighlightTracker;
import moe.maika.fmteamhundo.livestats.model.StartupLoader;
import moe.maika.fmteamhundo.livestats.ui.TeamPanelView;

public class LiveStats extends Application {
    private static final Logger LOGGER = LogManager.getLogger(LiveStats.class);
    private static final Clock CLOCK = Clock.systemDefaultZone();

    private final ObjectMapper objectMapper = JsonSupport.createObjectMapper();
    private final Map<Long, Timeline> highlightTimelines = new HashMap<>();
    private LiveStatsConfig config;
    private FirehoseClient playerFirehose;
    private FirehoseClient teamFirehose;
    private Timeline relativeTimeTicker;
    private final HighlightTracker highlightTracker = new HighlightTracker(java.time.Duration.ofSeconds(10));
    private LiveStatsState state;
    private Label playerStatus;
    private Label teamStatus;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("FM Team Hundo Live Stats");

        try {
            config = LiveStatsConfig.fromArgs(getParameters().getRaw().toArray(String[]::new));
            state = loadState();
            stage.setScene(createScene());
            startRelativeTimeTicker();
            startFirehoses();
        } catch (Exception ex) {
            LOGGER.error("Unable to start LiveStats", ex);
            stage.setScene(createFailureScene(ex));
        }

        stage.show();
    }

    @Override
    public void stop() {
        if (playerFirehose != null) {
            playerFirehose.close();
        }
        if (teamFirehose != null) {
            teamFirehose.close();
        }
        if (relativeTimeTicker != null) {
            relativeTimeTicker.stop();
        }
        highlightTimelines.values().forEach(Animation::stop);
        highlightTimelines.clear();
    }

    private LiveStatsState loadState() throws Exception {
        LiveStatsApiClient apiClient = new LiveStatsApiClient(HttpClient.newHttpClient(), objectMapper, config);
        return new StartupLoader(apiClient).load();
    }

    private Scene createScene() {
        HBox teamPanels = new HBox(0);
        TeamPanelView panelView = new TeamPanelView();
        int visiblePlayerRows = state.teams().stream()
            .mapToInt(team -> team.players().size())
            .max()
            .orElse(0);
        state.teams().forEach(team -> teamPanels.getChildren().add(panelView.create(team, visiblePlayerRows)));

        playerStatus = new Label("Player firehose: disconnected");
        teamStatus = new Label("Team firehose: disconnected");
        HBox status = new HBox(16, playerStatus, teamStatus);
        status.setPadding(new Insets(8, 12, 8, 12));
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("status-bar");
        playerStatus.setId("player-firehose-status");
        teamStatus.setId("team-firehose-status");

        BorderPane root = new BorderPane(teamPanels);
        root.setTop(status);
        root.getStyleClass().add("root-pane");
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/livestats.css").toExternalForm());
        return scene;
    }

    private void startFirehoses() {
        playerFirehose = new FirehoseClient(
            "player",
            config.websocketUri("/firehose/player"),
            this::handlePlayerFirehoseMessage,
            connected -> Platform.runLater(() -> playerStatus.setText("Player firehose: " + statusText(connected))));
        teamFirehose = new FirehoseClient(
            "team",
            config.websocketUri("/firehose/team"),
            this::handleTeamFirehoseMessage,
            connected -> Platform.runLater(() -> teamStatus.setText("Team firehose: " + statusText(connected))));
        playerFirehose.start();
        teamFirehose.start();
    }

    private String statusText(boolean connected) {
        return connected ? "connected" : "disconnected";
    }

    private void handlePlayerFirehoseMessage(String message) {
        try {
            PlayerUpdate update = objectMapper.readValue(message, PlayerUpdate.class);
            Platform.runLater(() -> {
                boolean applied = state.applyPlayerUpdate(update, CLOCK);
                if (!applied) {
                    LOGGER.warn("Ignoring update for unknown player id {}", update.participantId());
                }
            });
        } catch (Exception ex) {
            LOGGER.warn("Unable to parse player firehose payload: {}", message, ex);
        }
    }

    private void handleTeamFirehoseMessage(String message) {
        try {
            LibraryUpdate update = objectMapper.readValue(message, LibraryUpdate.class);
            Platform.runLater(() -> {
                boolean applied = state.applyLibraryUpdate(update);
                if (!applied) {
                    return;
                }
                update.newAcquisitions().forEach(acquisition -> flashPlayer(acquisition.playerId()));
            });
        } catch (Exception ex) {
            LOGGER.warn("Unable to parse team firehose payload: {}", message, ex);
        }
    }

    private void flashPlayer(long playerId) {
        state.getPlayerRow(playerId).ifPresent(row -> {
            Timeline existing = highlightTimelines.remove(playerId);
            if (existing != null) {
                existing.stop();
            }

            highlightTracker.restart(playerId, java.time.Instant.now(CLOCK));
            row.setHighlighted(true);
            Timeline timeline = new Timeline(new KeyFrame(Duration.millis(500), _ -> row.setHighlighted(!row.isHighlighted())));
            timeline.setCycleCount(20);
            timeline.setOnFinished(_ -> {
                row.setHighlighted(false);
                highlightTimelines.remove(playerId);
            });
            highlightTimelines.put(playerId, timeline);
            timeline.play();
        });
    }

    private void startRelativeTimeTicker() {
        relativeTimeTicker = new Timeline(new KeyFrame(Duration.seconds(1), _ -> state.refreshRelativeTimes(CLOCK)));
        relativeTimeTicker.setCycleCount(Animation.INDEFINITE);
        relativeTimeTicker.play();
    }

    private Scene createFailureScene(Exception ex) {
        Label title = new Label("Unable to load LiveStats");
        title.getStyleClass().add("failure-title");
        Label message = new Label(ex.getMessage());
        message.setWrapText(true);
        VBox root = new VBox(10, title, message);
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, 520, 180);
        scene.getStylesheets().add(getClass().getResource("/livestats.css").toExternalForm());
        return scene;
    }
}
