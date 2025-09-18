package moe.maika.fmteamhundo.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

@Route("profile")
@AnonymousAllowed
public class UserProfileView extends VerticalLayout {

    public UserProfileView() {
        setSizeFull();
        setDefaultHorizontalComponentAlignment(Alignment.CENTER);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (principal instanceof OAuth2User) {
            OAuth2User user = (OAuth2User) principal;
            String username = user.getAttribute("preferred_username");
            add(new H2("User Profile"));
            add(new Div(new Div("Username: " + username)));
            // Add more user info here as needed
        } else {
            add(new H2("Not logged in"));
            add(new Div("Please log in to view your profile."));
        }
    }
}
