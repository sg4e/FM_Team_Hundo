package moe.maika.fmteamhundo.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.api.CardAcquisition;
import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.AcquisitionVideoListener;
import moe.maika.fmteamhundo.service.AcquisitionVideoService;
import moe.maika.fmteamhundo.service.TeamService;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.TeamUpdateListener;
import moe.maika.fmteamhundo.state.UserMappings;
import moe.maika.ygofm.gamedata.Duelist;
import moe.maika.ygofm.gamedata.FMDB;

@Route("teams")
@AnonymousAllowed
public class TeamView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle, TeamUpdateListener, AcquisitionVideoListener {

    private static final int CARDS_PER_FULL_GRID = 100;
    private static final int TOTAL_CARDS = 722;
    private static final byte[] UTF_8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final GameStateService gameStateService;
    private final UserRepository userRepository;
    private final AcquisitionVideoService acquisitionVideoService;
    private final HundoConstants hundoConstants;
    private final UserMappings userMappings;
    private final TeamService teamService;
    private final VerticalLayout content;
    private final FMDB fmdb;

    private Integer teamId;
    private Integer renderedTeamId;
    private String teamName;
    private List<User> teamMembers;
    private LibraryUpdate renderedSnapshot;
    private UI currentUI;
    private Div statsBar;
    private Div completionBanner;
    private Span completionBannerText;
    private UnorderedList latestAcquisitionsList;
    private final Map<Integer, Div> cardCellsById = new HashMap<>();
    private final Map<Integer, CardAcquisition> renderedCardAcquisitions = new HashMap<>();

    @Autowired
    public TeamView(GameStateService gameStateService, UserRepository userRepository, 
            HundoConstants hundoConstants, UserMappings userMappings, TeamService teamService,
            AcquisitionVideoService acquisitionVideoService
    ) {
        this.gameStateService = gameStateService;
        this.userRepository = userRepository;
        this.acquisitionVideoService = acquisitionVideoService;
        this.hundoConstants = hundoConstants;
        this.userMappings = userMappings;
        this.teamService = teamService;
        this.fmdb = FMDB.getInstance();

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
            acquisitionVideoService.addListener(this);
            loadInitialSnapshot();
        });
        addDetachListener(event -> {
            gameStateService.removeTeamUpdateListener(this);
            acquisitionVideoService.removeListener(this);
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
    public String getPageTitle() {
        return teamName != null ? String.format("Team %s", teamName) : "Team View";
    }

    @Override
    public void onTeamUpdate(LibraryUpdate snapshot) {
        if(teamId != null && teamId == snapshot.teamId() && currentUI != null) {
            currentUI.access(() -> renderIfNewer(snapshot));
        }
    }

    @Override
    public void onAcquisitionVideoResolved(AcquisitionVideo acquisitionVideo) {
        if(teamId != null && teamId == acquisitionVideo.getTeamId() && currentUI != null) {
            currentUI.access(() -> updateCardVideoAttributes(acquisitionVideo.getCardId(), acquisitionVideo));
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
        if(Objects.equals(renderedTeamId, teamId) && renderedSnapshot != null
                && snapshot.timestamp().isBefore(renderedSnapshot.timestamp())) {
            return;
        }
        if(!Objects.equals(renderedTeamId, teamId) || statsBar == null) {
            buildTeamShell(snapshot);
        }
        else {
            applySnapshot(snapshot);
        }
        renderedSnapshot = snapshot;
    }

    private void renderMissingTeam() {
        renderedTeamId = null;
        renderedSnapshot = null;
        statsBar = null;
        completionBanner = null;
        completionBannerText = null;
        latestAcquisitionsList = null;
        cardCellsById.clear();
        renderedCardAcquisitions.clear();
        content.removeAll();
        content.add(new H1("Team not found"));
    }

    private Component createDownloadLink() {
        Anchor downloadLink = new Anchor();
        downloadLink.setText("Download Team's Library");
        downloadLink.setDownload(true);
        downloadLink.addAttachListener(event -> downloadLink.setHref(createTeamLibraryDownloadHandler()));
        return downloadLink;
    }

    private DownloadHandler createTeamLibraryDownloadHandler() {
        return DownloadHandler.fromInputStream(event -> {
            List<Integer> cardIds = gameStateService.getLibrary(teamId).getAcquiredCardIds();
            byte[] bytes = buildTeamLibraryDownloadBytes(cardIds);
            String fileName = teamName.replaceAll("[^a-zA-Z0-9._-]", "_") + "_cards.txt";
            return new DownloadResponse(new ByteArrayInputStream(bytes), fileName, "text/plain; charset=UTF-8", bytes.length);
        });
    }

    static byte[] buildTeamLibraryDownloadBytes(List<Integer> cardIds) {
        String content = cardIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\r\n"));
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[UTF_8_BOM.length + contentBytes.length];
        System.arraycopy(UTF_8_BOM, 0, bytes, 0, UTF_8_BOM.length);
        System.arraycopy(contentBytes, 0, bytes, UTF_8_BOM.length, contentBytes.length);
        return bytes;
    }

    private void buildTeamShell(LibraryUpdate snapshot) {
        renderedTeamId = teamId;
        cardCellsById.clear();
        renderedCardAcquisitions.clear();
        content.removeAll();

        H1 title = new H1(teamName);
        title.addClassName("team-view__title");
        statsBar = new Div();
        statsBar.addClassName("dashboard-stats-bar");

        completionBannerText = new Span();
        completionBanner = new Div(new Icon(VaadinIcon.TROPHY), completionBannerText);
        completionBanner.addClassName("completion-banner");

        content.add(title, completionBanner);
        content.add(statsBar, createDownloadLink(), createObsStatsWidgetLink(), createLatestAcquisitions(),
                createMembers(snapshot), createCardGrids(gameStateService.getLibrary(teamId).getAcquiredCards()));
        applySnapshot(snapshot);
        if(currentUI != null) {
            currentUI.getPage().executeJs("if(window.initCardPopovers) window.initCardPopovers()");
        }
    }

    private void applySnapshot(LibraryUpdate snapshot) {
        updateStats(snapshot);
        updateCompletionBanner(snapshot);
        updateLatestAcquisitions();
        updateCardGrid(snapshot);
    }

    private void updateStats(LibraryUpdate snapshot) {
        statsBar.removeAll();
        statsBar.add(ViewSupport.createAllStats(snapshot, hundoConstants));
    }

    private void updateCompletionBanner(LibraryUpdate snapshot) {
        boolean completed = snapshot.hasCompletedHundo() && snapshot.completionTime() != null;
        completionBanner.setVisible(completed);
        if(completed) {
            completionBannerText.setText("Completed at " + ViewSupport.formatInstant(snapshot.completionTime()));
        }
    }

    private Component createObsStatsWidgetLink() {
        Anchor link = new Anchor(String.format("/widgets/stats/team/%d?cards=true&starchips=true&cost_of_buyables=true&unbuyables=true&bewds=true&dark_mode=false", teamId), "OBS Stats Widget");
        return link;
    }

    private Component createLatestAcquisitions() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.addClassName("content-section");
        section.add(new H3("Latest 10 new cards"));

        latestAcquisitionsList = new UnorderedList();
        latestAcquisitionsList.addClassName("activity-list");
        section.add(latestAcquisitionsList);
        return section;
    }

    private void updateLatestAcquisitions() {
        latestAcquisitionsList.removeAll();
        List<CardAcquisition> latestAcquisitions = gameStateService.getLatestCardAcquisitions(teamId);
        if(latestAcquisitions.isEmpty()) {
            latestAcquisitionsList.add(new ListItem("No cards acquired yet."));
        }
        else {
            latestAcquisitions.stream().limit(10).forEach(acquisition -> {
                latestAcquisitionsList.add(ViewSupport.createFromCardAcquisition(acquisition, userMappings));
            });
        }
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
        section.add(new H3("Library"));

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
        return gridWrapper;
    }

    private Component createCardCell(int cardId, CardAcquisition acquisition) {
        Div cell = new Div();
        cell.setText(Integer.toString(cardId));
        cell.addClassName("card-cell");
        cell.getElement().setAttribute("data-card-id", Objects.toString(fmdb.getCard(cardId)));
        cardCellsById.put(cardId, cell);

        if(hundoConstants.getUnobtainableCards().contains(cardId)) {
            cell.addClassName("card-cell--unobtainable");
            cell.getElement().setAttribute("data-status", "unobtainable");
        }
        else if(acquisition != null) {
            updateCardCell(cardId, acquisition);
        }
        else {
            resetCardCellToUnacquired(cell);
        }
        return cell;
    }

    private void updateCardGrid(LibraryUpdate snapshot) {
        for(CardAcquisition acquisition : snapshot.newAcquisitions()) {
            updateCardCell(acquisition.cardId(), acquisition);
        }
        if(renderedCardAcquisitions.size() != snapshot.uniqueCardCount()) {
            resyncCardGrid(gameStateService.getLibrary(teamId).getAcquiredCards());
        }
    }

    private void resyncCardGrid(Map<Integer, CardAcquisition> acquiredCards) {
        renderedCardAcquisitions.clear();
        for(Map.Entry<Integer, Div> entry : cardCellsById.entrySet()) {
            int cardId = entry.getKey();
            Div cell = entry.getValue();
            if(hundoConstants.getUnobtainableCards().contains(cardId)) {
                continue;
            }
            CardAcquisition acquisition = acquiredCards.get(cardId);
            if(acquisition != null) {
                updateCardCell(cardId, acquisition);
            }
            else {
                resetCardCellToUnacquired(cell);
            }
        }
    }

    private void updateCardCell(int cardId, CardAcquisition acquisition) {
        Div cell = cardCellsById.get(cardId);
        if(cell == null || hundoConstants.getUnobtainableCards().contains(cardId)) {
            return;
        }
        renderedCardAcquisitions.put(cardId, acquisition);
        cell.removeClassName("card-cell--unacquired");
        cell.addClassName("card-cell--acquired");
        cell.getElement().setAttribute("data-status", "acquired");
        cell.getElement().setAttribute("data-player-name", userMappings.getUserById(acquisition.playerId()).getName());
        cell.getElement().setAttribute("data-source", acquisition.source().toString());
        cell.getElement().setAttribute("data-acquisition-time", ViewSupport.formatInstant(acquisition.acquisitionTime()));
        updateCardVideoAttributes(cardId, acquisitionVideoService.getResolvedVideo(teamId, cardId).orElse(null));
        Duelist opponent = fmdb.getDuelist(acquisition.opponentId());
        if(opponent != null) {
            cell.getElement().setAttribute("data-opponent", opponent.toString());
        }
        else {
            cell.getElement().removeAttribute("data-opponent");
        }
    }

    private void resetCardCellToUnacquired(Div cell) {
        cell.removeClassName("card-cell--acquired");
        cell.addClassName("card-cell--unacquired");
        cell.getElement().setAttribute("data-status", "unacquired");
        cell.getElement().removeAttribute("data-player-name");
        cell.getElement().removeAttribute("data-source");
        cell.getElement().removeAttribute("data-acquisition-time");
        cell.getElement().removeAttribute("data-vod-url");
        cell.getElement().removeAttribute("data-vod-label");
        cell.getElement().removeAttribute("data-opponent");
    }

    private void updateCardVideoAttributes(int cardId, AcquisitionVideo acquisitionVideo) {
        Div cell = cardCellsById.get(cardId);
        if(cell == null) {
            return;
        }
        if(acquisitionVideo == null || acquisitionVideo.getTwitchUrl() == null) {
            cell.getElement().removeAttribute("data-vod-url");
            cell.getElement().removeAttribute("data-vod-label");
            return;
        }
        cell.getElement().setAttribute("data-vod-url", acquisitionVideo.getTwitchUrl());
        cell.getElement().setAttribute("data-vod-label", "Watch VoD");
    }
}
