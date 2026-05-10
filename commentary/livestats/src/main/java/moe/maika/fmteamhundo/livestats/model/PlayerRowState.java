package moe.maika.fmteamhundo.livestats.model;

import java.time.Clock;
import java.time.Instant;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import moe.maika.fmteamhundo.livestats.api.MessageType;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.PlayerUpdate;

public class PlayerRowState {
    private final long playerId;
    private final StringProperty playerName = new SimpleStringProperty();
    private final ObjectProperty<MessageType> source = new SimpleObjectProperty<>();
    private final ObjectProperty<Integer> value = new SimpleObjectProperty<>();
    private final ObjectProperty<Integer> opponentId = new SimpleObjectProperty<>();
    private final ObjectProperty<Instant> updateTime = new SimpleObjectProperty<>();
    private final StringProperty sourceText = new SimpleStringProperty("");
    private final StringProperty valueText = new SimpleStringProperty("");
    private final StringProperty opponentText = new SimpleStringProperty("");
    private final StringProperty relativeTimeText = new SimpleStringProperty("");
    private final BooleanProperty highlighted = new SimpleBooleanProperty(false);

    public PlayerRowState(PlayerJson player) {
        this.playerId = player.id();
        this.playerName.set(player.name());
    }

    public long getPlayerId() {
        return playerId;
    }

    public StringProperty playerNameProperty() {
        return playerName;
    }

    public StringProperty sourceTextProperty() {
        return sourceText;
    }

    public StringProperty valueTextProperty() {
        return valueText;
    }

    public StringProperty opponentTextProperty() {
        return opponentText;
    }

    public StringProperty relativeTimeTextProperty() {
        return relativeTimeText;
    }

    public BooleanProperty highlightedProperty() {
        return highlighted;
    }

    public boolean isHighlighted() {
        return highlighted.get();
    }

    public void setHighlighted(boolean highlighted) {
        this.highlighted.set(highlighted);
    }

    public void apply(PlayerUpdate update, Clock clock) {
        source.set(update.source());
        value.set(update.value());
        opponentId.set(update.opponentId());
        updateTime.set(update.time());
        sourceText.set(update.source() == null ? "" : update.source().toString());
        valueText.set(String.valueOf(update.value()));
        opponentText.set(String.valueOf(update.opponentId()));
        refreshRelativeTime(clock);
    }

    public void refreshRelativeTime(Clock clock) {
        relativeTimeText.set(RelativeTimeFormatter.format(updateTime.get(), clock));
    }
}
