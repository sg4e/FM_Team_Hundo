package moe.maika.fmteamhundo.livestats.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import moe.maika.fmteamhundo.livestats.client.JsonSupport;

class DtoJsonTest {
    private final ObjectMapper objectMapper = JsonSupport.createObjectMapper();

    @Test
    void parsesPlayerUpdateWithInstantAndMessageType() throws Exception {
        String json = """
            {
              "value": 122,
              "source": "drop",
              "participantId": 42,
              "time": "2026-05-09T12:34:56Z",
              "lastRng": 3,
              "nowRng": 4,
              "opponentId": 5
            }
            """;

        PlayerUpdate update = objectMapper.readValue(json, PlayerUpdate.class);

        assertEquals(122, update.value());
        assertEquals(MessageType.DROP, update.source());
        assertEquals(42L, update.participantId());
        assertEquals(Instant.parse("2026-05-09T12:34:56Z"), update.time());
        assertEquals(5, update.opponentId());
    }

    @Test
    void parsesLibraryUpdateAndCardAcquisitionList() throws Exception {
        String json = """
            {
              "teamId": 7,
              "timestamp": "2026-05-09T12:35:00Z",
              "totalStarchips": 250,
              "uniqueCardCount": 1,
              "newAcquisitions": [
                {
                  "cardId": 122,
                  "acquisitionTime": "2026-05-09T12:34:56Z",
                  "source": "fuse",
                  "playerId": 42,
                  "opponentId": 5
                }
              ],
              "totalUnobtained": 721,
              "totalUnbuyables": 300,
              "totalCostOfBuyables": 12345,
              "canAffordRemainingBuyables": false,
              "hasCompletedHundo": false,
              "completionTime": null,
              "bewdCount": 0
            }
            """;

        LibraryUpdate update = objectMapper.readValue(json, LibraryUpdate.class);

        assertEquals(7, update.teamId());
        assertEquals(250L, update.totalStarchips());
        assertEquals(1, update.newAcquisitions().size());
        assertEquals(MessageType.FUSE, update.newAcquisitions().get(0).source());
        assertEquals(42L, update.newAcquisitions().get(0).playerId());
        assertFalse(update.hasCompletedHundo());
    }
}
