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
import com.vaadin.flow.shared.Registration;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.PlayerPageSnapshot;
import moe.maika.fmteamhundo.state.TeamMapping;

@Route("players")
@AnonymousAllowed
public class PlayerView extends VerticalLayout implements HasUrlParameter<String> {

    private final GameStateService gameStateService;
    private final TeamMapping teamMapping;
    private final VerticalLayout content;

    private Long playerId;
    private long renderedVersion = -1;
    private Registration pollRegistration;

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
        addAttachListener(event -> startPolling(event.getUI()));
        addDetachListener(event -> stopPolling(event.getUI()));
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

    private void startPolling(UI ui) {
        ui.setPollInterval(2000);
        stopPolling(ui);
        pollRegistration = ui.addPollListener(event -> refreshIfNeeded());
        refreshIfNeeded();
    }

    private void stopPolling(UI ui) {
        if(pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        ui.setPollInterval(-1);
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
