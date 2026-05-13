package moe.maika.fmteamhundo.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinService;

import moe.maika.fmteamhundo.api.CardAcquisition;
import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideoStatus;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.AcquisitionVideoService;
import moe.maika.fmteamhundo.service.TeamService;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.Library;
import moe.maika.fmteamhundo.state.UserMappings;

class TeamViewTest {

    private GameStateService gameStateService;
    private UserRepository userRepository;
    private AcquisitionVideoService acquisitionVideoService;
    private UserMappings userMappings;
    private TeamView view;
    private Library library;
    private User player;

    @BeforeEach
    void setUp() {
        RouteRegistry routeRegistry = mock(RouteRegistry.class);
        Router router = mock(Router.class);
        VaadinService vaadinService = mock(VaadinService.class);
        when(routeRegistry.getTargetUrl(any(), any(RouteParameters.class))).thenReturn(Optional.of("test-route"));
        when(router.getRegistry()).thenReturn(routeRegistry);
        when(vaadinService.getRouter()).thenReturn(router);
        VaadinService.setCurrent(vaadinService);
        UI.setCurrent(new UI());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("anonymous", null));

        gameStateService = mock(GameStateService.class);
        userRepository = mock(UserRepository.class);
        acquisitionVideoService = mock(AcquisitionVideoService.class);
        userMappings = mock(UserMappings.class);
        TeamService teamService = mock(TeamService.class);
        library = mock(Library.class);
        HundoConstants hundoConstants = new HundoConstants("http://localhost:8080/api", 689, List.of(7), false);

        Team team = new Team(1, "Alpha");
        player = new User();
        player.setDatabaseId(10L);
        player.setName("Mai");
        player.setTeamId(1);

        when(teamService.getTeamById(1)).thenReturn(team);
        when(userRepository.findByTeamId(1)).thenReturn(List.of(player));
        when(gameStateService.getLibrary(1)).thenReturn(library);
        when(library.getAcquiredCards()).thenReturn(Map.of());
        when(gameStateService.getLatestCardAcquisitions(1)).thenReturn(List.of());
        when(acquisitionVideoService.getResolvedVideo(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(userMappings.getUserById(10L)).thenReturn(player);

        view = new TeamView(gameStateService, userRepository, hundoConstants, userMappings, teamService, acquisitionVideoService);
        view.setParameter(null, "1");
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        VaadinService.setCurrent(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void initialRenderBuildsStableShellAndCardGrid() throws Exception {
        renderIfNewer(snapshot("2026-05-10T12:00:00Z", 0, 0, List.of(), false));

        assertThat(statTexts()).containsExactly(
                "Cards: 0/689",
                "Starchips: 0",
                "Cost of Buyables: 0",
                "Unbuyables: 0",
                "BEWDs: 0"
        );
        assertThat(cardCells()).hasSize(722);
        assertThat(cardCells().get(122).getElement().getAttribute("data-status")).isEqualTo("unacquired");
        assertThat(((Div) field("completionBanner")).isVisible()).isFalse();
        assertThat(((UnorderedList) field("latestAcquisitionsList")).getChildren()).hasSize(1);
    }

    @Test
    void starchipsOnlyUpdateRefreshesStatsWithoutRebuildingCardCells() throws Exception {
        renderIfNewer(snapshot("2026-05-10T12:00:00Z", 0, 0, List.of(), false));
        Div cardCell = cardCells().get(122);

        renderIfNewer(snapshot("2026-05-10T12:01:00Z", 100, 0, List.of(), false));

        assertThat(cardCells().get(122)).isSameAs(cardCell);
        assertThat(statTexts()).contains("Starchips: 100");
        assertThat(cardCell.getElement().getAttribute("data-status")).isEqualTo("unacquired");
    }

    @Test
    void acquisitionUpdatePatchesAffectedCardCell() throws Exception {
        CardAcquisition acquisition = acquisition(122, "2026-05-10T12:01:00Z");
        when(gameStateService.getLatestCardAcquisitions(1)).thenReturn(List.of(acquisition));

        renderIfNewer(snapshot("2026-05-10T12:00:00Z", 0, 0, List.of(), false));
        Div cardCell = cardCells().get(122);
        renderIfNewer(snapshot("2026-05-10T12:01:00Z", 0, 1, List.of(acquisition), false));

        assertThat(cardCells().get(122)).isSameAs(cardCell);
        assertThat(cardCell.getElement().getAttribute("data-status")).isEqualTo("acquired");
        assertThat(cardCell.getElement().getAttribute("data-player-name")).isEqualTo("Mai");
        assertThat(cardCell.getElement().getAttribute("data-source")).isEqualTo("drop");
    }

    @Test
    void resolvedVodLinkIsAddedToAcquiredCardCell() throws Exception {
        CardAcquisition acquisition = acquisition(122, "2026-05-10T12:09:23Z");
        AcquisitionVideo video = new AcquisitionVideo();
        video.setStatus(AcquisitionVideoStatus.RESOLVED);
        video.setTwitchVideoId("2770883762");
        video.setOffsetSeconds(563L);
        when(gameStateService.getLatestCardAcquisitions(1)).thenReturn(List.of(acquisition));
        when(acquisitionVideoService.getResolvedVideo(1, 122)).thenReturn(Optional.of(video));

        renderIfNewer(snapshot("2026-05-10T12:09:23Z", 0, 1, List.of(acquisition), false));

        Div cardCell = cardCells().get(122);
        assertThat(cardCell.getElement().getAttribute("data-vod-url"))
                .isEqualTo("https://www.twitch.tv/videos/2770883762?t=0h9m23s");
        assertThat(cardCell.getElement().getAttribute("data-vod-label")).isEqualTo("Watch VoD");
    }

    @Test
    void completionTransitionShowsBanner() throws Exception {
        Instant completionTime = Instant.parse("2026-05-10T12:05:00Z");

        renderIfNewer(snapshot("2026-05-10T12:00:00Z", 0, 0, List.of(), false));
        renderIfNewer(new LibraryUpdate(null, 1, completionTime, 0, 0, List.of(), 0, 0, 0, true, true, completionTime, 0));

        assertThat(((Div) field("completionBanner")).isVisible()).isTrue();
        assertThat(((Span) field("completionBannerText")).getText()).startsWith("Completed at ");
    }

    @Test
    void olderSnapshotsAreIgnored() throws Exception {
        renderIfNewer(snapshot("2026-05-10T12:01:00Z", 100, 0, List.of(), false));
        renderIfNewer(snapshot("2026-05-10T12:00:00Z", 50, 0, List.of(), false));

        assertThat(statTexts()).contains("Starchips: 100");
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Div> cardCells() throws Exception {
        return (Map<Integer, Div>) field("cardCellsById");
    }

    private List<String> statTexts() throws Exception {
        Div statsBar = (Div) field("statsBar");
        return statsBar.getChildren()
                .map(component -> ((Span) component).getText())
                .toList();
    }

    private Object field(String name) throws Exception {
        Field field = TeamView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }

    private void renderIfNewer(LibraryUpdate snapshot) throws Exception {
        if(UI.getCurrent() == null) {
            UI.setCurrent(new UI());
        }
        Method method = TeamView.class.getDeclaredMethod("renderIfNewer", LibraryUpdate.class);
        method.setAccessible(true);
        method.invoke(view, snapshot);
    }

    private static LibraryUpdate snapshot(String timestamp, long starchips, int uniqueCards, List<CardAcquisition> acquisitions,
            boolean complete) {
        Instant instant = Instant.parse(timestamp);
        return new LibraryUpdate(null, 1, instant, starchips, uniqueCards, acquisitions, 0, 0, 0, true, complete,
                complete ? instant : null, 0);
    }

    private static CardAcquisition acquisition(int cardId, String timestamp) {
        return new CardAcquisition(cardId, Instant.parse(timestamp), MessageType.DROP, 10L, 1);
    }
}
