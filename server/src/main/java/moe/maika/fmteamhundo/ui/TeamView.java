package moe.maika.fmteamhundo.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.TeamService;
import moe.maika.fmteamhundo.state.CardAcquisition;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.LibraryUpdate;
import moe.maika.fmteamhundo.state.TeamUpdateListener;
import moe.maika.fmteamhundo.state.UserMappings;
import moe.maika.ygofm.gamedata.FMDB;

@Route("teams")
@AnonymousAllowed
public class TeamView extends VerticalLayout implements HasUrlParameter<String>, TeamUpdateListener {

    private static final int CARDS_PER_FULL_GRID = 100;
    private static final int TOTAL_CARDS = 722;

    private final GameStateService gameStateService;
    private final UserRepository userRepository;
    private final HundoConstants hundoConstants;
    private final UserMappings userMappings;
    private final TeamService teamService;
    private final VerticalLayout content;

    private Integer teamId;
    private String teamName;
    private List<User> teamMembers;
    private LibraryUpdate renderedSnapshot;
    private UI currentUI;

    @Autowired
    public TeamView(GameStateService gameStateService, UserRepository userRepository, 
            HundoConstants hundoConstants, UserMappings userMappings, TeamService teamService
    ) {
        this.gameStateService = gameStateService;
        this.userRepository = userRepository;
        this.hundoConstants = hundoConstants;
        this.userMappings = userMappings;
        this.teamService = teamService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);
        content.addClassName("page-container");

        add(ViewSupport.createTopBar(), content);
        addAttachListener(event -> {
            currentUI = event.getUI();
            currentUI.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
            gameStateService.addTeamUpdateListener(this);
            loadInitialSnapshot();
        });
        addDetachListener(event -> {
            gameStateService.removeTeamUpdateListener(this);
            currentUI = null;
        });
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        try {
            teamId = Integer.parseInt(parameter);
            Team team = teamService.getTeamById(teamId);
            teamMembers = userRepository.findByTeamId(teamId);
            if(team != null) {
                teamName = team.getName();
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
        if(currentUI != null) {
            loadInitialSnapshot();
        }
    }

    @Override
    public void onTeamUpdate(LibraryUpdate snapshot) {
        if(teamId != null && teamId == snapshot.teamId() && currentUI != null) {
            currentUI.access(() -> renderIfNewer(snapshot));
        }
    }

    private void loadInitialSnapshot() {
        if(teamId == null || teamName == null) {
            renderMissingTeam();
            return;
        }
        renderIfNewer(gameStateService.getLatestLibraryUpdate(teamId));
    }

    private void renderIfNewer(LibraryUpdate snapshot) {
        if(renderedSnapshot != null && snapshot.timestamp().isBefore(renderedSnapshot.timestamp())) {
            return;
        }
        renderedSnapshot = snapshot;
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

    private void render(LibraryUpdate snapshot) {
        content.removeAll();

        H1 title = new H1(teamName);
        title.addClassName("team-view__title");
        Div stats = new Div();
        stats.addClassName("dashboard-stats-bar");
        stats.add(ViewSupport.createAllStats(snapshot, hundoConstants));

        content.add(title);
        if(snapshot.hasCompletedHundo()) {
            Div banner = new Div(new Icon(VaadinIcon.TROPHY), new Span("Completed at " + ViewSupport.formatInstant(snapshot.completionTime())));
            banner.addClassName("completion-banner");
            content.add(banner);
        }
        content.add(stats, createDownloadLink(), createLatestAcquisitions(snapshot),
                createMembers(snapshot), createCardGrids(gameStateService.getLibrary(teamId).getAcquiredCards()));
    }

    private Component createLatestAcquisitions(LibraryUpdate snapshot) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.addClassName("content-section");
        section.add(new H3("Latest 10 cards"));

        List<CardAcquisition> latestAcquisitions = gameStateService.getLatestCardAcquisitions(teamId);
        UnorderedList list = new UnorderedList();
        list.addClassName("activity-list");
        if(latestAcquisitions.isEmpty()) {
            list.add(new ListItem("No cards acquired yet."));
        }
        else {
            latestAcquisitions.stream().limit(10).forEach(acquisition -> {
                list.add(new ListItem(
                    "Card " + acquisition.cardId() + " via " + acquisition.source()
                        + " by " + userMappings.getUserById(acquisition.playerId()).getName()
                        + " at " + ViewSupport.formatInstant(acquisition.acquisitionTime())
                ));
            });
        }
        section.add(list);
        return section;
    }

    private Component createMembers(LibraryUpdate snapshot) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.addClassName("content-section");
        section.add(new H3("Team Members"));

        UnorderedList list = new UnorderedList();
        if(teamMembers.isEmpty()) {
            list.add(new ListItem("No members on team."));
        }
        else {
            for(User member : teamMembers) {
                RouterLink playerLink = new RouterLink();
                playerLink.setText(member.getName());
                playerLink.setRoute(PlayerView.class, String.valueOf(member.getDatabaseId()));
                list.add(new ListItem(playerLink));
            }
        }
        section.add(list);
        return section;
    }

    private Component createCardGrids(Map<Integer, CardAcquisition> acquiredCards) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(true);
        section.addClassName("content-section");
        section.add(new H3("Card Grid"));

        for(int startCardId = 1; startCardId <= 700; startCardId += CARDS_PER_FULL_GRID) {
            section.add(createGrid(startCardId, startCardId + CARDS_PER_FULL_GRID - 1, acquiredCards));
        }
        section.add(createGrid(701, TOTAL_CARDS, acquiredCards));
        return section;
    }

    private Component createGrid(int startCardId, int endCardId, Map<Integer, CardAcquisition> acquiredCards) {
        VerticalLayout gridWrapper = new VerticalLayout();
        gridWrapper.setPadding(false);
        gridWrapper.setSpacing(false);
        gridWrapper.addClassName("card-grid-section");
        gridWrapper.add(new Span("Cards " + startCardId + "-" + endCardId));

        Div grid = new Div();
        grid.addClassNames("card-grid", "js-card-grid");

        for(int cardId = startCardId; cardId <= endCardId; cardId++) {
            grid.add(createCardCell(cardId, acquiredCards.get(cardId)));
        }
        gridWrapper.add(grid);
        if(currentUI != null) {
            currentUI.getPage().executeJs("if(window.initCardPopovers) window.initCardPopovers()");
        }
        return gridWrapper;
    }

    private Component createCardCell(int cardId, CardAcquisition acquisition) {
        Div cell = new Div();
        cell.setText(Integer.toString(cardId));
        cell.addClassName("card-cell");
        cell.getElement().setAttribute("data-card-id", Integer.toString(cardId));

        if(hundoConstants.getUnobtainableCards().contains(cardId)) {
            cell.addClassName("card-cell--unobtainable");
            cell.getElement().setAttribute("data-status", "unobtainable");
        }
        else if(acquisition != null) {
            cell.addClassName("card-cell--acquired");
            cell.getElement().setAttribute("data-status", "acquired");
            cell.getElement().setAttribute("data-player-name", userMappings.getUserById(acquisition.playerId()).getName());
            cell.getElement().setAttribute("data-source", acquisition.source().toString());
            cell.getElement().setAttribute("data-acquisition-time", ViewSupport.formatInstant(acquisition.acquisitionTime()));
            Object opponent = FMDB.getInstance().getDuelist(acquisition.opponentId());
            if(opponent != null) {
                cell.getElement().setAttribute("data-opponent", opponent.toString());
            }
        }
        else {
            cell.addClassName("card-cell--unacquired");
            cell.getElement().setAttribute("data-status", "unacquired");
        }
        return cell;
    }
}
