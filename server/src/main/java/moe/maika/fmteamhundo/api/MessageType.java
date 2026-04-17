package moe.maika.fmteamhundo.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum MessageType {
    @JsonProperty("drop")
    DROP,
    @JsonProperty("fuse")
    FUSE,
    @JsonProperty("ritual")
    RITUAL,
    @JsonProperty("starchips")
    STARCHIPS;
}
