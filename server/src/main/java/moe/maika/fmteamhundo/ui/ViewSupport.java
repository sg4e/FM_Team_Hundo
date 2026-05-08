package moe.maika.fmteamhundo.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.communication.PushMode;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.state.CardAcquisition;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.LibraryUpdate;
import moe.maika.fmteamhundo.state.UserMappings;
import moe.maika.ygofm.gamedata.FMDB;

final class ViewSupport {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss MMMM dd")
        .withZone(ZoneId.systemDefault());
    private static final String ADMIN_TWITCH_ID = "73758417";
    private static final FMDB fmdb = FMDB.getInstance();

    private ViewSupport() { }

    static HorizontalLayout createTopBar() {
        HorizontalLayout topBar = new HorizontalLayout();
        topBar.setWidthFull();
        topBar.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        topBar.setAlignItems(FlexComponent.Alignment.CENTER);
        topBar.setPadding(true);
        topBar.addClassName("top-bar");

        HorizontalLayout nav = new HorizontalLayout();
        nav.setSpacing(true);
        nav.addClassName("top-bar__nav");
        nav.add(new RouterLink("Home", MainView.class));
        nav.add(new RouterLink("Setup and Rules", DocsView.class));

        Div alert = createAlert();

        Div actions = new Div();
        actions.addClassName("top-bar__actions");

        Button themeToggle = new Button(new Icon(VaadinIcon.MOON), event -> UI.getCurrent().getPage().executeJs("toggleTheme()"));
        themeToggle.addClassName("theme-toggle");
        themeToggle.getElement().setAttribute("aria-label", "Toggle dark mode");
        actions.add(themeToggle);

        topBar.add(nav, alert, actions);
        topBar.setFlexGrow(1, alert);

        topBar.addAttachListener(event -> {
            UI ui = event.getUI();
            ui.getPushConfiguration().setPushMode(PushMode.AUTOMATIC);
            TopBarAlertService.AlertListener listener = message -> ui.access(() -> setAlertMessage(alert, message));
            TopBarAlertService.addListener(listener);
            topBar.addDetachListener(detachEvent -> TopBarAlertService.removeListener(listener));
        });

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(principal instanceof OAuth2User user) {
            MenuBar menuBar = new MenuBar();
            menuBar.addClassName("top-bar__menu");
            MenuItem userMenu = menuBar.addItem((String) user.getAttribute("preferred_username"), event -> { });
            userMenu.getSubMenu().addItem("Profile", event -> UI.getCurrent().navigate(UserProfileView.class));
            
            String twitchId = (String) user.getAttribute("sub");
            if(ADMIN_TWITCH_ID.equals(twitchId)) {
                userMenu.getSubMenu().addItem("Admin", event -> UI.getCurrent().navigate(AdminView.class));
            }
            
            userMenu.getSubMenu().addItem("Logout", event -> UI.getCurrent().getPage().setLocation("/logout"));
            actions.add(menuBar);
        }
        else {
            Button loginButton = new Button("Login", event -> UI.getCurrent().getPage().setLocation("/oauth2/authorization/twitch"));
            loginButton.addClassName("top-bar__login");
            actions.add(loginButton);
        }

        return topBar;
    }

    private static Div createAlert() {
        Div alert = new Div();
        alert.addClassName("top-bar__alert");

        Span alertText = new Span();
        alertText.addClassName("top-bar__alert-text");
        alert.add(alertText);

        setAlertMessage(alert, TopBarAlertService.getMessage());
        return alert;
    }

    private static void setAlertMessage(Div alert, String message) {
        boolean hasMessage = message != null && !message.isBlank();
        alert.setVisible(hasMessage);
        if(hasMessage && alert.getComponentCount() > 0 && alert.getComponentAt(0) instanceof Span alertText) {
            alertText.setText(message);
        }
    }

    static String formatInstant(Instant instant) {
        return TIMESTAMP_FORMAT.format(instant);
    }

    static Span createStat(String label, String value) {
        Span stat = new Span(label + ": " + value);
        stat.addClassName("stat-chip");
        return stat;
    }

    static List<Component> createAllStats(LibraryUpdate snapshot, HundoConstants hundoConstants) {
        return List.of(
            createStat("Cards", String.format("%d/%d", snapshot.uniqueCardCount(), hundoConstants.getTotalObtainableCards())),
            createStarchipsStat(snapshot.totalStarchips()),
            createStat("Cost of Buyables", Integer.toString(snapshot.totalCostOfBuyables())),
            createStat("Unbuyables", Integer.toString(snapshot.totalUnbuyables())),
            createStat("BEWDs", Integer.toString(snapshot.bewdCount()))
        );
    }

    static Component createStarchipsStat(long starchips) {
        return createStat("Starchips", Long.toString(starchips));
    }

    static Component externalLink(String text, String href) {
        Anchor anchor = new Anchor(href, text);
        anchor.addClassName("external-link");
        return anchor;
    }

    static ListItem createFromCardAcquisition(CardAcquisition acquisition, UserMappings userMappings) {
        ListItem item = new ListItem();
        item.add(stylizeCardName(acquisition.cardId()));
        item.add(String.format(" by %s as %s against %s at %s",
            userMappings.getUserById(acquisition.playerId()).getName(),
            acquisition.source(),
            Objects.toString(fmdb.getDuelist(acquisition.opponentId())),
            formatInstant(acquisition.acquisitionTime())
        ));
        return item;
    }

    static ListItem createFromPlayerUpdate(PlayerUpdate update) {
        ListItem item = new ListItem();
        item.add(stylizeCardName(update.getValue()));
        item.add(String.format(" (%s) against %s at %s",
            update.getSource(),
            Objects.toString(fmdb.getDuelist(update.getOpponentId())),
            formatInstant(update.getTime())
        ));
        return item;
    }

    private static Span stylizeCardName(int cardId) {
        Span cardName = new Span(Objects.toString(fmdb.getCard(cardId)));
        cardName.getStyle().set("font-weight", "bold");
        return cardName;
    }
}
