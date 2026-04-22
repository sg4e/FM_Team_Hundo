package moe.maika.fmteamhundo.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;

import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.TeamPageSnapshot;

final class ViewSupport {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final String ADMIN_TWITCH_ID = "73758417";

    private ViewSupport() { }

    static HorizontalLayout createTopBar() {
        HorizontalLayout topBar = new HorizontalLayout();
        topBar.setWidthFull();
        topBar.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        topBar.setAlignItems(FlexComponent.Alignment.CENTER);
        topBar.setPadding(true);

        HorizontalLayout nav = new HorizontalLayout();
        nav.setSpacing(true);
        nav.add(new RouterLink("Home", MainView.class));
        topBar.add(nav);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(principal instanceof OAuth2User user) {
            MenuBar menuBar = new MenuBar();
            MenuItem userMenu = menuBar.addItem((String) user.getAttribute("preferred_username"), event -> { });
            userMenu.getSubMenu().addItem("Profile", event -> UI.getCurrent().navigate(UserProfileView.class));
            
            String twitchId = (String) user.getAttribute("sub");
            if(ADMIN_TWITCH_ID.equals(twitchId)) {
                userMenu.getSubMenu().addItem("Admin", event -> UI.getCurrent().navigate(AdminView.class));
            }
            
            userMenu.getSubMenu().addItem("Logout", event -> UI.getCurrent().getPage().setLocation("/logout"));
            topBar.add(menuBar);
        }
        else {
            topBar.add(new Button("Login", event -> UI.getCurrent().getPage().setLocation("/oauth2/authorization/twitch")));
        }

        return topBar;
    }

    static String formatInstant(Instant instant) {
        return TIMESTAMP_FORMAT.format(instant);
    }

    static Span createStat(String label, String value) {
        Span stat = new Span(label + ": " + value);
        stat.getStyle().set("font-weight", "600");
        stat.getStyle().set("padding", "0.35rem 0.65rem");
        stat.getStyle().set("border", "1px solid #d0d7de");
        stat.getStyle().set("border-radius", "999px");
        stat.getStyle().set("background", "#f6f8fa");
        return stat;
    }

    static List<Component> createAllStats(TeamPageSnapshot snapshot, HundoConstants hundoConstants) {
        return List.of(
            createStat("Cards", String.format("%d/%d", snapshot.uniqueCardCount(), hundoConstants.getTotalObtainableCards())),
            createStarchipsStat(snapshot.totalStarchips())
        );
    }

    static Component createStarchipsStat(long starchips) {
        return createStat("Starchips", Long.toString(starchips));
    }

    static Component externalLink(String text, String href) {
        Anchor anchor = new Anchor(href, text);
        return anchor;
    }
}
