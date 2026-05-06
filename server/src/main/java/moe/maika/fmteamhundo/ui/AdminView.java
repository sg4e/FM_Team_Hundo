package moe.maika.fmteamhundo.ui;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Route("admin")
@AnonymousAllowed
@PageTitle("Admin Panel")
public class AdminView extends VerticalLayout {

    private static final String ADMIN_TWITCH_ID = "73758417";
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final VerticalLayout content;

    @Autowired
    public AdminView(UserRepository userRepository, TeamRepository teamRepository) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.content = new VerticalLayout();

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
        List<User> unassignedUsers = userRepository.findByTeamId(0);

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
}
