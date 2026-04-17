package moe.maika.fmteamhundo.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class EmuMessage {
    private MessageType type;
    private int value;
    @JsonProperty("last_rng")
    private int lastRng;
    @JsonProperty("now_rng")
    private int nowRng;
}
