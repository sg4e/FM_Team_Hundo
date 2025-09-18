
package moe.maika.fmteamhundo.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

@Route("")
@AnonymousAllowed
public class MainView extends VerticalLayout {

    public MainView() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        HorizontalLayout topBar = new HorizontalLayout();
        topBar.setWidthFull();
        topBar.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        topBar.setPadding(true);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (principal instanceof OAuth2User) {
            OAuth2User user = (OAuth2User) principal;
            MenuBar menuBar = new MenuBar();
            MenuItem userMenu = menuBar.addItem((String) user.getAttribute("preferred_username"), e -> {});
            userMenu.getSubMenu().addItem("Profile", e -> UI.getCurrent().navigate(UserProfileView.class));
            userMenu.getSubMenu().addItem("Logout", e -> UI.getCurrent().getPage().setLocation("/logout"));
            topBar.add(menuBar);
        } else {
            Button loginButton = new Button("Login", e -> UI.getCurrent().getPage().setLocation("/oauth2/authorization/twitch"));
            topBar.add(loginButton);
        }

        add(topBar);

        // Filler landing page content
        Div filler = new Div();
        filler.add(new H1("Welcome to FM Team Hundo!"));
        filler.add("Under construction...");
        filler.getStyle().set("margin-top", "100px");
        filler.setWidthFull();

        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.CENTER);
        add(filler);
    }
}