package moe.maika.fmteamhundo.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmuMessage {
    private MessageType type;
    private int value;
    @JsonProperty("last_rng")
    private int lastRng;
    @JsonProperty("now_rng")
    private int nowRng;
    @JsonProperty("opp_id")
    private int opponentId;
}
