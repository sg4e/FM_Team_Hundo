package moe.maika.fmteamhundo.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.component.textfield.TextField;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.fmteamhundo.state.UserMappings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Route("profile")
@AnonymousAllowed
public class UserProfileView extends VerticalLayout {

    private static final String CREDENTIALS_FILENAME = "credentials_FM_Team_Hundo.json";

    private final UserRepository userRepository;
    private final UserMappings teamMapping;
    private final ApiKeyService apiKeyService;
    private final HundoConstants hundoConstants;
    private final ObjectMapper objectMapper;
    private final Anchor downloadAnchor;
    private final VerticalLayout content;

    @Autowired
    public UserProfileView(UserRepository userRepository, UserMappings teamMapping, ApiKeyService apiKeyService, ObjectMapper objectMapper,
                           HundoConstants hundoConstants) {
        this.userRepository = userRepository;
        this.teamMapping = teamMapping;
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
        this.hundoConstants = hundoConstants;
        this.downloadAnchor = new Anchor();
        this.content = new VerticalLayout();

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(Alignment.STRETCH);
        addClassName("profile-view");

        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);
        content.addClassName("page-container");

        add(ViewSupport.createTopBar(), content);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(principal instanceof User) {
            User user = (User) principal;
            String username = user.getName();
            content.add(new H1("User Profile"));

            VerticalLayout detailsSection = createSection();
            detailsSection.add(new H2("Details"));
            detailsSection.add(createProfileField("Username: " + username));
            detailsSection.add(createProfileField("Team: " + teamMapping.getTeamNameForTeamId(user.getTeamId())));
            detailsSection.add(createAltAccountEditor(user));
            content.add(detailsSection);

            RouterLink playerLink = new RouterLink();
            playerLink.setText("View public player page");
            playerLink.setRoute(PlayerView.class, String.valueOf(user.getDatabaseId()));
            playerLink.addClassName("profile-view__link");

            Button downloadButton = new Button("Download credentials file", event -> openDownloadDialog(user));
            downloadButton.addClassName("profile-view__button");
            downloadAnchor.setId("credential-download-anchor");
            downloadAnchor.getElement().setAttribute("download", CREDENTIALS_FILENAME);
            downloadAnchor.addClassName("u-hidden");

            VerticalLayout credentialsSection = createSection();
            credentialsSection.add(new H2("Credentials"), playerLink, downloadButton, downloadAnchor);
            content.add(credentialsSection);
        } else {
            content.add(new H1("Not logged in"));
            content.add(new Div("Please log in to view your profile."));
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
            StreamResource resource = createCredentialsResource(apiKey, user.getName());
            downloadAnchor.setHref(resource);
            confirmDialog.close();
            UI.getCurrent().getPage().executeJs("document.getElementById($0).click();", downloadAnchor.getId().orElse(""));
        });

        Button cancelButton = new Button("Cancel", event -> confirmDialog.close());
        HorizontalLayout buttonRow = new HorizontalLayout(acknowledgeButton, cancelButton);
        buttonRow.setSpacing(true);
        buttonRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        buttonRow.addClassName("profile-view__dialog-actions");

        confirmDialog.add(warning1, warning2, buttonRow);
        confirmDialog.open();
    }

    private StreamResource createCredentialsResource(String apiKey, String username) {
        String json = buildCredentialsJson(apiKey, username);
        return new StreamResource(CREDENTIALS_FILENAME, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String buildCredentialsJson(String apiKey, String username) {
        try {
            Credentials dto = new Credentials(apiKey, hundoConstants.getApiUrl(), username);
            return objectMapper.writeValueAsString(dto);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize credentials", e);
        }
    }

    private Div createProfileField(String text) {
        Div wrapper = new Div(new Div(text));
        wrapper.addClassName("profile-view__field");
        return wrapper;
    }

    private VerticalLayout createSection() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(true);
        section.addClassNames("content-section", "profile-view__section");
        return section;
    }

    private HorizontalLayout createAltAccountEditor(User user) {
        TextField altAccountField = new TextField("Alt Account (optional)");
        altAccountField.setValue(user.getAltAccount() == null ? "" : user.getAltAccount());
        altAccountField.setPlaceholder("Enter alt Twitch account for streaming");
        altAccountField.setClearButtonVisible(true);
        altAccountField.addClassName("profile-view__alt-account-field");

        Button saveButton = new Button("Save");
        Icon statusIcon = VaadinIcon.CHECK.create();
        statusIcon.addClassName("profile-view__save-status");
        statusIcon.setVisible(false);

        altAccountField.addValueChangeListener(event -> statusIcon.setVisible(false));
        saveButton.addClickListener(event -> saveAltAccount(user, altAccountField, statusIcon));

        HorizontalLayout editorRow = new HorizontalLayout(altAccountField, saveButton, statusIcon);
        editorRow.setWidthFull();
        editorRow.setAlignItems(FlexComponent.Alignment.END);
        editorRow.addClassName("profile-view__alt-account-row");
        editorRow.setFlexGrow(1, altAccountField);
        return editorRow;
    }

    private void saveAltAccount(User user, TextField altAccountField, Icon statusIcon) {
        String value = altAccountField.getValue() == null ? null : altAccountField.getValue().trim();
        user.setAltAccount(value == null || value.isEmpty() ? null : value);

        try {
            userRepository.save(user);
            statusIcon.setIcon(VaadinIcon.CHECK);
            statusIcon.removeClassName("profile-view__save-status--error");
            statusIcon.addClassName("profile-view__save-status--success");
        }
        catch(RuntimeException ex) {
            statusIcon.setIcon(VaadinIcon.CLOSE_SMALL);
            statusIcon.removeClassName("profile-view__save-status--success");
            statusIcon.addClassName("profile-view__save-status--error");
        }

        statusIcon.setVisible(true);
    }
}
