package moe.maika.fmteamhundo.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

@Route("")
@AnonymousAllowed
public class MainView extends VerticalLayout {

    public MainView() {
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setDefaultHorizontalComponentAlignment(Alignment.CENTER);
        
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        
        if (principal instanceof OAuth2User) {
            showUserInfo((OAuth2User) principal);
        } else {
            showLoginButton();
        }
    }

    private void showUserInfo(OAuth2User user) {
        String username = user.getAttribute("preferred_username");
        add(new H1("Welcome, " + username));
        
        add(new Button("Logout", _ -> UI.getCurrent().getPage().setLocation("/logout")));
    }

    private void showLoginButton() {
        add(
            new H1("Twitch OAuth Demo"),
            new Button("Login with Twitch", 
                _ -> UI.getCurrent().getPage().setLocation("/oauth2/authorization/twitch"))
        );
    }
}