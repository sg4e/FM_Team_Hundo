package moe.maika.fmteamhundo.ui;

import java.time.Instant;
import java.util.List;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.PlayerUpdateListener;
import moe.maika.fmteamhundo.state.UserMappings;
import moe.maika.fmteamhundo.util.RingBuffer;

@Route("players")
@AnonymousAllowed
public class PlayerView extends VerticalLayout implements HasUrlParameter<String>, PlayerUpdateListener {

    private static final int NUMBER_OF_PLAYER_UPDATES_RENDERED = 10;

    private final GameStateService gameStateService;
    private final UserMappings teamMapping;
    private final VerticalLayout content;
    private final RingBuffer<PlayerUpdate> latestUpdates = new RingBuffer<>(NUMBER_OF_PLAYER_UPDATES_RENDERED);

    private Long playerId;
    private UI currentUI;
    private Instant lastStarchipUpdate = Instant.MIN;
    private long currentStarchips = 0;

    public PlayerView(GameStateService gameStateService, UserMappings teamMapping) {
        this.gameStateService = gameStateService;
        this.teamMapping = teamMapping;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);

        add(ViewSupport.createTopBar(), content);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        
        currentUI = attachEvent.getUI();
        currentUI.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
        
        if (playerId != null && playerId > 0) {
            latestUpdates.addAll(gameStateService.getLatestPlayerUpdates(playerId));
            gameStateService.addPlayerUpdateListener(playerId, this);
            loadInitialSnapshot();
        } else {
            renderMissingPlayer();
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        
        if (playerId != null && playerId > 0) {
            gameStateService.removePlayerUpdateListener(playerId, this);
        }
        currentUI = null;
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        try {
            playerId = Long.parseLong(parameter);
        }
        catch(NumberFormatException ex) {
            playerId = -1L;
        }
    }

    @Override
    public void onPlayerUpdate(List<PlayerUpdate> updates) {
        if(currentUI != null) {
            updates.stream().filter(c -> c.getSource() == MessageType.STARCHIPS).max((c1, c2) -> c1.getTime().compareTo(c2.getTime())).ifPresent(starchipUpdate -> {
                if(starchipUpdate.getTime().isAfter(lastStarchipUpdate)) {
                    currentStarchips = gameStateService.getLibrary(teamMapping.getTeamForUserId(playerId).getTeamId()).getStarchips(playerId);
                    lastStarchipUpdate = starchipUpdate.getTime();
                }
            });

            updateLatestUpdates(updates.stream().filter(c -> c.getSource() != MessageType.STARCHIPS).toList());
            currentUI.access(() -> render());
        }
    }

    private void updateLatestUpdates(List<PlayerUpdate> updates) {
        latestUpdates.addAll(updates);
    }

    private void loadInitialSnapshot() {
        if(playerId == null || playerId <= 0) {
            renderMissingPlayer();
            return;
        }
        render();
    }

    private void renderMissingPlayer() {
        content.removeAll();
        content.add(new H1("Player not found"));
    }

    private void render() {
        content.removeAll();
        User player = teamMapping.getUserById(playerId);
        if(player == null) {
            renderMissingPlayer();
            return;
        }
        Team team = teamMapping.getTeamForUserId(playerId);

        content.add(new H1(player.getName()));
        if(!team.isNoTeam()) {
            RouterLink teamLink = new RouterLink("Team " + teamMapping.getTeamNameForTeamId(team.getTeamId()), TeamView.class, String.valueOf(team.getTeamId()));
            content.add(teamLink);
            if(currentStarchips == 0) {
                currentStarchips = gameStateService.getLibrary(team.getTeamId()).getStarchips(playerId);
            }
        }

        content.add(
            ViewSupport.createStarchipsStat(currentStarchips),
            new H3("Latest updates")
        );

        UnorderedList updates = new UnorderedList();
        if(latestUpdates.isEmpty()) {
            updates.add(new ListItem("No recent non-starchip updates."));
        }
        else {
            for(PlayerUpdate update : latestUpdates) {
                updates.add(new ListItem(
                    ViewSupport.formatInstant(update.getTime())
                        + " - " + update.getSource()
                        + " card " + update.getValue()
                ));
            }
        }
        content.add(updates);
    }
}
