package moe.maika.fmteamhundo.state;

public interface StateChangeListener {
    void onTeamStateChanged(int teamId);
    void onPlayerStateChanged(long playerId);
    void onOverallStateChanged();
}
