package moe.maika.fmteamhundo.state;

import moe.maika.fmteamhundo.api.LibraryUpdate;

public interface TeamUpdateListener {
    public void onTeamUpdate(LibraryUpdate snapshot);
}
