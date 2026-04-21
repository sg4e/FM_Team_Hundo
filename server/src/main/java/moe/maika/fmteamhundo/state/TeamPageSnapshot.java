package moe.maika.fmteamhundo.state;

import java.util.List;
import java.util.Map;

public record TeamPageSnapshot(
    int teamId,
    long version,
    long totalStarchips,
    int uniqueCardCount,
    List<TeamMember> members,
    List<CardAcquisitionView> latestAcquisitions,
    Map<Integer, CardAcquisitionView> acquiredCards
) { }
