package moe.maika.fmteamhundo.api;

import java.time.Instant;
import java.util.List;

public record CreditsResponse(
    List<TeamCredits> teams,
    AllTeamStats allTeams,
    List<TeamStats> teamStats
) {
    public record TeamCredits(
        int id,
        String name,
        boolean completed,
        Instant completionTime,
        List<PlayerCredits> players
    ) { }

    public record PlayerCredits(long id, String name) { }

    public record AllTeamStats(
        long totalDrops,
        long totalFusions,
        long totalRituals,
        long twinHeadedThunderDragonFusions
    ) { }

    public record TeamStats(
        int teamId,
        List<CountRow> dropCardCounts,
        List<CountRow> fusionCardCounts,
        long heishinDrops,
        long seto3Drops,
        List<CountRow> duelistDropCounts
    ) { }

    public record CountRow(int id, long count) { }
}
