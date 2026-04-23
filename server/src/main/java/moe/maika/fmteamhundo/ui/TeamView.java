package moe.maika.fmteamhundo.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.state.CardAcquisitionView;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.PlayerPageSnapshot;
import moe.maika.fmteamhundo.state.StateChangeListener;
import moe.maika.fmteamhundo.state.TeamMember;
import moe.maika.fmteamhundo.state.TeamPageSnapshot;
import moe.maika.fmteamhundo.data.repos.TeamRepository;

@Route("teams")
@AnonymousAllowed
public class TeamView extends VerticalLayout implements HasUrlParameter<String>, StateChangeListener {

    private static final int CARDS_PER_FULL_GRID = 100;
    private static final int TOTAL_CARDS = 722;

    private final GameStateService gameStateService;
    private final TeamRepository teamRepository;
    private final HundoConstants hundoConstants;
    private final VerticalLayout content;

    private Integer teamId;
    private String teamName;
    private long renderedVersion = -1;
    private UI currentUI;

    @Autowired
    public TeamView(GameStateService gameStateService, TeamRepository teamRepository, HundoConstants hundoConstants) {
        this.gameStateService = gameStateService;
        this.teamRepository = teamRepository;
        this.hundoConstants = hundoConstants;

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
            loadInitialSnapshot();
        });
        addDetachListener(event -> {
            gameStateService.removeStateChangeListener(this);
            currentUI = null;
        });
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        try {
            teamId = Integer.parseInt(parameter);
            Optional<Team> team = teamRepository.findById(teamId);
            if(team.isPresent()) {
                teamName = team.get().getName();
            }
            else {
                teamId = null;
                teamName = null;
            }
        }
        catch(NumberFormatException ex) {
            teamId = null;
            teamName = null;
        }
        renderedVersion = -1;
        if(currentUI != null) {
            loadInitialSnapshot();
        }
    }

    @Override
    public void onTeamStateChanged(TeamPageSnapshot snapshot) {
        if(teamId != null && teamId == snapshot.teamId() && currentUI != null) {
            currentUI.access(() -> renderIfNewer(snapshot));
        }
    }

    @Override
    public void onPlayerStateChanged(PlayerPageSnapshot snapshot) {
        // TeamView doesn't need player-specific updates
    }

    private void loadInitialSnapshot() {
        if(teamId == null || teamName == null) {
            renderMissingTeam();
            return;
        }
        renderIfNewer(gameStateService.getTeamPageSnapshot(teamId));
    }

    private void renderIfNewer(TeamPageSnapshot snapshot) {
        if(snapshot.version() <= renderedVersion) {
            return;
        }
        renderedVersion = snapshot.version();
        render(snapshot);
    }

    private void renderMissingTeam() {
        content.removeAll();
        content.add(new H1("Team not found"));
    }

    private Component createDownloadLink() {
        StreamResource resource = new StreamResource(
            teamName.replaceAll("[^a-zA-Z0-9._-]", "_") + "_cards.txt",
            () -> {
                List<Integer> cardIds = gameStateService.getLibrary(teamId).getAcquiredCardIds();
                String content = cardIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("\n"));
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        );

        Anchor downloadLink = new Anchor(resource, "Download Team's Library");
        downloadLink.getElement().setAttribute("download", true);
        return downloadLink;
    }

    private void render(TeamPageSnapshot snapshot) {
        content.removeAll();

        H1 title = new H1(teamName);
        HorizontalLayout stats = new HorizontalLayout();
        stats.add(ViewSupport.createAllStats(snapshot, hundoConstants));
        stats.setWrap(true);

        content.add(title, stats, createDownloadLink(), createLatestAcquisitions(snapshot), createMembers(snapshot), createCardGrids(snapshot.acquiredCards()));
    }

    private Component createLatestAcquisitions(TeamPageSnapshot snapshot) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.add(new H3("Latest 5 unique acquisitions"));

        UnorderedList list = new UnorderedList();
        if(snapshot.latestAcquisitions().isEmpty()) {
            list.add(new ListItem("No cards acquired yet."));
        }
        else {
            for(CardAcquisitionView acquisition : snapshot.latestAcquisitions()) {
                list.add(new ListItem(
                    "Card " + acquisition.cardId() + " via " + acquisition.source()
                        + " by " + acquisition.playerName()
                        + " at " + ViewSupport.formatInstant(acquisition.acquisitionTime())
                ));
            }
        }
        section.add(list);
        return section;
    }

    private Component createMembers(TeamPageSnapshot snapshot) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.add(new H3("Team Members"));

        UnorderedList list = new UnorderedList();
        if(snapshot.members().isEmpty()) {
            list.add(new ListItem("No team members known yet."));
        }
        else {
            for(TeamMember member : snapshot.members()) {
                RouterLink playerLink = new RouterLink();
                playerLink.setText(member.playerName());
                playerLink.setRoute(PlayerView.class, String.valueOf(member.playerId()));
                list.add(new ListItem(playerLink));
            }
        }
        section.add(list);
        return section;
    }

    private Component createCardGrids(Map<Integer, CardAcquisitionView> acquiredCards) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(true);
        section.add(new H3("Card Grid"));

        for(int startCardId = 1; startCardId <= 700; startCardId += CARDS_PER_FULL_GRID) {
            section.add(createGrid(startCardId, startCardId + CARDS_PER_FULL_GRID - 1, acquiredCards));
        }
        section.add(createGrid(701, TOTAL_CARDS, acquiredCards));
        return section;
    }

    private Component createGrid(int startCardId, int endCardId, Map<Integer, CardAcquisitionView> acquiredCards) {
        VerticalLayout gridWrapper = new VerticalLayout();
        gridWrapper.setPadding(false);
        gridWrapper.setSpacing(false);
        gridWrapper.add(new Span("Cards " + startCardId + "-" + endCardId));

        Div grid = new Div();
        grid.getStyle().set("display", "grid");
        grid.getStyle().set("grid-template-columns", "repeat(10, minmax(0, 1fr))");
        grid.getStyle().set("gap", "4px");
        grid.getStyle().set("max-width", "520px");

        for(int cardId = startCardId; cardId <= endCardId; cardId++) {
            grid.add(createCardCell(cardId, acquiredCards.get(cardId)));
        }
        gridWrapper.add(grid);
        return gridWrapper;
    }

    private Component createCardCell(int cardId, CardAcquisitionView acquisition) {
        Div cell = new Div();
        cell.setText(Integer.toString(cardId));
        cell.getStyle().set("height", "2rem");
        cell.getStyle().set("display", "flex");
        cell.getStyle().set("align-items", "center");
        cell.getStyle().set("justify-content", "center");
        cell.getStyle().set("border-radius", "6px");
        cell.getStyle().set("font-size", "0.75rem");
        cell.getStyle().set("color", "#ffffff");

        if(hundoConstants.getUnobtainableCards().contains(cardId)) {
            cell.getStyle().set("background", "#6a737d");
            cell.getElement().setProperty("title", "Card " + cardId + "\nUnobtainable");
        }
        else if(acquisition != null) {
            cell.getStyle().set("background", "#2da44e");
            cell.getElement().setProperty("title",
                "Card " + cardId
                    + "\nAcquired by " + acquisition.playerName()
                    + "\nSource: " + acquisition.source()
                    + "\nAt: " + ViewSupport.formatInstant(acquisition.acquisitionTime()));
        }
        else {
            cell.getStyle().set("background", "#cf222e");
            cell.getElement().setProperty("title", "Card " + cardId + "\nNot acquired");
        }
        return cell;
    }
}
