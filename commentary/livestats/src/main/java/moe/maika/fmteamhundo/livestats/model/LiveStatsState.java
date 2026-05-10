package moe.maika.fmteamhundo.livestats.model;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.PlayerUpdate;
import moe.maika.fmteamhundo.livestats.api.TeamJson;

public class LiveStatsState {
    private final ObservableList<TeamPanelState> teams;
    private final Map<Integer, TeamPanelState> teamsById;
    private final Map<Long, TeamPanelState> teamsByPlayerId;

    public LiveStatsState(List<TeamJson> teams, List<PlayerJson> players, Map<Integer, LibraryUpdate> librariesByTeamId) {
        Map<Integer, List<PlayerJson>> playersByTeam = players.stream()
            .collect(Collectors.groupingBy(PlayerJson::teamId));

        List<TeamPanelState> panelStates = teams.stream()
            .filter(team -> team.id() > 0)
            .sorted(Comparator.comparingInt(TeamJson::id))
            .map(team -> new TeamPanelState(team, playersByTeam.getOrDefault(team.id(), List.of()), librariesByTeamId.get(team.id())))
            .toList();

        this.teams = FXCollections.observableArrayList(panelStates);
        this.teamsById = panelStates.stream().collect(Collectors.toMap(panel -> panel.team().id(), Function.identity()));
        this.teamsByPlayerId = panelStates.stream()
            .flatMap(panel -> panel.players().stream().map(row -> Map.entry(row.getPlayerId(), panel)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public ObservableList<TeamPanelState> teams() {
        return teams;
    }

    public boolean applyPlayerUpdate(PlayerUpdate update, Clock clock) {
        TeamPanelState team = teamsByPlayerId.get(update.participantId());
        return team != null && team.applyPlayerUpdate(update, clock);
    }

    public boolean applyLibraryUpdate(LibraryUpdate update) {
        TeamPanelState team = teamsById.get(update.teamId());
        return team != null && team.applyLibraryUpdate(update);
    }

    public Optional<PlayerRowState> getPlayerRow(long playerId) {
        TeamPanelState team = teamsByPlayerId.get(playerId);
        return team == null ? Optional.empty() : team.getPlayerRow(playerId);
    }

    public void refreshRelativeTimes(Clock clock) {
        teams.forEach(team -> team.refreshRelativeTimes(clock));
    }
}
