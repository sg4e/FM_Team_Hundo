package moe.maika.fmteamhundo.ui;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.service.TeamService;

@Route("team_widgets")
@AnonymousAllowed
public class TeamWidgetsView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {

    private final TeamService teamService;
    private final VerticalLayout content;

    private Integer teamId;
    private String teamName;

    private static final String CARD_WIDGETS_EXPLANATION = """
            These widgets display the latest new acquisitions to the team's Library. Pick the theme you like best and add it into OBS as a Browser Source. 
            They render best if you set the width in the Browser Source a few pixels greater than the value specified in the table column header.
            They only show new acquisitions since the widget was opened, so they will be blank when first loaded.

            Do not crop the widgets, or they won't render properly. Instead, adjust the height in pixels to your preference in the Browser Source, then 
            resize with your mouse if necessary.

            You can customize their layout by editing these values in the URL:
             - `limit`: a positive integer for the maximum number of cards to display.
             - `direction`: whether new cards appear at the bottom or the top of the widget. Choose either `top` or `bottom` for the value.
            """;

    @Autowired
    public TeamWidgetsView(TeamService teamService) {
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
    }

    @Override
    public void setParameter(BeforeEvent event, String parameter) {
        try {
            teamId = Integer.parseInt(parameter);
            Team team = teamService.getTeamById(teamId);

            if (team != null) {
                teamName = team.getName();
            } else {
                teamId = null;
                teamName = null;
            }
        } catch (NumberFormatException ex) {
            teamId = null;
            teamName = null;
        }

        render();
    }

    @Override
    public String getPageTitle() {
        return teamName != null ? String.format("%s Widgets", teamName) : "Team Widgets";
    }

    private void render() {
        content.removeAll();

        if (teamId == null || teamName == null) {
            content.add(new H1("Team not found"));
            return;
        }

        H1 title = new H1("OBS Widgets for " + teamName);
        title.addClassName("team-view__title");

        content.add(
                title,
                createStatsWidgetSection(),
                createLatestAcquisitionsSection()
        );
    }

    private Component createStatsWidgetSection() {
        Div container = new Div();
        container.addClassName("content-section");

        container.add(new H3("Stats Widget"));

        UnorderedList list = new UnorderedList();
        list.add(new ListItem(createWidgetLink(
                String.format(
                        "/widgets/stats/team/%d?cards=true&starchips=true&cost_of_buyables=true&unbuyables=true&bewds=true&dark_mode=false",
                        teamId
                ),
                "Open OBS Stats Widget"
        )));

        container.add(list);
        return container;
    }

    private Component createLatestAcquisitionsSection() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(true);
        section.addClassName("content-section");

        section.add(new H3("Latest Acquisitions Widgets"));
        section.add(new Markdown(CARD_WIDGETS_EXPLANATION));
        section.add(createWidgetGallery());

        return section;
    }

    private Component createWidgetGallery() {
        Grid<WidgetRow> grid = new Grid<>(WidgetRow.class, false);

        grid.addColumn(WidgetRow::name)
                .setHeader("Widget")
                .setFlexGrow(1);

        grid.addComponentColumn(row -> createWidgetLink(row.compactUrl(), String.format("%s %s", row.name(), "Compact")))
                .setHeader("Compact (240px wide)")
                .setFlexGrow(1);

        grid.addComponentColumn(row -> createWidgetLink(row.wideUrl(), String.format("%s %s", row.name(), "Wide")))
                .setHeader("Wide (450px wide)")
                .setFlexGrow(1);
        
        grid.addComponentColumn(row -> new Image(row.previewUrl(), String.format("Preview of %s", row.name())))
                .setHeader("Preview")
                .setFlexGrow(1);

        grid.setItems(
                new WidgetRow(
                        "Classic",
                        String.format("/widgets/v1-classic.html?teamId=%d&limit=5&direction=top", teamId),
                        String.format("/widgets/v1-classic-wide.html?teamId=%d&limit=5&direction=top", teamId),
                        "/widget_previews/v1.png"
                ),
                new WidgetRow(
                        "Neon",
                        String.format("/widgets/v2-cyberpunk.html?teamId=%d&limit=5&direction=bottom", teamId),
                        String.format("/widgets/v2-cyberpunk-wide.html?teamId=%d&limit=5&direction=bottom", teamId),
                        "/widget_previews/v2.png"
                ),
                new WidgetRow(
                        "Card Deck",
                        String.format("/widgets/v3-deck.html?teamId=%d&limit=5&direction=top", teamId),
                        String.format("/widgets/v3-deck-wide.html?teamId=%d&limit=5&direction=top", teamId),
                        "/widget_previews/v3.png"
                ),
                new WidgetRow(
                        "CRT",
                        String.format("/widgets/v4-arcade.html?teamId=%d&limit=5&direction=bottom", teamId),
                        String.format("/widgets/v4-arcade-wide.html?teamId=%d&limit=5&direction=bottom", teamId),
                        "/widget_previews/v4.png"
                )
        );

        grid.addClassName("widget-gallery-grid");
        grid.setAllRowsVisible(true);

        return grid;
    }

    private Anchor createWidgetLink(String url, String text) {
        Anchor link = new Anchor(url, text);
        link.setTarget("_blank");
        link.getElement().setAttribute("rel", "noopener noreferrer");
        return link;
    }

    public record WidgetRow(String name, String compactUrl, String wideUrl, String previewUrl) {
    }
}
