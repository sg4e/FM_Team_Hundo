package moe.maika.fmteamhundo.livestats.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.model.PlayerRowState;
import moe.maika.fmteamhundo.livestats.model.TeamPanelState;

public class TeamPanelView {
    public static final double PANEL_WIDTH = 320.0;
    private static final double TABLE_ROW_HEIGHT = 28.0;
    private static final int VISIBLE_ROWS = 8;

    public VBox create(TeamPanelState state) {
        Label title = new Label(state.team().name());
        title.getStyleClass().add("team-title");

        TableView<PlayerRowState> table = createTable(state);
        VBox libraryStats = createLibraryStats(state);

        VBox panel = new VBox(10, title, table, libraryStats);
        panel.setPadding(new Insets(12));
        panel.setPrefWidth(PANEL_WIDTH);
        panel.setMinWidth(PANEL_WIDTH);
        panel.setMaxWidth(PANEL_WIDTH);
        panel.getStyleClass().add("team-panel");
        return panel;
    }

    private TableView<PlayerRowState> createTable(TeamPanelState state) {
        TableView<PlayerRowState> table = new TableView<>(state.players());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(TABLE_ROW_HEIGHT);
        table.setPrefHeight(TABLE_ROW_HEIGHT * (VISIBLE_ROWS + 1.4));
        table.setMinHeight(TABLE_ROW_HEIGHT * (VISIBLE_ROWS + 1.4));
        table.setMaxHeight(TABLE_ROW_HEIGHT * (VISIBLE_ROWS + 1.4));

        TableColumn<PlayerRowState, String> player = new TableColumn<>("Player");
        player.setCellValueFactory(data -> data.getValue().playerNameProperty());
        player.setPrefWidth(112);

        TableColumn<PlayerRowState, String> source = new TableColumn<>("Source");
        source.setCellValueFactory(data -> data.getValue().sourceTextProperty());
        source.setPrefWidth(70);

        TableColumn<PlayerRowState, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(data -> data.getValue().valueTextProperty());
        value.setPrefWidth(58);

        TableColumn<PlayerRowState, String> time = new TableColumn<>("Time");
        time.setCellValueFactory(data -> data.getValue().relativeTimeTextProperty());
        time.setPrefWidth(80);

        table.getColumns().add(player);
        table.getColumns().add(source);
        table.getColumns().add(value);
        table.getColumns().add(time);
        table.setRowFactory(_ -> {
            TableRow<PlayerRowState> row = new TableRow<>();
            ChangeListener<Boolean> highlightListener = (_, _, highlighted) ->
                row.setStyle(Boolean.TRUE.equals(highlighted) ? "-fx-background-color: #fff176;" : "");
            row.itemProperty().addListener((_, oldItem, newItem) -> {
                if (oldItem != null) {
                    oldItem.highlightedProperty().removeListener(highlightListener);
                }
                if (newItem == null) {
                    row.setStyle("");
                } else {
                    newItem.highlightedProperty().addListener(highlightListener);
                    row.setStyle(newItem.isHighlighted() ? "-fx-background-color: #fff176;" : "");
                }
            });
            return row;
        });
        VBox.setVgrow(table, Priority.NEVER);
        return table;
    }

    private VBox createLibraryStats(TeamPanelState state) {
        VBox stats = new VBox(4);
        stats.getStyleClass().add("library-stats");
        stats.getChildren().addAll(
            boundStat("Unique", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).uniqueCardCount()),
                state.libraryUpdateProperty())),
            boundStat("Starchips", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).totalStarchips()),
                state.libraryUpdateProperty())),
            boundStat("BEWD", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).bewdCount()),
                state.libraryUpdateProperty())),
            boundStat("Unobtained", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).totalUnobtained()),
                state.libraryUpdateProperty())),
            boundStat("Unbuyables", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).totalUnbuyables()),
                state.libraryUpdateProperty())),
            boundStat("Buyable Cost", Bindings.createStringBinding(
                () -> String.valueOf(nullSafe(state.getLibraryUpdate()).totalCostOfBuyables()),
                state.libraryUpdateProperty())),
            boundStat("Affordable", Bindings.createStringBinding(
                () -> yesNo(nullSafe(state.getLibraryUpdate()).canAffordRemainingBuyables()),
                state.libraryUpdateProperty())),
            boundStat("Complete", Bindings.createStringBinding(
                () -> yesNo(nullSafe(state.getLibraryUpdate()).hasCompletedHundo()),
                state.libraryUpdateProperty()))
        );
        return stats;
    }

    private Label boundStat(String label, StringBinding value) {
        Label stat = new Label();
        stat.textProperty().bind(Bindings.concat(label, ": ", value));
        stat.getStyleClass().add("library-stat");
        return stat;
    }

    private static LibraryUpdate nullSafe(LibraryUpdate update) {
        if (update != null) {
            return update;
        }
        return new LibraryUpdate(0, null, 0, 0, java.util.List.of(), 0, 0, 0, false, false, null, 0);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }
}
