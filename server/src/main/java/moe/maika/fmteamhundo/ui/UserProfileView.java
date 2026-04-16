package moe.maika.fmteamhundo.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Route("profile")
@AnonymousAllowed
public class UserProfileView extends VerticalLayout {

    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;
    private final Anchor downloadAnchor;

    @Autowired
    public UserProfileView(UserRepository userRepository, ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
        this.downloadAnchor = new Anchor();

        setSizeFull();
        setDefaultHorizontalComponentAlignment(Alignment.CENTER);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(principal instanceof User) {
            User user = (User) principal;
            String username = user.getTwitchInfo().getDisplayName();
            add(new H2("User Profile"));
            add(new Div(new Div("Username: " + username)));
            Button downloadButton = new Button("Download credentials file", event -> openDownloadDialog(user));
            downloadAnchor.setId("credential-download-anchor");
            downloadAnchor.getElement().setAttribute("download", "credentials.json");
            downloadAnchor.getStyle().set("display", "none");
            add(downloadButton, downloadAnchor);
        } else {
            add(new H2("Not logged in"));
            add(new Div("Please log in to view your profile."));
        }
    }

    private void openDownloadDialog(User user) {
        Dialog confirmDialog = new Dialog();
        confirmDialog.setCloseOnEsc(true);
        confirmDialog.setCloseOnOutsideClick(true);

        Paragraph warning1 = new Paragraph(
            "Treat this file like you would a password. Anyone who has this file will be able to impersonate you."
        );
        Paragraph warning2 = new Paragraph(
            "When you click \"Acknowledge and Download\", any previous credential file you have downloaded will no longer work. Replace any previous file and restart your emulator for the changes to take effect."
        );

        Button acknowledgeButton = new Button("Acknowledge and Download", event -> {
            String apiKey = apiKeyService.generateNewApiKey(user);
            StreamResource resource = createCredentialsResource(apiKey, user.getTwitchInfo().getDisplayName());
            downloadAnchor.setHref(resource);
            confirmDialog.close();
            UI.getCurrent().getPage().executeJs("document.getElementById($0).click();", downloadAnchor.getId().orElse(""));
        });

        Button cancelButton = new Button("Cancel", event -> confirmDialog.close());

        confirmDialog.add(warning1, warning2, acknowledgeButton, cancelButton);
        confirmDialog.open();
    }

    private StreamResource createCredentialsResource(String apiKey, String username) {
        String json = buildCredentialsJson(apiKey, username);
        return new StreamResource("credentials.json", () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String buildCredentialsJson(String apiKey, String username) {
        try {
            Credentials dto = new Credentials(apiKey, "http://localhost:8080/api/v1", username);
            return objectMapper.writeValueAsString(dto);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize credentials", e);
        }
    }
}
