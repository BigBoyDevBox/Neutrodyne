# Neutrodyne — notes for Claude sessions

Status: planning only; no code yet. [docs/PLAN.md](docs/PLAN.md) is the source of truth (requirements, decisions, owner decisions, roadmap); the design docs in [docs/design/](docs/design/) elaborate it. Implement milestone by milestone and record any deviation in the owning document (and in PLAN.md for D-ids).

Standing owner conventions — apply them without asking:

- **Package and ID prefix:** always `ch.lkmc`. Application ID and Kotlin base package `ch.lkmc.neutrodyne` (no `.debug` suffix).
- **Distribution:** GitHub Releases only. No Google Play, F-Droid or other stores, no product flavors for stores, no mirror and no beta channel.
- **Debug builds only:** every published APK is a per-ABI debug build signed with the debug keystore committed to the repository. There is no release key or key ceremony.
- **Updates:** the app only checks GitHub and notifies, linking to the release page and the APK for the device. It never downloads or installs APKs itself. YouTube engine (yt-dlp) updates are automatic, limited to versions approved by our own canary.
- **No Google developer verification registration.** Install and update guidance covers the advanced flow instead.
- **Licensing:** the repository is Unlicense. Never add GPL or AGPL code or dependencies, neither Gradle nor Python. LGPL is allowed (owner decision 2026-10-05) when dynamically linked with its notices met; the plan still records which components use it. YouTube extraction uses yt-dlp (Unlicense) embedded via Chaquopy, never NewPipe Extractor.
