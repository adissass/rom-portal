# RomPortal agent guide

## Sources of truth

- GitHub Issues is the issue tracker; the **RomPortal UI Overhaul** GitHub Project tracks workflow status. Read the issue, comments, project status, and linked design references before grooming, implementation, or review. The issue defines scope and acceptance criteria; raise conflicts with the code or handoff during grooming.
- Read `README.md` for product behavior and local commands. Read `.github/workflows/android-ci.yml` for the current CI checks; use the actual scripts and Gradle tasks rather than copying commands into this file.
- For browser UI work, use the approved handoff at `../rom-portal-archive-2026-09-19/RomPortal-UI-Handoff/Approved/` while it remains outside this repository. Its `specs/`, `approved-copy.json`, `screens/`, `assets/`, and `fonts/` are the design references. The sibling archive is local to this workstation: if it is absent, obtain the approved handoff before claiming design fidelity. Update this pointer when the handoff is committed here.

## Local workspace notes

- On Apple Silicon, use an ARM64 JDK 21 and ARM64 Node 22.12.0 (pinned in `.nvmrc`) for the combined Gradle/web build. The installed x64 JDK 21 can launch `buildWeb` in x64 mode against ARM `web/node_modules`, causing Vite/Rolldown native-binding errors. For terminal builds, set `JAVA_HOME=$(/usr/libexec/java_home -v 21 -a arm64)` and put `$HOME/Library/Node/22.12.0/bin` first on `PATH`; set Android Studio's Gradle JDK to the same ARM JDK. Run `npm ci --prefix web` after switching Node versions.
- Keep the `@/*` TypeScript path alias in both `web/tsconfig.json` and `web/tsconfig.app.json`; shadcn CLI 4.21.4 reads the root config and otherwise writes aliases under a literal `web/@/` directory. Its Button registry item lists only `cn` as a dependency, but its source also imports `class-variance-authority` and `radix-ui`. Install those packages when adding Button, and inspect generated imports before applying other components. Keep only reviewed output under `web/src/`.

## Issue and change workflow

1. **PM → Backlog:** The PM creates the issue with the goal, scope, constraints, acceptance criteria, and design links when relevant. Backlog means the reviewer has not declared it ready.
2. **Reviewer → Ready:** The reviewer checks the issue against the code and approved handoff. If a decision or artifact is missing, leave it in Backlog and record the exact gap on the issue. Move it to Ready only when a developer can implement it without inventing product behavior and each acceptance criterion can be verified.
3. **Developer → In progress:** The developer takes a Ready issue, moves it to In progress, implements its scope, runs relevant checks, and provides screenshots or other evidence needed for review. Keep the issue and branch linked.
4. **User → Design review:** For UI changes, move the issue to Design review when the implementation is ready for the user to inspect against approved references. The developer addresses feedback before code review. For changes without UI, proceed to Code Review.
5. **Reviewer → Code Review:** The reviewer compares the change with the issue and the repo. Review correctness, security, accessibility, scope, conventions, and avoidable complexity. Report actionable findings with file and line references; the developer resolves them and requests another review.
6. **User + reviewer → Verification:** Move the issue to Verification when design and code review findings are resolved. The user and reviewer check each acceptance criterion using the built artifact and recorded test results. Open the PR after joint verification. Move to Done only after the PR is merged and the accepted result is recorded.

Set Backlog, Ready, In progress, Design review, Code Review, Verification, and Done on the **RomPortal UI Overhaul** project's Status field. Repository issue labels describe issue type and are not workflow status. If an issue is missing from the project, add it before moving its status.

## Engineering boundaries

- `web/` contains the React source. Vite builds it into `app/src/main/assets/web/`; that directory is generated and ignored. The Android Gradle build invokes the web build.
- `app/src/main/java/com/romportal/app/server/` owns Ktor routes, PIN authentication, sessions, and file operations. `app/src/main/java/com/romportal/app/service/` owns server lifecycle and idle timeout behavior.
- Treat the existing `POST /login` status, body, and HttpOnly cookie behavior as a contract unless the issue explicitly changes it. Preserve authenticated `/api/*` behavior and SAF root containment when working on browser UI.
- Reuse existing code and platform features before adding dependencies or abstractions. Keep changes within the issue's scope; preserve input validation, error handling, security, and accessibility.

## Evidence at handoff

- Developer handoff: summarize changed behavior, deviations from the issue, commands and results, and manual UI checks. For browser changes run the web typecheck and build; for server or Android changes run affected unit tests and a debug build.
- Reviewer handoff: separate issue/spec findings from code-quality findings. State what remains unverified, especially visual and interaction behavior that a passing build cannot prove.
- Joint verification is complete when the user and reviewer have checked every acceptance criterion or explicitly recorded its exception. Keep the evidence with the issue or PR.
