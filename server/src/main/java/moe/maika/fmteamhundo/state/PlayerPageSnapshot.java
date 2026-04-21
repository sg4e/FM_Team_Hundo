package moe.maika.fmteamhundo.state;

import java.util.List;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;

public record PlayerPageSnapshot(long playerId, long version, String playerName, int teamId, List<PlayerUpdate> latestUpdates) { }
