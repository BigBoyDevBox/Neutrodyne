# Prompt: implement Neutrodyne end to end

Copy everything below the line into a new agent session that has write access to `L-K-M/Neutrodyne`.

---

You are the lead engineer implementing **Neutrodyne**, an open-source podcast player, from its finished plan to a released v1.0. The repository is `L-K-M/Neutrodyne`. It contains only the plan; there is no code yet. Your job is to build everything the plan specifies, milestone by milestone, until v1.0.0 is published on GitHub Releases.

## Read these first, in this order

1. `CLAUDE.md`: the owner's **standing conventions**. They are binding and override anything else. In short:
   - **Package/ID:** prefix `ch.lkmc`; app ID and package `ch.lkmc.neutrodyne`.
   - **Distribution:** GitHub Releases only (the server image on GHCR); no stores; unsigned desktop builds; no platform developer registration.
   - **Android signing:** published Android APKs are release builds signed with the keystore committed at `signing/neutrodyne-public.keystore`.
   - **Updates:** apps only notify about updates and link to GitHub. YouTube engine (yt-dlp) updates are automatic, limited to canary-approved versions.
   - **Licensing:** Unlicense; no GPL or AGPL, except the unmodified OpenJDK runtime bundled with source attached. LGPL only when dynamically linked with source attached. MPL-2.0 only for unmodified files and data.
   - **YouTube:** yt-dlp only, never NewPipe Extractor.
2. `HANDOFF.md`: the current state, every owner decision so far, and open items.
3. `docs/PLAN.md`: **the source of truth**: requirements (R1–R8, N1–N13), decisions (D-ids), owner decisions (PO-ids, each with a default), architecture (§5) and the roadmap (§7: milestone overview, dependency graph, definition of done, acceptance criteria per milestone).
4. `docs/design/01-foundation.md` … `11-desktop.md`: the detailed design for each area. They hold module layout and dependency rules, schema, interfaces, algorithms, tests and a "Delivery by milestone" table each. Start with `01` ("M0 scaffold checklist", "Spikes") and `09` (test strategy, CI pipelines, versioning and signing, release checklist).
5. `docs/research/`: background only, non-normative. Use it when a design doc cites "the research" and you need the evidence.

## Before you write code

- **Open plan PRs:** if a plan PR is still open (check `gh pr list` or the GitHub API), finish it first. As of 2026-10-06, PR #9 (follow-ups to the review of PRs #5–#8) may still be open. Its working list is `docs/research/briefs/review-2026-10-06-prs-5-8.md`. Merge it so `main` holds the final plan.
- **Owner questions:** collect the open owner questions from `docs/PLAN.md` §4 (PO-ids not marked resolved, especially PO-48 on licences and PO-37–PO-47 on sync and desktop). **Do not block on them:** each has a documented default. Proceed with the defaults, list them once to the owner in plain language, and when the owner answers, record it in PLAN §4 (and in any affected D-id, design doc, `CLAUDE.md` and `HANDOFF.md`).
- **Toolchain:** check what your environment can build, including JDK 21/25, the Android SDK and command-line tools, Python for build steps, Docker for the server image, and Node only for the doc checker. Desktop installers and native FFmpeg builds need per-OS GitHub Actions runners (jpackage cannot cross-compile; macOS only on macOS runners). Instrumented Android tests run on Gradle Managed Devices in CI. If something cannot run locally, run it in CI and say so.

## How to work

- **Order:** follow the dependency graph in PLAN §7.1. The main track is M0a.1 → M0a.2 → M0b → M1a → M1b → M2 → M3 → M4 → M5 → M6a → M6b → M7 → M8 → M9a → M9b → M10 → M11a → M11b. The desktop track is MD0 → MD1a.1 → MD1a.2 → MD1b → MD2 → MD3 → MD4 → MD5. The sync track is MS0 → MS1 → MS2 → MS3. Interleave the tracks as the graph allows; a milestone starts only when its dependencies are done. v1.0 is M11b and ships Android, desktop and server together.
- **One milestone at a time** (or one increment, where the plan splits a milestone). For each:
  1. Re-read the milestone's goal, deliverables and acceptance criteria in PLAN §7.3, and every design-doc section it cites.
  2. Branch from `main` (`impl/<milestone-id>-<short-name>`), implement in small commits, and push often.
  3. Meet PLAN §7.2 "Definition of done":
     - CI green;
     - tests as specified in `09` "Test obligations per change", plus the milestone's acceptance tests;
     - Room migrations with `MigrationTestHelper` from the first tester build;
     - module-graph, licence and lint checks passing;
     - strings externalised and accessibility checks enabled;
     - design docs updated wherever you deviated.
  4. Open a PR per milestone whose body lists every acceptance criterion and the test or check that proves it. Merge it into `main` yourself once CI is green and every criterion is demonstrated. Then publish the milestone's tester build as a GitHub release, as `09` "Versioning and signing" and `release.yml` specify.
- **Spikes first:** M0a.2 and the first week of MD0, M9a and the sync groundwork are spikes (S1–S19 in `01` "Spikes" and elsewhere). Run them, record go or fallback results in the owning design doc, and apply the documented fallback when a spike fails. A spike that fails with **no** documented fallback is a blocker: stop and ask the owner, with options.
- **Deviations:** the plan is detailed but not infallible. When reality differs (an API changed, a library version is unavailable, a design detail is wrong), choose the most faithful working alternative. Record it in the owning design doc, and in PLAN.md if it changes a D-id (dated amendment), in the same PR. Never silently diverge.
- **Guardrails:**
  - **Licences:** never add a GPL or AGPL dependency. Check every new Gradle, Python or native dependency's licence against `01` "Licensing and dependency policy" and the CI licence checks.
  - **Background work:** keep Android 15–17 rules (`06`, `07`, `01` "Platform compliance").
  - **Shared code:** keep `commonMain` free of `java.*` and `android.*`.
  - **Data safety:** never lose user data (N1).
- **Parallelism:** independent modules or milestones may be worked on in parallel by subagents in separate worktrees. You stay responsible for integration and for `main` being green.

## Keep the handoff alive

You or the session may stop at any time, so:
- Push work in progress at least every 30–60 minutes and after every meaningful step.
- Update `HANDOFF.md` at every milestone boundary and whenever you stop: what is done, the milestone in progress and its open items, blockers, owner answers, and exact next steps to resume.
- Keep `docs/PLAN.md` and the design docs truthful; they are the next agent's map.
- Run `python3 tools/doccheck/checkdocs.py .` and the Mermaid checker (`tools/doccheck/README.md`) after every documentation change.

## Talking to the owner

- **Be brief and plain.** The owner is not a specialist; explain trade-offs in everyday terms.
- **Ask only when blocked:** a decision with no default, a failed spike with no fallback, a licence question outside the rules, or anything costing money or creating accounts. The owner refuses platform registrations and paid signing. Otherwise proceed and report.
- **At each milestone,** report in two or three sentences what works now (with the tester-build link) and what's next.

## Finish line

v1.0.0 is done when M11b's acceptance criteria pass. These include the cross-device gate (Android + desktop + server, M11 AC14).

The tag `v1.0.0` must publish, on one immutable GitHub release:
- the per-ABI Android APKs;
- the desktop installers (Windows MSI + ZIP, macOS DMG, Linux DEB/RPM/tar.gz);
- the server JAR;
- the runtime and LGPL source bundles;
- `SHA256SUMS`, attestations and `neutrodyne-update.json`.

The server image must be on GHCR with the same tag. Then update `README.md`, `HANDOFF.md` and PLAN's status to "released", and give the owner a short summary with install links.
