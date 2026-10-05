# Neutrodyne — notes for Claude sessions

Status: planning only; no code yet. [docs/PLAN.md](docs/PLAN.md) is the source of truth (requirements, decisions, owner decisions, roadmap); the design docs in [docs/design/](docs/design/) elaborate it. Implement milestone by milestone and record any deviation in the owning document (and in PLAN.md for D-ids).

Standing owner conventions — apply them without asking:

- **Scope of v1.0:** the Android app, the desktop app (Windows, macOS on Apple Silicon, Linux) and an optional self-hosted sync server, all shipped together in v1.0. The server syncs subscriptions and listening state, never audio or feed contents.
- **Stack:** Kotlin Multiplatform with Compose Multiplatform (targets `android` and `jvm("desktop")`; no iOS), and a Kotlin + Ktor sync server that shares code with the clients. The Android-specific parts stay native: Media3, WorkManager / user-initiated jobs, Android Auto, and Chaquopy for yt-dlp.
- **Package and ID prefix:** always `ch.lkmc`. The application ID and Kotlin base package are `ch.lkmc.neutrodyne`.
- **Distribution:** GitHub Releases only (container images on GHCR count as GitHub). No Google Play, F-Droid, winget, Homebrew, Flathub or other stores, no mirror and no beta channel. Desktop builds are unsigned.
- **Android builds:** published APKs are optimised, non-debuggable release builds signed with the keystore committed to the repository. There is no key management or key ceremony.
- **No platform developer registration:** neither Google's Android developer verification nor Apple's Developer ID. Install and update guidance covers the workarounds.
- **Updates:** apps only check GitHub and notify, linking to the release page and the right download. They never download or install app updates themselves. YouTube engine (yt-dlp) updates are automatic, limited to versions approved by our own canary.
- **Licensing:** the repository is Unlicense.
  - Never add GPL or AGPL code or dependencies (Gradle, Python or native). There is one exception: the unmodified OpenJDK runtime bundled with desktop installers and the server image, with its source attached to each release.
  - LGPL is allowed when dynamically linked with its notices met (for example the LGPL FFmpeg build used for desktop playback). MPL-2.0 is allowed for unmodified files and data.
  - YouTube extraction uses yt-dlp (Unlicense), never NewPipe Extractor.
- **Brand:** the app icon source is `media-sources/icon.png` (a vacuum-tube "N", amber on navy).
