package moe.maika.fmteamhundo.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmuMessage(
        MessageType type,
        int value,
        @JsonProperty("last_rng") Integer lastRng,
        @JsonProperty("now_rng") Integer nowRng,
        @JsonProperty("opp_id") Integer opponentId) {

    public EmuMessage {
        lastRng = lastRng == null ? 0 : lastRng;
        nowRng = nowRng == null ? 0 : nowRng;
        opponentId = opponentId == null ? 0 : opponentId;
    }
}
