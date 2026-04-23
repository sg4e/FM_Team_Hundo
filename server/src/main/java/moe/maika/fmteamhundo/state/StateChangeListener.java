package moe.maika.fmteamhundo.state;

public interface StateChangeListener {
    void onTeamStateChanged(TeamPageSnapshot snapshot);
    void onPlayerStateChanged(PlayerPageSnapshot snapshot);
}
