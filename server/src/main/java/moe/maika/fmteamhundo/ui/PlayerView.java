package moe.maika.fmteamhundo.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.PlayerPageSnapshot;
import moe.maika.fmteamhundo.state.StateChangeListener;
import moe.maika.fmteamhundo.state.TeamMapping;

@Route("players")
@AnonymousAllowed
public class PlayerView extends VerticalLayout implements HasUrlParameter<String>, StateChangeListener {

    private final GameStateService gameStateService;
    private final TeamMapping teamMapping;
    private final VerticalLayout content;

    private Long playerId;
    private long renderedVersion = -1;
    private UI currentUI;

    public PlayerView(GameStateService gameStateService, TeamMapping teamMapping) {
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
        addAttachListener(event -> {
            currentUI = event.getUI();
            currentUI.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
            gameStateService.addStateChangeListener(this);
            refreshIfNeeded();
        });
        addDetachListener(event -> {
            gameStateService.removeStateChangeListener(this);
            currentUI = null;
        });
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        try {
            playerId = Long.parseLong(parameter);
        }
        catch (NumberFormatException ex) {
            playerId = -1L;
        }
        renderedVersion = -1;
        refreshIfNeeded();
    }

    @Override
    public void onTeamStateChanged(int teamId) {
        // PlayerView doesn't need team updates
    }

    @Override
    public void onPlayerStateChanged(long changedPlayerId) {
        if(playerId != null && playerId.equals(changedPlayerId) && currentUI != null) {
            currentUI.access(this::refreshIfNeeded);
        }
    }

    @Override
    public void onOverallStateChanged() {
        // PlayerView doesn't need overall updates
    }

    private void refreshIfNeeded() {
        if(playerId == null || playerId <= 0) {
            renderMissingPlayer();
            return;
        }

        PlayerPageSnapshot snapshot = gameStateService.getPlayerPageSnapshot(playerId);
        if(snapshot.version() == renderedVersion) {
            return;
        }
        renderedVersion = snapshot.version();
        render(snapshot);
    }

    private void renderMissingPlayer() {
        content.removeAll();
        content.add(new H1("Player not found"));
    }

    private void render(PlayerPageSnapshot snapshot) {
        content.removeAll();

        content.add(
            new H1(snapshot.playerName()),
            new Paragraph("Team " + teamMapping.getTeamNameForTeamId(snapshot.teamId())),
            new H3("Latest 10 player updates")
        );

        UnorderedList updates = new UnorderedList();
        if(snapshot.latestUpdates().isEmpty()) {
            updates.add(new ListItem("No recent non-starchip updates."));
        }
        else {
            for(PlayerUpdate update : snapshot.latestUpdates()) {
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
