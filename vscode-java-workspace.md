# VS Code Java Workspace

Open this repository with `FM_Team_Hundo.code-workspace` instead of opening the repository parent folder directly. The workspace opens the repository root for normal repo-wide access, plus the Maven server project and the Gradle livestats project as separate VS Code workspace folders so the Java tooling can import each build independently.

In Explorer, you should see the full repo under `FM_Team_Hundo` as well as dedicated `server-maven` and `livestats-gradle` folders. That means `server` and `commentary/livestats` may appear twice: once inside the full repo tree, and once as explicit Java project roots.

After opening the workspace file:

1. Run `Java: Clean Java Language Server Workspace`.
2. Run `Java: Import Java projects in workspace`.
3. Confirm both projects appear separately in the VS Code Java Projects panel.

The repository root is `.`. The Maven project root is `server`. The Gradle project root is `commentary/livestats`.
