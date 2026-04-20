package moe.maika.fmteamhundo.data.entities;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import moe.maika.fmteamhundo.api.EmuMessage;
import moe.maika.fmteamhundo.api.MessageType;

@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PlayerUpdate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // IDENTITY over SEQUENCE
    private Long databaseId;
    private int value;
    private MessageType source;
    private long participantId;
    private Instant time;
    private int lastRng;
    private int nowRng;

    public PlayerUpdate(User user, EmuMessage message, Instant time) {
        this.value = message.getValue();
        this.source = message.getType();
        this.participantId = user.getDatabaseId();
        this.time = time;
        this.lastRng = message.getLastRng();
        this.nowRng = message.getNowRng();
    }
}
