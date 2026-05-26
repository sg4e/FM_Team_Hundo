package moe.maika.fmteamhundo.ui;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ManualPlayerUpdateService;
import moe.maika.fmteamhundo.service.ManualPlayerUpdateService.ManualPlayerUpdateRequest;
import moe.maika.fmteamhundo.service.TeamService;
import moe.maika.ygofm.gamedata.Duelist;
import moe.maika.ygofm.gamedata.FMDB;

@Route("admin")
@AnonymousAllowed
@PageTitle("Admin Panel")
public class AdminView extends VerticalLayout {

    private static final String ADMIN_TWITCH_ID = "73758417";
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamService teamService;
    private final ManualPlayerUpdateService manualPlayerUpdateService;
    private final VerticalLayout content;
    private final FMDB fmdb;

    @Autowired
    public AdminView(UserRepository userRepository, TeamRepository teamRepository,
            TeamService teamService,
            ManualPlayerUpdateService manualPlayerUpdateService) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.teamService = teamService;
        this.manualPlayerUpdateService = manualPlayerUpdateService;
        this.content = new VerticalLayout();
        this.fmdb = FMDB.getInstance();

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(Alignment.STRETCH);

        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);
        content.addClassNames("page-container", "admin-view");

        add(ViewSupport.createTopBar(), content);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof User) {
            User user = (User) principal;
            if (ADMIN_TWITCH_ID.equals(user.getTwitchId())) {
                renderAdminPanel();
            } else {
                renderUnauthorized();
            }
        } else {
            renderUnauthorized();
        }
    }

    private void renderAdminPanel() {
        content.removeAll();
        content.add(new H1("Admin Panel"));

        VerticalLayout alertSection = createSection("Top Bar Alert");

        TextField alertField = new TextField("Alert Text");
        alertField.setWidthFull();
        alertField.setPlaceholder("Enter scrolling alert text");
        alertField.setValue(TopBarAlertService.getMessage());

        Button setAlertButton = new Button("Set Alert", event -> TopBarAlertService.setMessage(alertField.getValue()));
        Button clearAlertButton = new Button("Clear Alert", event -> {
            TopBarAlertService.clearMessage();
            alertField.clear();
        });

        HorizontalLayout alertActions = new HorizontalLayout(setAlertButton, clearAlertButton);
        alertActions.setPadding(false);
        alertActions.setSpacing(true);
        alertSection.add(alertField, alertActions);
        content.add(alertSection);

        VerticalLayout teamsSection = createSection("Teams");
        List<Team> teams = teamRepository.findAll().stream()
            .filter(team -> !team.isNoTeam())
            .toList();

        if (teams.isEmpty()) {
            teamsSection.add(new Paragraph("No teams exist yet."));
        } else {
            for (Team team : teams) {
                teamsSection.add(createTeamRow(team));
            }
        }
        content.add(teamsSection);

        VerticalLayout createTeamSection = createSection("Create Team");

        TextField teamNameField = new TextField("Team Name");
        teamNameField.setPlaceholder("Enter team name");
        Button createTeamButton = new Button("Create Team", event -> {
            String teamName = teamNameField.getValue();
            if (teamName != null && !teamName.trim().isEmpty()) {
                Team team = new Team(teamName);
                teamRepository.save(team);
                teamNameField.clear();
                renderAdminPanel();  // Refresh the view
            }
        });
        createTeamSection.add(teamNameField, createTeamButton);
        content.add(createTeamSection);

        VerticalLayout assignmentSection = createSection("Assign Users to Teams");
        List<Team> allTeams = teamRepository.findAll();
        List<User> unassignedUsers = userRepository.findByTeamIdAndRegisteredForNextHundo(0, true);

        if (unassignedUsers.isEmpty()) {
            assignmentSection.add(new Paragraph("No unassigned users."));
        } else {
            Grid<User> userGrid = new Grid<>(User.class, false);
            userGrid.addColumn(User::getName).setHeader("User Name");
            userGrid.addColumn(User::getTwitchId).setHeader("Twitch ID");
            userGrid.addComponentColumn(user -> {
                ComboBox<Team> teamSelector = new ComboBox<>();
                teamSelector.setItems(allTeams);
                teamSelector.setItemLabelGenerator(Team::getName);
                teamSelector.setPlaceholder("Select team");

                Button assignButton = new Button("Assign", event -> {
                    Team selectedTeam = teamSelector.getValue();
                    if (selectedTeam != null) {
                        user.setTeamId(selectedTeam.getTeamId());
                        user.setRegisteredForNextHundo(false);
                        userRepository.save(user);
                        renderAdminPanel();  // Refresh the view
                    }
                });

                VerticalLayout container = new VerticalLayout(teamSelector, assignButton);
                container.setPadding(false);
                container.setSpacing(true);
                container.addClassName("admin-action-row");
                return container;
            }).setHeader("Action");

            userGrid.setItems(unassignedUsers);
            userGrid.setWidthFull();
            assignmentSection.add(userGrid);
        }

        content.add(assignmentSection);
        content.add(createManualPlayerUpdateSection());
    }

    private HorizontalLayout createTeamRow(Team team) {
        Span nameSpan = new Span(team.getName());
        nameSpan.addClassName("admin-team-name");

        TextField nameField = new TextField();
        nameField.setValue(team.getName());
        nameField.setVisible(false);
        nameField.addClassName("admin-team-name-field");

        Span errorSpan = new Span();
        errorSpan.addClassName("profile-view__save-status--error");
        errorSpan.setVisible(false);

        Button renameButton = new Button();
        renameButton.setText("Rename");
        Button saveButton = new Button();
        saveButton.setText("Save");
        saveButton.setVisible(false);
        Button cancelButton = new Button();
        cancelButton.setText("Cancel");
        cancelButton.setVisible(false);

        renameButton.addClickListener(event -> {
            nameSpan.setVisible(false);
            renameButton.setVisible(false);
            nameField.setVisible(true);
            saveButton.setVisible(true);
            cancelButton.setVisible(true);
            errorSpan.setVisible(false);
        });

        saveButton.addClickListener(event -> {
            String newName = nameField.getValue();
            if (newName == null || newName.trim().isEmpty()) {
                errorSpan.setText("Team name cannot be blank");
                errorSpan.setVisible(true);
                return;
            }
            try {
                teamService.renameTeam(team.getTeamId(), newName.trim());
                renderAdminPanel();
            } catch (IllegalArgumentException e) {
                errorSpan.setText(e.getMessage());
                errorSpan.setVisible(true);
            }
        });

        cancelButton.addClickListener(event -> {
            nameField.setValue(team.getName());
            errorSpan.setVisible(false);
            nameField.setVisible(false);
            saveButton.setVisible(false);
            cancelButton.setVisible(false);
            nameSpan.setVisible(true);
            renameButton.setVisible(true);
        });

        HorizontalLayout row = new HorizontalLayout(nameSpan, nameField, renameButton, saveButton, cancelButton, errorSpan);
        row.setWidthFull();
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        row.addClassName("admin-team-row");
        return row;
    }

    private VerticalLayout createManualPlayerUpdateSection() {
        VerticalLayout section = createSection("Manual Player Update");
        Span statusMessage = new Span();
        statusMessage.addClassName("profile-view__save-message");
        statusMessage.setVisible(false);

        ComboBox<AdminPlayerOption> playerSelector = new ComboBox<>("Player");
        playerSelector.setItems(getAssignedPlayerOptions());
        playerSelector.setItemLabelGenerator(AdminPlayerOption::label);
        playerSelector.setPlaceholder("Select player");
        playerSelector.setWidthFull();

        ComboBox<MessageType> sourceSelector = new ComboBox<>("Source");
        sourceSelector.setItems(MessageType.values());
        sourceSelector.setItemLabelGenerator(MessageType::toString);
        sourceSelector.setPlaceholder("Select source");

        TextField valueField = new TextField("Value");
        valueField.setPlaceholder("Card ID or starchips total");
        valueField.setClearButtonVisible(true);

        ComboBox<DuelistOption> opponentSelector = new ComboBox<>("Opponent");
        opponentSelector.setItems(getDuelistOptions());
        opponentSelector.setItemLabelGenerator(DuelistOption::name);
        opponentSelector.setPlaceholder("Select opponent");
        opponentSelector.setWidthFull();

        DateTimePicker timePicker = new DateTimePicker("Retroactive Time");
        timePicker.setHelperText("Leave empty to use current time");

        sourceSelector.addValueChangeListener(event -> {
            boolean starchips = event.getValue() == MessageType.STARCHIPS;
            opponentSelector.setEnabled(!starchips);
            if(starchips) {
                opponentSelector.clear();
            }
        });

        Button submitButton = new Button("Create Manual Update", event -> {
            statusMessage.setVisible(false);
            try {
                ManualPlayerUpdateRequest request = buildManualUpdateRequest(
                    playerSelector,
                    sourceSelector,
                    valueField,
                    opponentSelector,
                    timePicker
                );
                manualPlayerUpdateService.validateManualUpdate(request);
                openManualUpdateConfirmation(
                    request,
                    statusMessage,
                    playerSelector,
                    sourceSelector,
                    valueField,
                    opponentSelector,
                    timePicker
                );
            } catch(IllegalArgumentException ex) {
                showManualUpdateStatus(statusMessage, ex.getMessage(), false);
            }
        });

        HorizontalLayout firstRow = new HorizontalLayout(playerSelector, sourceSelector);
        firstRow.setWidthFull();
        firstRow.setFlexGrow(1, playerSelector);
        firstRow.setFlexGrow(1, sourceSelector);

        HorizontalLayout secondRow = new HorizontalLayout(valueField, opponentSelector, timePicker);
        secondRow.setWidthFull();
        secondRow.setFlexGrow(1, valueField);
        secondRow.setFlexGrow(1, opponentSelector);
        secondRow.setFlexGrow(1, timePicker);

        section.add(firstRow, secondRow, submitButton, statusMessage);
        return section;
    }

    private List<AdminPlayerOption> getAssignedPlayerOptions() {
        Map<Integer, Team> teamsById = teamRepository.findAll().stream()
            .collect(Collectors.toMap(Team::getTeamId, Function.identity()));
        return userRepository.findAll().stream()
            .filter(user -> user.getTeamId() != 0)
            .map(user -> new AdminPlayerOption(user, teamsById.get(user.getTeamId())))
            .sorted(Comparator.comparing(
                option -> option.user().getName(),
                String.CASE_INSENSITIVE_ORDER
            ))
            .toList();
    }

    private List<DuelistOption> getDuelistOptions() {
        return fmdb.getAllDuelists().stream()
            .sorted(Comparator.comparingInt(Duelist::getId))
            .map(duelist -> new DuelistOption(duelist.getId(), duelist.toString()))
            .toList();
    }

    private ManualPlayerUpdateRequest buildManualUpdateRequest(
            ComboBox<AdminPlayerOption> playerSelector,
            ComboBox<MessageType> sourceSelector,
            TextField valueField,
            ComboBox<DuelistOption> opponentSelector,
            DateTimePicker timePicker) {
        AdminPlayerOption selectedPlayer = playerSelector.getValue();
        if(selectedPlayer == null) {
            throw new IllegalArgumentException("Player is required");
        }
        MessageType source = sourceSelector.getValue();
        if(source == null) {
            throw new IllegalArgumentException("Source is required");
        }
        int value;
        try {
            value = Integer.parseInt(valueField.getValue().trim());
        } catch(Exception ex) {
            throw new IllegalArgumentException("Value must be an integer");
        }

        Integer opponentId = null;
        if(source != MessageType.STARCHIPS) {
            DuelistOption selectedOpponent = opponentSelector.getValue();
            if(selectedOpponent == null) {
                throw new IllegalArgumentException("Opponent is required for card updates");
            }
            opponentId = selectedOpponent.id();
        }

        Instant timestamp = null;
        LocalDateTime selectedTime = timePicker.getValue();
        if(selectedTime != null) {
            timestamp = selectedTime.atZone(ZoneId.systemDefault()).toInstant();
        }

        return new ManualPlayerUpdateRequest(selectedPlayer.user().getDatabaseId(), source, value, opponentId, timestamp);
    }

    private void openManualUpdateConfirmation(
            ManualPlayerUpdateRequest request,
            Span statusMessage,
            ComboBox<AdminPlayerOption> playerSelector,
            ComboBox<MessageType> sourceSelector,
            TextField valueField,
            ComboBox<DuelistOption> opponentSelector,
            DateTimePicker timePicker) {
        Dialog confirmDialog = new Dialog();
        confirmDialog.setCloseOnEsc(true);
        confirmDialog.setCloseOnOutsideClick(true);

        AdminPlayerOption player = playerSelector.getValue();
        DuelistOption opponent = opponentSelector.getValue();

        VerticalLayout summary = new VerticalLayout();
        summary.setPadding(false);
        summary.setSpacing(false);
        summary.add(new H2("Confirm Manual Update"));
        summary.add(new Paragraph("Player: " + player.label()));
        summary.add(new Paragraph("Source: " + request.source()));
        summary.add(new Paragraph("Value: " + manualPlayerUpdateService.describeValue(request)));
        summary.add(new Paragraph("Opponent: " + (request.source() == MessageType.STARCHIPS ? "None" : opponent.name())));
        summary.add(new Paragraph("Time: " + (request.time() == null ? "Current time on submit" : ViewSupport.formatInstant(request.time()))));

        Button confirmButton = new Button("Create Update", event -> {
            try {
                manualPlayerUpdateService.createManualUpdate(request);
                resetManualUpdateForm(playerSelector, sourceSelector, valueField, opponentSelector, timePicker);
                showManualUpdateStatus(statusMessage, "Manual player update created", true);
                confirmDialog.close();
            } catch(IllegalArgumentException ex) {
                showManualUpdateStatus(statusMessage, ex.getMessage(), false);
                confirmDialog.close();
            }
        });
        Button cancelButton = new Button("Cancel", event -> confirmDialog.close());
        HorizontalLayout buttonRow = new HorizontalLayout(confirmButton, cancelButton);
        buttonRow.setSpacing(true);
        buttonRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        buttonRow.addClassName("profile-view__dialog-actions");

        confirmDialog.add(summary, buttonRow);
        confirmDialog.open();
    }

    private void resetManualUpdateForm(
            ComboBox<AdminPlayerOption> playerSelector,
            ComboBox<MessageType> sourceSelector,
            TextField valueField,
            ComboBox<DuelistOption> opponentSelector,
            DateTimePicker timePicker) {
        playerSelector.clear();
        sourceSelector.clear();
        valueField.clear();
        opponentSelector.clear();
        opponentSelector.setEnabled(true);
        timePicker.clear();
    }

    private void showManualUpdateStatus(Span statusMessage, String message, boolean success) {
        statusMessage.setText(message);
        statusMessage.removeClassName(success ? "profile-view__save-status--error" : "profile-view__save-status--success");
        statusMessage.addClassName(success ? "profile-view__save-status--success" : "profile-view__save-status--error");
        statusMessage.setVisible(true);
    }

    private void renderUnauthorized() {
        content.removeAll();
        VerticalLayout unauthorizedSection = createSection("Unauthorized");
        unauthorizedSection.add(new Paragraph("You do not have permission to access this page."));
        content.add(new H1("Admin Panel"), unauthorizedSection);
    }

    private VerticalLayout createSection(String title) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(true);
        section.setSpacing(true);
        section.setWidthFull();
        section.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);
        section.addClassNames("content-section", "admin-section");
        section.add(new H2(title));
        return section;
    }

    private record AdminPlayerOption(User user, Team team) {
        String label() {
            String teamName = team == null ? "No Team" : team.getName();
            return user.getName() + " - " + teamName + " (#" + user.getDatabaseId() + ")";
        }
    }

    private record DuelistOption(int id, String name) {
    }
}
