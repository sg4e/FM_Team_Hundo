package moe.maika.fmteamhundo.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.TeamUpdateListener;

@Route("widgets/stats/team")
@AnonymousAllowed
// example URL: /widgets/stats/team/1?unbuyables=false&bewds=false&dark_mode=true
// Check StatKind for supported query parameters
public class StatsWidgetView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle, TeamUpdateListener {

    private static final String WIDGET_PAGE_CLASS = "stats-widget-page";
    private static final String DARK_WIDGET_CLASS = "stats-widget-dark";

    private final GameStateService gameStateService;
    private final HundoConstants hundoConstants;
    private final Div statsBar;

    private Integer teamId;
    private boolean darkMode;
    private Set<String> excludedStats = Set.of();
    private LibraryUpdate renderedSnapshot;
    private UI currentUI;

    @Autowired
    public StatsWidgetView(GameStateService gameStateService, HundoConstants hundoConstants) {
        this.gameStateService = gameStateService;
        this.hundoConstants = hundoConstants;

        setPadding(false);
        setSpacing(false);
        setMargin(false);
        addClassName("stats-widget-view");

        statsBar = new Div();
        statsBar.addClassNames("dashboard-stats-bar", "stats-widget-stats");
        add(statsBar);

        addAttachListener(event -> {
            currentUI = event.getUI();
            currentUI.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
            applyPageClasses();
            gameStateService.addTeamUpdateListener(this);
            loadInitialSnapshot();
        });
        addDetachListener(event -> {
            gameStateService.removeTeamUpdateListener(this);
            removePageClasses(event.getUI());
            currentUI = null;
        });
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        teamId = parseTeamId(parameter);
        QueryParameters queryParameters = event.getLocation().getQueryParameters();
        Map<String, List<String>> parameters = queryParameters.getParameters();
        excludedStats = excludedStats(parameters);
        darkMode = isDarkMode(parameters);
        updateDarkClass();

        if(currentUI != null) {
            applyPageClasses();
            loadInitialSnapshot();
        }
    }

    @Override
    public String getPageTitle() {
        return "FM Team Hundo Stats Widget";
    }

    @Override
    public void onTeamUpdate(LibraryUpdate snapshot) {
        if(teamId != null && teamId == snapshot.teamId() && currentUI != null) {
            currentUI.access(() -> renderIfNewer(snapshot));
        }
    }

    static Set<String> excludedStats(Map<String, List<String>> queryParameters) {
        return StatKind.all().stream()
            .map(StatKind::id)
            .filter(id -> queryParameters.getOrDefault(id, List.of()).stream()
                .anyMatch(value -> "false".equalsIgnoreCase(value)))
            .collect(Collectors.toUnmodifiableSet());
    }

    static boolean isDarkMode(Map<String, List<String>> queryParameters) {
        return queryParameters.getOrDefault("dark_mode", List.of()).stream()
            .anyMatch(value -> "true".equalsIgnoreCase(value));
    }

    private static Integer parseTeamId(String parameter) {
        try {
            return Integer.parseInt(parameter);
        }
        catch(NumberFormatException ex) {
            return null;
        }
    }

    private void loadInitialSnapshot() {
        if(teamId == null) {
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
        statsBar.removeAll();
        Span message = new Span("Team not found");
        message.addClassName("stat-chip");
        statsBar.add(message);
    }

    private void render(LibraryUpdate snapshot) {
        statsBar.removeAll();
        statsBar.add(StatKind.all().stream()
            .filter(stat -> !excludedStats.contains(stat.id()))
            .map(stat -> stat.createComponent(snapshot, hundoConstants))
            .toList());
    }

    private void applyPageClasses() {
        if(currentUI == null) {
            return;
        }
        currentUI.getPage().executeJs(
            "document.documentElement.classList.add($0); document.body.classList.add($0);",
            WIDGET_PAGE_CLASS
        );
    }

    private void removePageClasses(UI ui) {
        ui.getPage().executeJs(
            "document.documentElement.classList.remove($0); document.body.classList.remove($0);",
            WIDGET_PAGE_CLASS
        );
    }

    private void updateDarkClass() {
        if(darkMode) {
            addClassName(DARK_WIDGET_CLASS);
        }
        else {
            removeClassName(DARK_WIDGET_CLASS);
        }
    }
}
