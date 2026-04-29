package moe.maika.fmteamhundo.ui;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.service.TeamService;
import moe.maika.fmteamhundo.state.CardAcquisition;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.LibraryUpdate;
import moe.maika.fmteamhundo.state.TeamUpdateListener;
import moe.maika.fmteamhundo.state.UserMappings;

@Route("")
@AnonymousAllowed
public class MainView extends VerticalLayout implements TeamUpdateListener {

    private final GameStateService gameStateService;
    private final HundoConstants hundoConstants;
    private final UserMappings userMappings;
    private final List<Team> teams;
    private final VerticalLayout content;
    private final Map<Integer, LibraryUpdate> teamSnapshots;
    private UI currentUI;

    @Autowired
    public MainView(GameStateService gameStateService, HundoConstants hundoConstants,
            UserMappings userMappings, TeamService teamService
    ) {
        this.gameStateService = gameStateService;
        this.hundoConstants = hundoConstants;
        this.userMappings = userMappings;
        this.teams = teamService.getTeams();
        this.teamSnapshots = teams.stream().map(Team::getTeamId)
                .collect(Collectors.toMap(Function.identity(), id -> gameStateService.getLatestLibraryUpdate(id)));
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

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
            render();
        });
        addDetachListener(event -> {
            gameStateService.removeTeamUpdateListener(this);
            currentUI = null;
        });
    }

    @Override
    public void onTeamUpdate(LibraryUpdate snapshot) {
        if(currentUI != null) {
            currentUI.access(() -> applySnapshot(snapshot));
        }
    }

    private void applySnapshot(LibraryUpdate snapshot) {
        LibraryUpdate currentSnapshot = teamSnapshots.get(snapshot.teamId());
        if(currentSnapshot != null && currentSnapshot.timestamp().isAfter(snapshot.timestamp())) {
            return;
        }
        teamSnapshots.put(snapshot.teamId(), snapshot);
        render();
    }

    private void render() {
        content.removeAll();

        H1 title = new H1("FM Team Hundo");
        content.add(title);

        for(Team team : teams) {
            LibraryUpdate snapshot = teamSnapshots.get(team.getTeamId());
            content.add(createTeamCard(team, snapshot));
        }
    }

    private Div createTeamCard(Team team, LibraryUpdate snapshot) {
        Div card = new Div();
        card.addClassName("team-card");

        H3 heading = new H3();
        heading.addClassName("team-card__title");
        RouterLink link = new RouterLink();
        link.setText(team.getName());
        link.setRoute(TeamView.class, String.valueOf(team.getTeamId()));
        heading.add(link);

        Div stats = new Div();
        stats.addClassName("dashboard-stats-bar");
        stats.add(ViewSupport.createAllStats(snapshot, hundoConstants));

        List<CardAcquisition> latestAcquisitions = gameStateService.getLatestCardAcquisitions(team.getTeamId());
        UnorderedList acquisitions = new UnorderedList();
        acquisitions.addClassName("activity-list");
        if(latestAcquisitions.isEmpty()) {
            acquisitions.add(new ListItem("No cards acquired yet."));
        }
        else {
            latestAcquisitions.stream().limit(5).forEach(acquisition -> {
                acquisitions.add(ViewSupport.createFromCardAcquisition(acquisition, userMappings));
            });
        }

        if(snapshot.hasCompletedHundo()) {
            Div banner = new Div(new Icon(VaadinIcon.TROPHY), new Span("Completed at " + ViewSupport.formatInstant(snapshot.completionTime())));
            banner.addClassName("completion-banner");
            card.add(banner);
        }

        Span acquisitionsLabel = new Span("Latest 5 new cards");
        acquisitionsLabel.addClassName("section-label");

        card.add(heading, stats, acquisitionsLabel, acquisitions);
        return card;
    }

}
