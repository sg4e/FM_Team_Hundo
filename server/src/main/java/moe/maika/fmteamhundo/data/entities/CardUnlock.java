package moe.maika.fmteamhundo.data.entities;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
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
public class CardUnlock {
    @Id
    @GeneratedValue
    private long databaseId;
    private int cardId;
    private MessageType source;
    private String participantId;
    private Instant unlockTime;
    private int lastRng;
    private int nowRng;

    public CardUnlock(User user, EmuMessage message, Instant unlockTime) {
        this.cardId = message.getValue();
        this.source = message.getType();
        this.participantId = user.getTwitchId();
        this.unlockTime = unlockTime;
        this.lastRng = message.getLastRng();
        this.nowRng = message.getNowRng();
    }
}
