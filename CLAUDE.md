# Neutrodyne — notes for Claude sessions

Status: planning only; no code yet. [docs/PLAN.md](docs/PLAN.md) is the source of truth (requirements, decisions, owner decisions, roadmap); the design docs in [docs/design/](docs/design/) elaborate it. Implement milestone by milestone and record any deviation in the owning document (and in PLAN.md for D-ids).

Standing owner conventions — apply them without asking:

- **Package and ID prefix:** always `ch.lkmc`. Application ID and Kotlin base package `ch.lkmc.neutrodyne` (debug `ch.lkmc.neutrodyne.debug`).
- **Distribution:** GitHub Releases only. No Google Play, F-Droid or other stores, and no product flavors for stores.
- **No Google developer verification registration.** Install and update guidance covers the advanced flow instead.
- **Licensing:** the repository is Unlicense. Never add GPL, LGPL or AGPL code or dependencies, neither Gradle nor Python. YouTube extraction uses yt-dlp (Unlicense) embedded via Chaquopy, never NewPipe Extractor.
