package moe.maika.fmteamhundo.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import org.springframework.beans.factory.annotation.Autowired;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.state.CardAcquisitionView;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.PlayerPageSnapshot;
import moe.maika.fmteamhundo.state.StateChangeListener;
import moe.maika.fmteamhundo.state.TeamPageSnapshot;

@Route("")
@AnonymousAllowed
public class MainView extends VerticalLayout implements StateChangeListener {

    private final GameStateService gameStateService;
    private final TeamRepository teamRepository;
    private final HundoConstants hundoConstants;
    private final VerticalLayout content;
    private final Map<Integer, TeamPageSnapshot> teamSnapshots;
    private UI currentUI;

    @Autowired
    public MainView(GameStateService gameStateService, TeamRepository teamRepository, HundoConstants hundoConstants) {
        this.gameStateService = gameStateService;
        this.teamRepository = teamRepository;
        this.hundoConstants = hundoConstants;
        this.teamSnapshots = new HashMap<>();
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

        content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);

        add(ViewSupport.createTopBar(), content);
        addAttachListener(event -> {
            currentUI = event.getUI();
            currentUI.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
            gameStateService.addStateChangeListener(this);
            loadInitialSnapshots();
        });
        addDetachListener(event -> {
            gameStateService.removeStateChangeListener(this);
            currentUI = null;
        });
    }

    @Override
    public void onTeamStateChanged(TeamPageSnapshot snapshot) {
        if(currentUI != null) {
            currentUI.access(() -> applySnapshot(snapshot));
        }
    }

    @Override
    public void onPlayerStateChanged(PlayerPageSnapshot snapshot) {
        // MainView doesn't need player-specific updates
    }

    private void loadInitialSnapshots() {
        teamSnapshots.clear();
        for(Team team : teamRepository.findAll()) {
            teamSnapshots.put(team.getTeamId(), gameStateService.getTeamPageSnapshot(team.getTeamId()));
        }
        render();
    }

    private void applySnapshot(TeamPageSnapshot snapshot) {
        TeamPageSnapshot currentSnapshot = teamSnapshots.get(snapshot.teamId());
        if(currentSnapshot != null && currentSnapshot.version() >= snapshot.version()) {
            return;
        }
        teamSnapshots.put(snapshot.teamId(), snapshot);
        render();
    }

    private void render() {
        content.removeAll();

        H1 title = new H1("FM Team Hundo");
        content.add(title);

        for(Team team : teamRepository.findAll()) {
            TeamPageSnapshot snapshot = teamSnapshots.getOrDefault(team.getTeamId(), emptySnapshot(team.getTeamId()));
            content.add(createTeamCard(team, snapshot));
        }
    }

    private TeamPageSnapshot emptySnapshot(int teamId) {
        return new TeamPageSnapshot(teamId, 0, 0, 0, List.of(), List.of(), Map.of());
    }

    private Div createTeamCard(Team team, TeamPageSnapshot snapshot) {
        Div card = new Div();
        card.getStyle().set("border", "1px solid #d0d7de");
        card.getStyle().set("border-radius", "12px");
        card.getStyle().set("padding", "1rem");
        card.getStyle().set("background", "white");

        H3 heading = new H3();
        RouterLink link = new RouterLink();
        link.setText(team.getName());
        link.setRoute(TeamView.class, String.valueOf(team.getTeamId()));
        heading.add(link);

        Div stats = new Div();
        stats.add(ViewSupport.createAllStats(snapshot, hundoConstants));
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
