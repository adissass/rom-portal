# RomPortal agent guide

## Sources of truth

- GitHub Issues is the issue tracker; the **RomPortal UI Overhaul** GitHub Project tracks workflow status. Read the issue, comments, project status, and linked design references before grooming, implementation, or review. The issue defines scope and acceptance criteria; raise conflicts with the code or handoff during grooming.
- Read `README.md` for product behavior and local commands. Read `.github/workflows/android-ci.yml` for the current CI checks; use the actual scripts and Gradle tasks rather than copying commands into this file.
- For browser UI work on this workstation, use the approved handoff at `/Users/dev/wamzuu/rom-portal-workspace/references/approved-ui/`. Its `specs/`, `approved-copy.json`, `screens/`, `assets/`, and `fonts/` are the design references. This directory is outside Git; if it is absent, obtain the approved handoff before claiming design fidelity.

## Local workspace notes

- On Apple Silicon, use an ARM64 JDK 21 and ARM64 Node 22.12.0 (pinned in `.nvmrc`) for the combined Gradle/web build. The installed x64 JDK 21 can launch `buildWeb` in x64 mode against ARM `web/node_modules`, causing Vite/Rolldown native-binding errors. For terminal builds, set `JAVA_HOME=$(/usr/libexec/java_home -v 21 -a arm64)` and put `$HOME/Library/Node/22.12.0/bin` first on `PATH`; set Android Studio's Gradle JDK to the same ARM JDK. Run `npm ci --prefix web` after switching Node versions.
- Keep the `@/*` TypeScript path alias in both `web/tsconfig.json` and `web/tsconfig.app.json`; shadcn CLI 4.21.4 reads the root config and otherwise writes aliases under a literal `web/@/` directory. Its Button registry item lists only `cn` as a dependency, but its source also imports `class-variance-authority` and `radix-ui`. Install those packages when adding Button, and inspect generated imports before applying other components. Keep only reviewed output under `web/src/`.

## Issue and change workflow

1. **Backlog:** The coordinator records scope, acceptance criteria, dependencies and design references on the issue. Resolve concrete gaps before implementation; request separate grooming only for uncertain contracts or consequential decisions.
2. **In progress:** The developer implements one small product slice on a branch and opens a draft PR early. Link the issue, run relevant checks and capture screenshots for changed UI.
3. **Review:** An independent reviewer checks the actual diff against scope and evidence. The coordinator routes findings back to the developer and requests focused re-review. Ask the user for relevant design/device checks or unresolved decisions; record completed acceptance evidence once.
4. **Done:** The user retains final merge approval. Mark Done after the PR is merged and accepted evidence is recorded. Use `Closes #N` when the PR completes the issue; otherwise link the issue and leave it open.

Use only Backlog, In progress, Review and Done on the **RomPortal UI Overhaul** project's Status field. Add missing issues to the project. Labels describe issue type, not workflow status.

The coordinator owns dispatch and status reconciliation; the user should not relay routine messages. Keep one product slice active, with at most one independent admin task. Coordinate shared configuration/instruction edits with active work.

Each dispatch/handoff names the exact issue/PR, branch/worktree, current commit, next action, relevant evidence and unresolved decisions. Read only the context needed for that task. Assign CI monitoring to one worker. Reuse passing checks for unchanged code; repeat checks when changes, failures or unresolved concerns justify them. A routine fix needs a builder pass, focused independent review and one consolidated merge summary.

Use repository branch prefixes such as `sprint/`, `fix/`, `feat/`, `docs/` and `test/`; do not use `codex/`.

## Engineering boundaries

- `web/` contains the React source. Vite builds it into `app/src/main/assets/web/`; that directory is generated and ignored. The Android Gradle build invokes the web build.
- `app/src/main/java/com/romportal/app/server/` owns Ktor routes, PIN authentication, sessions, and file operations. `app/src/main/java/com/romportal/app/service/` owns server lifecycle and idle timeout behavior.
- Treat the existing `POST /login` status, body, and HttpOnly cookie behavior as a contract unless the issue explicitly changes it. Preserve authenticated `/api/*` behavior and SAF root containment when working on browser UI.
- Reuse existing code and platform features before adding dependencies or abstractions. Keep changes within the issue's scope; preserve input validation, error handling, security, and accessibility.

## Evidence at handoff

- Developer handoff: summarize changed behavior, deviations from the issue, commands and results, and manual UI checks. For browser changes run the web typecheck and build; for server or Android changes run affected unit tests and a debug build.
- Reviewer handoff: separate issue/spec findings from code-quality findings. State what remains unverified, especially visual and interaction behavior that a passing build cannot prove.
- Record acceptance evidence and any explicitly accepted exceptions in the PR. The final handoff gives the user one merge recommendation with checks, relevant preview/screenshots and remaining limitations; avoid a separate routine verification ceremony.
