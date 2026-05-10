package moe.maika.fmteamhundo.livestats.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum MessageType {
    @JsonProperty("drop")
    DROP("drop"),
    @JsonProperty("fuse")
    FUSE("fuse"),
    @JsonProperty("ritual")
    RITUAL("ritual"),
    @JsonProperty("starchips")
    STARCHIPS("starchips");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
