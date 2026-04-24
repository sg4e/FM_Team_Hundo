package moe.maika.fmteamhundo.state;

import java.util.List;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;

public interface PlayerUpdateListener {
    public void onPlayerUpdate(List<PlayerUpdate> updates);
}
