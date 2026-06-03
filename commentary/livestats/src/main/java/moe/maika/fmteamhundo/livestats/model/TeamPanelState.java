package moe.maika.fmteamhundo.livestats.model;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import moe.maika.fmteamhundo.livestats.api.CardAcquisition;
import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.PlayerUpdate;
import moe.maika.fmteamhundo.livestats.api.TeamJson;

public class TeamPanelState {
    private final TeamJson team;
    private final ObservableList<PlayerRowState> players;
    private final Map<Long, PlayerRowState> playerRowsById;
    private final ObjectProperty<LibraryUpdate> libraryUpdate = new SimpleObjectProperty<>();

    public TeamPanelState(TeamJson team, List<PlayerJson> teamPlayers, LibraryUpdate libraryUpdate) {
        this.team = team;
        List<PlayerRowState> rows = teamPlayers.stream()
            .sorted(Comparator.comparing(PlayerJson::name, String.CASE_INSENSITIVE_ORDER))
            .map(PlayerRowState::new)
            .toList();
        this.players = FXCollections.observableArrayList(rows);
        this.playerRowsById = rows.stream().collect(Collectors.toMap(PlayerRowState::getPlayerId, Function.identity()));
        this.libraryUpdate.set(libraryUpdate);
    }

    public TeamJson team() {
        return team;
    }

    public ObservableList<PlayerRowState> players() {
        return players;
    }

    public ObjectProperty<LibraryUpdate> libraryUpdateProperty() {
        return libraryUpdate;
    }

    public LibraryUpdate getLibraryUpdate() {
        return libraryUpdate.get();
    }

    public Optional<PlayerRowState> getPlayerRow(long playerId) {
        return Optional.ofNullable(playerRowsById.get(playerId));
    }

    public boolean applyPlayerUpdate(PlayerUpdate update, Clock clock) {
        PlayerRowState row = playerRowsById.get(update.participantId());
        if (row == null) {
            return false;
        }
        row.apply(update, clock);
        return true;
    }

    public boolean applyLibraryUpdate(LibraryUpdate update) {
        if (update.teamId() != team.id()) {
            return false;
        }
        applyNewLibraryAdditions(update);
        libraryUpdate.set(update);
        return true;
    }

    private void applyNewLibraryAdditions(LibraryUpdate update) {
        Map<Long, CardAcquisition> latestAcquisitionsByPlayerId = new HashMap<>();
        update.newAcquisitions().forEach(acquisition ->
            latestAcquisitionsByPlayerId.merge(
                acquisition.playerId(),
                acquisition,
                (existing, replacement) -> existing.acquisitionTime().isBefore(replacement.acquisitionTime())
                    ? replacement
                    : existing));
        latestAcquisitionsByPlayerId.forEach((playerId, acquisition) -> {
            PlayerRowState row = playerRowsById.get(playerId);
            if (row != null) {
                row.applyNewLibraryAddition(acquisition);
            }
        });
    }

    public void refreshRelativeTimes(Clock clock) {
        players.forEach(player -> player.refreshRelativeTime(clock));
    }
}
