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
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.shared.Registration;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.state.CardAcquisitionView;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.TeamPageSnapshot;

@Route("teams")
@AnonymousAllowed
public class TeamView extends VerticalLayout implements HasUrlParameter<String> {

    private static final int CARDS_PER_FULL_GRID = 100;
    private static final int TOTAL_CARDS = 722;

    private final GameStateService gameStateService;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final VerticalLayout content;

    private Integer teamId;
    private String teamName;
    private long renderedVersion = -1;
    private Registration pollRegistration;

    @Autowired
    public TeamView(GameStateService gameStateService, TeamRepository teamRepository, UserRepository userRepository) {
        this.gameStateService = gameStateService;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;

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
            teamId = Integer.parseInt(parameter);
            // Check if team exists in database
            Optional<Team> team = teamRepository.findById(teamId);
            if (team.isPresent()) {
                teamName = team.get().getName();
            } else {
                teamId = null;
                teamName = null;
            }
        }
        catch (NumberFormatException ex) {
            teamId = null;
            teamName = null;
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
        if(teamId == null || teamName == null) {
            renderMissingTeam();
            return;
        }

        TeamPageSnapshot snapshot = gameStateService.getTeamPageSnapshot(teamId);
        if(snapshot.version() == renderedVersion) {
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
        List<Integer> cardIds = gameStateService.getLibrary(teamId).getAcquiredCardIds();
        String csvContent = cardIds.stream()
            .map(String::valueOf)
            .collect(Collectors.joining("\n"));
        
        StreamResource resource = new StreamResource(
            teamName.replaceAll("[^a-zA-Z0-9._-]", "_") + "_cards.txt",
            () -> new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
        );
        
        Anchor downloadLink = new Anchor(resource, "Download Team's Library");
        downloadLink.getElement().setAttribute("download", true);
        return downloadLink;
    }

    private void render(TeamPageSnapshot snapshot) {
        content.removeAll();

        H1 title = new H1(teamName);
        HorizontalLayout stats = new HorizontalLayout(
            ViewSupport.createStat("Starchips", Long.toString(snapshot.totalStarchips())),
            ViewSupport.createStat("Unique Cards", Integer.toString(snapshot.uniqueCardCount()))
        );
        stats.setWrap(true);

        content.add(title, stats, createDownloadLink(), createLatestAcquisitions(snapshot), createMembers(), createCardGrids(snapshot.acquiredCards()));
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

    private Component createMembers() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.add(new H3("Team Members"));

        UnorderedList list = new UnorderedList();
        List<User> members = userRepository.findByTeamId(teamId);
        if(members.isEmpty()) {
            list.add(new ListItem("No team members known yet."));
        }
        else {
            for(User member : members) {
                RouterLink playerLink = new RouterLink();
                playerLink.setText(member.getName());
                playerLink.setRoute(PlayerView.class, String.valueOf(member.getDatabaseId()));
                ListItem item = new ListItem(playerLink);
                list.add(item);
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

        if(acquisition != null) {
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
