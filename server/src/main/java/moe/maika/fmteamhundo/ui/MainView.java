
package moe.maika.fmteamhundo.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.Registration;

import moe.maika.fmteamhundo.state.CardAcquisitionView;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.TeamPageSnapshot;

@Route("")
@AnonymousAllowed
public class MainView extends VerticalLayout {

    private final GameStateService gameStateService;
    private final VerticalLayout content;
    private long renderedVersion = -1;
    private Registration pollRegistration;

    public MainView(GameStateService gameStateService) {
        this.gameStateService = gameStateService;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

        content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);

        add(ViewSupport.createTopBar(), content);
        addAttachListener(event -> startPolling(event.getUI()));
        addDetachListener(event -> stopPolling(event.getUI()));
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
        long currentVersion = gameStateService.getOverallTeamVersion();
        if(currentVersion == renderedVersion) {
            return;
        }
        renderedVersion = currentVersion;
        render();
    }

    private void render() {
        content.removeAll();

        H1 title = new H1("FM Team Hundo");
        Paragraph subtitle = new Paragraph("Live team progress.");
        content.add(title, subtitle);

        for(int teamId : gameStateService.getKnownTeamIds()) {
            content.add(createTeamCard(gameStateService.getTeamPageSnapshot(teamId)));
        }
    }

    private Div createTeamCard(TeamPageSnapshot snapshot) {
        Div card = new Div();
        card.getStyle().set("border", "1px solid #d0d7de");
        card.getStyle().set("border-radius", "12px");
        card.getStyle().set("padding", "1rem");
        card.getStyle().set("background", "white");

        H3 heading = new H3();
        RouterLink link = new RouterLink();
        link.setText("Team " + snapshot.teamId());
        link.setRoute(TeamView.class, String.valueOf(snapshot.teamId()));
        heading.add(link);

        Div stats = new Div(
            ViewSupport.createStat("Starchips", Long.toString(snapshot.totalStarchips())),
            ViewSupport.createStat("Unique Cards", Integer.toString(snapshot.uniqueCardCount()))
        );
        stats.getStyle().set("display", "flex");
        stats.getStyle().set("gap", "0.5rem");
        stats.getStyle().set("flex-wrap", "wrap");

        UnorderedList acquisitions = new UnorderedList();
        if(snapshot.latestAcquisitions().isEmpty()) {
            acquisitions.add(new ListItem("No cards acquired yet."));
        }
        else {
            for(CardAcquisitionView acquisition : snapshot.latestAcquisitions()) {
                acquisitions.add(new ListItem(describeAcquisition(acquisition)));
            }
        }

        card.add(heading, stats, new Span("Latest 5 unique acquisitions"), acquisitions);
        return card;
    }

    private String describeAcquisition(CardAcquisitionView acquisition) {
        return "Card " + acquisition.cardId() + " via " + acquisition.source()
            + " by " + acquisition.playerName()
            + " at " + ViewSupport.formatInstant(acquisition.acquisitionTime());
    }
}
