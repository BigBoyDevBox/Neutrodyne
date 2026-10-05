# Distribution research: GitHub Releases only, no Google developer verification

Research date: 2026-10-05. Scope: what the plan must do now that the product owner (PO) has decided to (1) ship only through GitHub Releases, (2) not register for Android developer verification, and (3) use the `ch.lkmc` package prefix. Every non-obvious claim has a source in [Verified facts](#verified-facts-with-source-urls-and-dates). Anything not confirmed from a primary source is marked **Unverified:**.

---

## Recommendation

1. **Ship one signed universal APK per tag on GitHub Releases, unregistered, and plan for the 2027 friction instead of hoping it goes away.** Before global enforcement starts, nothing changes for GitHub users anywhere, including Brazil, Indonesia, Singapore and Thailand: the 2026-09-30 phase covers only installs from seven named stores. From the global rollout in 2027 (Google has not published a date), every new install and every update of Neutrodyne on a certified Android 8+ phone or tablet with Google services is blocked unless the user has turned on the one-time "advanced flow" (Developer options, an anti-coercion check, a restart, a 24-hour wait, then biometric or PIN confirmation, then "7 days" or "indefinitely") or installs over ADB. Devices without Google certification (GrapheneOS, LineageOS without GApps, /e/OS, Huawei and Amazon devices) are not affected.
2. **Add an in-app updater** (new permissions `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; needs a PLAN amendment, because 01 freezes the permission set). Reasons: GitHub is now the only channel; YouTube extractor hotfixes come roughly monthly; and from 2027 every update has to get through the verification gate, so the app must spot a blocked update and explain it. The design is in [In-app updater](#in-app-updater-api-2637):
   - Stable channel: fetch a per-release `neutrodyne-update.json` through `https://github.com/<owner>/Neutrodyne/releases/latest/download/…`. This is not the REST API, so it does not count against the 60 requests per hour per IP limit.
   - Beta channel: read the repository's `releases.atom` feed.
   - Verify the SHA-256 and the signing certificate before installing.
   - Install with a `PackageInstaller` session: a silent self-update on API 31+, gentle install constraints on API 34+, and a confirmation dialog on API 26–30.
   - Handle `STATUS_FAILURE_ABORTED` plus `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` (API 36.1+) with a help screen and a fallback to "Download in browser".
   - Keep Obtainium as a documented alternative. Users choose one updater; the setting is off when Obtainium is used.
3. **Write the user guidance now and ship it before enforcement:**
   - a README "Install and update" section;
   - an in-app "Install & updates" help page;
   - a one-time in-app notice in the last releases before the global date: "turn on 'Allow apps from unverified developers', choose *indefinitely*, or updates (including YouTube fixes) will stop".
   Recommend "indefinitely" over "7 days": once the setting is off, updates of unregistered apps fail.
4. **Treat ADB, Shizuku and de-Googled ROMs as documented power-user fallbacks, not as the main path.**
   - The AOSP source exempts installs whose caller runs as shell or root (ADB, and therefore Shizuku-based installers such as Obtainium in Shizuku mode).
   - Shizuku must be restarted after every reboot through Wireless debugging (Android 11+), which keeps Developer options on. Some banking apps refuse to run with Developer options on.
   - Google can close this route without an OS update. The advanced flow and the verifier ship through Google's own components.
5. **Drop store work and keep supply-chain work.**
   - Drop: the `play` flavor and the Play checklist (PEPK, upload key, Data safety, FGS declarations, listing), the F-Droid recipe, IzzyOnDroid, `fastlane/metadata`, blocking reproducibility gates, `apksigcopier` and the developer-verification registration steps.
   - Keep or strengthen: offline key custody with two holders. There is no Play App Signing escrow, a lost key forces every user to uninstall, and per Google it also makes later registration impossible.
   - Signing: v2 and v3 (v1 off). Write a key-compromise runbook based on v3.1 key rotation.
   - Publishing: GitHub **immutable releases** (draft, upload every asset, publish), SLSA build provenance with `actions/attest@v4`, `SHA256SUMS`, and the certificate SHA-256 in the README and every release body, in AppVerifier format.
   - Keep monotonic version codes and targetSdk 37. No store enforces a target API any more, but the installer's own target SDK now changes how verification failures are surfaced (see Technical detail).
6. **Package name `ch.lkmc.neutrodyne`** (debug: `ch.lkmc.neutrodyne.debug`) is valid and needs no registration for GitHub distribution. Freeze it before the first public APK: changing it later creates a different app for Android.

**What would change this recommendation:**
- Google publishes a 2027 date earlier than about Q2, or tightens the advanced flow (for example by ending "indefinitely", or by requiring it again for every app or update). Then the pre-enforcement notice and updater fallback move into the next release, and the PO should reconsider registration.
- Device testing shows that a self-updater targeting API 37 cannot complete an install even with the advanced flow on. Then the updater changes to "download, then hand off to the system installer or browser" (see Pitfalls).
- GitHub disables the repository or account (DMCA or abuse report against a YouTube-extracting app). GitHub is the single channel, so a mirror would be needed (see Open questions).
- The licensing or YouTube research picks an embedded runtime (Python/yt-dlp, QuickJS) whose native libraries push the universal APK past the 25 MB budget. Then per-ABI APKs are needed.

---

## Options considered (trade-off table)

### A. How users get past developer verification (from the 2027 global rollout)

| Option | User friction | PO constraints | Robustness | Verdict |
|---|---|---|---|---|
| **A1. Unregistered + advanced flow guidance + in-app updater** | One-time: Developer options, anti-coercion check, restart, 24 h wait, biometric or PIN, choose 7 days or indefinitely. Every install or update still shows "unverified developer" with "Install anyway". | Meets all (no identity given to Google) | Depends on Google keeping the flow. It runs in Google components (Developer Verifier, Play services), not in AOSP. | **Recommended**, the only option that fits the PO's decision |
| A2. Full verification (Android Developer Console "full distribution") | None for users | Violates the decision: $25 and a government ID; a real name tied to a YouTube-extracting app | Highest | Rejected by PO; remains possible later only if the same signing key is kept |
| A3. Limited distribution account | Users accept an invitation (QR or link handshake); at most 20 authorised devices | Free and no government ID, but needs a Google Account with 2-Step Verification and a Google payments profile holding legal name and address | Not a public-distribution mechanism | Not suitable. **Unverified:** how a package registered to a limited account behaves on non-authorised devices; do not mix it with A1 |
| A4. ADB install/update | PC or Wireless debugging for every update | Fits | Exempt by design (Google FAQ, AOSP code) | Fallback for power users; impractical for monthly hotfixes |
| A5. Shizuku-based installer (Obtainium "Use Shizuku", others) | Shizuku restarted after every reboot through Wireless debugging (Android 11+, Wi-Fi); Developer options stay on | Fits | Bypasses because the session caller runs as shell UID → `INSTALL_FROM_ADB`. Google could close this. **Unverified** on a device with enforcement active | Documented fallback; do not build into Neutrodyne v1 |
| A6. Non-certified OS (GrapheneOS, LineageOS without GApps, /e/OS, Huawei, Fire OS) | None | Fits | Not subject (Google Help: AOSP and non-certified devices exempt) | State it in the README; it is the audience's natural home |

### B. How updates reach users

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| B1. No updater; README + Obtainium only (current plan) | No new permissions, no code | Most users never install Obtainium. YouTube hotfixes reach few users. No in-app explanation when an update is blocked | Not enough for GitHub-only |
| B2. Update notifier (check, notify, open the release page) | No install permission; the browser and system installer handle verification UX | Several taps per update; the user must find the APK; the downloaded APK is not checked against our checksum | Fallback path inside B3 |
| **B3. In-app updater** (check, download, verify SHA-256 and certificate, PackageInstaller session) | Silent self-update on API 31+ where allowed; can detect and explain verification blocks; can defer while audio plays | Two new permissions; ~1 week of work; Neutrodyne becomes an "installer", so its own targetSdk (37) decides how verification failures are surfaced; must coexist with Obtainium | **Recommended**, with B2 as the fallback and an Obtainium opt-out |

### C. Release artefact shape

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **C1. One universal APK** | One file for browser, Obtainium and updater; no ABI choice | Larger when native code grows (currently only `sqlite-bundled`) | **Recommended** while under 25 MB |
| C2. Per-ABI APKs (+ universal) | Smaller downloads | Obtainium needs `autoApkFilterByArch` or a regex; the updater must choose by `Build.SUPPORTED_ABIS`; more assets to attest | Only if an embedded runtime blows the budget |
| C3. AAB / split APKs | — | Not installable from a browser without a split installer | No |

---

## Technical detail

### 1. Android developer verification: current rules (as of 2026-10-05)

**Phases**

| Phase | Date | Scope | Effect on a GitHub-sideloaded Neutrodyne |
|---|---|---|---|
| Tooling | Mar–Aug 2026 | Consoles open (Mar); Android Developer Verifier system service (`com.google.android.verifier`) pushed to devices (Apr–Jul); limited distribution accounts and the **advanced flow** launched globally in Aug 2026 (gradual rollout) | None |
| Phase 1 | since **2026-09-30** | Certified devices in **Brazil, Indonesia, Singapore, Thailand**, and only installs **from seven participating stores**: Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps. Off-Play enforcement covers phone and tablet form factors only | **None.** Google FAQ (2026-07-15): "if users sideload your app directly, these new verification requirements won't apply to your app yet" |
| Phase 2 | **"2027"** (no date published) | "Globally for all apps on certified Android devices"; "will soon be expanded to all third-party Android app stores" | New installs and updates blocked unless the advanced flow is on or ADB is used |

- **Unverified:** whether the 2027 rollout is itself staged (stores before direct sideloads, or by region). Keep Android Open uses "January 2027" only as a placeholder ("The date … is not yet published").
- Google contradicts itself on Android versions: "Android 7+" on developer.android.com and "Android 8 and up" in the Android Help Center. Neutrodyne's minSdk 26 (Android 8.0) is covered by both.

**Devices affected**

- **Certified Android devices only** (Play Protect certified, preloaded GMS). Google Help: "Android Open Source Project (AOSP) and non-certified devices are exempt … does not apply in regions where Google Mobile Services are unsupported or in territories subject to applicable trade sanctions." The FAQ adds that checks do not run on devices physically located in comprehensively sanctioned countries.
- **GrapheneOS:** not certified. Its sandboxed Google Play is an unprivileged app and cannot be the system's verifier. Community statements say it is unaffected; I found no official GrapheneOS project statement.
- **LineageOS:** official statement, 2026-07-04. The verifier is a separate app (`com.google.android.verifier`) wired in through the framework overlays `config_developerVerificationServiceProviderPackageName` and `config_developerVerificationPolicyDelegatePackageName`. LineageOS ships neither the app nor the overlays, so it is unaffected. A GApps package could in theory bundle and enable it.
- **Android Automotive / TV / Wear:** phase 1 off-Play enforcement applies "only to mobile and tablet form factors". Neutrodyne does not target AAOS distribution anyway (06: AAOS needs Play's car review).
- **Emulators and CI devices:** the app is installed over ADB (GMD, Android Studio), which is exempt.

**Exempt install paths**

- **ADB:** "no changes to how ADB works"; the waiting period does not apply.
- **AOSP (`android16-qpr2-release`):** `PackageInstallerService` sets `INSTALL_FROM_ADB` whenever `isRootOrShell(callingUid)`. `PackageInstallerSession.shouldUseVerificationService()` returns false for `INSTALL_FROM_ADB` unless `forceVerification` is set. So any installer whose binder calls run as shell or root UID (Shizuku, root installers) is exempt in the current source.
- **Android Studio:** deploys over ADB, so exempt.
- **Enterprise:** "Apps distributed through your organization store, on managed devices, won't need to complete the verification requirements". Not relevant to consumers.

**The advanced flow (user steps, per Google Help 17588095 and the 2026-03-19 blog)**

1. Turn on Developer options (Settings › About phone › tap Build number 7 times).
2. Settings › System › Developer options › **Allow apps from unverified developers** → on.
3. Confirm nobody is coaching you (anti-coercion check) and re-authenticate.
4. The phone restarts.
5. Come back after the **one-time 24-hour wait**; confirm with biometrics or PIN.
6. Choose **7 days** ("Turn on temporarily") or **indefinitely**.
7. Each install of an unverified app still shows a warning: tap **"Install anyway"**.

**Scope and persistence**

- **Per device:** a one-time setup, not per app. Developer options can be switched off afterwards ("Once you make the change on your device, it's enabled").
- **Unverified:** whether it is per Android user or profile. AOSP stores the verification policy per userId, so a work profile or secondary user probably needs its own setup.
- **Updates:** "Unregistered apps can only be installed or updated when the advanced flow is enabled or by using ADB so if the advanced flow is disabled updates to unregistered apps will fail". This includes the 7-day option expiring.
- **Unverified:** whether turning it back on after 7 days repeats the 24 h wait. Google calls the wait "one-time".
- No ADB command bypasses the 24 h wait ("not supported at this point").
- **Already-installed apps:** every Google document describes install-time and update-time gating only. **Unverified:** that Google will never disable an installed unregistered app. Nothing announced says it will.
- **Delivery:** the flow ships through Google's verifier and Play services, "for all versions of Android" (9to5Google, 2026-03-19). On Android 16 QPR2 and later the native install hook does the blocking. On older versions, Google described "backwards compatibility leveraging Google Play Protect" (Google video, quoted by agnostic-apollo). **Unverified:** how the flow and the block look on Android 8–15.
- **Third-party stores and installers:** for unregistered apps the source does not matter (F-Droid, Obtainium, browser). "It doesn't matter where the APK is downloaded from" (XDA, 2026-08-19).

**Limited distribution account:** free, no government ID, up to 20 devices "that end-users have explicitly authorized" via QR or link handshake. Needs a Google Account with 2-Step Verification and a Google payments profile "so you can provide and manage your legal name and address". It can be converted to full distribution, not back. It is not usable for public GitHub releases.

**Package-name allocation (relevant even unregistered):**
- Rules when several keys claim a name: a key with more than 50 % of known installs has priority; any key with 50 or more installs ("sizeable cluster") can register; otherwise first come, first served.
- Before Neutrodyne has users, someone else could register `ch.lkmc.neutrodyne` with their own key.
- **Unverified:** what that does to installs of our differently signed APK. Most likely they are still "unverified", so the advanced flow applies. A stronger "impersonation" block is possible but undocumented.

### 2. Consequences for Neutrodyne's users, phase by phase

| Phase / device | First install from GitHub | Update | Android Auto |
|---|---|---|---|
| Now → global date, any country, any device | Browser download → "allow <browser> to install unknown apps" (once per source app) → install dialog. Play Protect may offer a scan of an unknown app (**Unverified** frequency) | In-app updater: silent on API 31+ when Neutrodyne installed itself or updates itself (conditions below); a dialog on API 26–30. Obtainium: its own flow | Sideloaded media apps appear in Android Auto only after Auto's developer setting "Unknown sources" (06 already notes this; [Android Authority](https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/)) |
| From 2027, certified device, advanced flow **off** | Blocked: "App developer unverified … only apps from verified developers can be installed" (string from AOSP-era Google installer; final wording **Unverified**) | Every update blocked; the installed version keeps running with an ageing YouTube extractor | Unchanged |
| From 2027, certified device, advanced flow **on (indefinitely)** | Warning + "Install anyway" | Warning + "Install anyway" per update. **Unverified** for silent self-updates (`USER_ACTION_NOT_REQUIRED`) and for installers targeting API 37 (see below) | Unchanged |
| From 2027, advanced flow on for **7 days**, expired | As "off" | As "off" | — |
| Non-certified device (GrapheneOS, LineageOS, /e/OS, Huawei, Fire) | As today | As today | As today (if Auto runs at all) |

**Risk to existing users at the global start.** Anyone who has not turned on the advanced flow is frozen on their installed version. For a podcast player that is tolerable. For the YouTube layer it means playback breaks at the next extractor-breaking YouTube change, and those users cannot get the hotfix. Support load lands on the issue tracker. Mitigations:
- the pre-enforcement in-app notice;
- the updater's "update blocked" explanation;
- a pinned GitHub issue or Discussion "Updates stopped working?";
- the README section.

### 3. In-app updater (API 26–37)

**Feed**

- Stable: each release carries `neutrodyne-update.json`:
  ```json
  {
    "versionCode": 1000095, "versionName": "1.0.0", "minSdk": 26,
    "apk": "neutrodyne-1.0.0.apk", "size": 23456789,
    "sha256": "…", "certSha256": "…",
    "notes": "…", "published": "2026-…Z"
  }
  ```
  The app fetches `https://github.com/<owner>/Neutrodyne/releases/latest/download/neutrodyne-update.json`. That URL 302s to `/releases/download/<tag>/…`, which 302s to a signed `release-assets.githubusercontent.com` URL with about one hour validity (observed 2026-10-05). "Latest" is "the most recent non-prerelease, non-draft release", so testers' pre-releases never reach stable users.
- Beta (opt-in): parse `https://github.com/<owner>/Neutrodyne/releases.atom`. It includes pre-releases; Neutrodyne's own feed parser can read it. Then fetch `/releases/download/<tag>/neutrodyne-update.json`. Pre-releases are recognised by the `-beta.N`/`-rc.N` suffix of the tag.
- **Avoid the REST API** (`api.github.com`): unauthenticated limit of 60 requests per hour per IP. Under CGNAT many users share one IP, and a conditional request answered with `304` is exempt only when authenticated. If it is ever used, honour `x-ratelimit-remaining` and `x-ratelimit-reset`.
- **Unverified:** GitHub's limits for unauthenticated `releases/download` and Atom requests. The 2025-05-08 tightening named REST, git-over-HTTPS and `raw.githubusercontent.com`. Downloads are not published, so treat 429 and 403 with backoff.
- **Immutable releases forbid editing assets**, so there can be no rolling "channel.json" asset. The per-release JSON plus `latest/download` is the immutable-safe design.

**Scheduling and settings**

- WorkManager periodic job every 24 h, with a network constraint and jitter, plus "Check now" in About.
- Setting `updates.mode`: `off`, `notify` (default), `download_and_prompt`.
- Setting `updates.channel`: `stable`, `beta`.
- First-run choice and network inventory ID `updates`: hosts `github.com`, `release-assets.githubusercontent.com`. This fits the "only hosts the user chose or opted into" privacy commitment if the PO decides between default-on and opt-in (open question).

**Download and pre-install checks**

- Download through 07's engine to `noBackupFilesDir/updates/`.
- Check size and SHA-256 against the JSON.
- `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` on API 28+ (`GET_SIGNATURES` on 26–27):
  - `packageName == ch.lkmc.neutrodyne`;
  - `versionCode >` the installed one;
  - certificate SHA-256 equals the running app's.
- Android enforces signature continuity anyway; the pre-check gives a clear error and avoids a broken session.

**Permission UX**

- If `packageManager.canRequestPackageInstalls()` is false, explain why, then open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:ch.lkmc.neutrodyne` (API 26+). The grant is per source app.
- Users who installed from a browser have granted the browser, not Neutrodyne. Neutrodyne needs its own grant once.

**Install session**

1. `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`, then `setAppPackageName("ch.lkmc.neutrodyne")`, then `setSize(size)`.
2. **API 31+:** `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` and declare `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (normal permission). A silent update additionally needs:
   - the APK to target ≥ 35 when the device runs API 37 (≥ 34 on 36, ≥ 33 on 35, …);
   - the installer to be the update owner, the installer of record, or "updating itself". The self-update case always qualifies.
   Always handle `STATUS_PENDING_USER_ACTION` anyway.
3. **API 34+:** commit through `commitSessionAfterInstallConstraintsAreMet(…, InstallConstraints.GENTLE_UPDATE, timeout)`. "Not interacting" includes "playing or recording audio/video" and "sending or receiving network data". The install, which kills the process, then waits until playback and downloads stop. **Unverified:** that the constraint check works when the session updates the calling app itself.
4. **API 26–33:** commit only while the app is idle (no `PlaybackState` playing, no active transfer). Otherwise show "Update ready — install now / when idle".
5. Commit with an explicit, mutable `PendingIntent` to `UpdateStatusReceiver`.
   - `STATUS_PENDING_USER_ACTION`: launch `EXTRA_INTENT` if the app is in the foreground. Otherwise post a notification "Tap to finish updating", because background activity starts are restricted.
   - `STATUS_SUCCESS`: the process restarts. Persist "updated to X" to show "What's new".
   - Failures: `STATUS_FAILURE_CONFLICT` (signature or downgrade), `STATUS_FAILURE_STORAGE`, `STATUS_FAILURE_INCOMPATIBLE`, `STATUS_FAILURE_BLOCKED`, `STATUS_FAILURE_ABORTED`.

**Developer verification on the updater path** (API 36.1+ constants; Android 16 QPR2 and Android 17)

- On failure, `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` carries `DEVELOPER_BLOCKED` (2), `NETWORK_UNAVAILABLE` (1) or `UNKNOWN` (0). `Intent.EXTRA_INTENT` "can provide additional context" from the OS.
- The platform rules (Javadoc plus AOSP `shouldSendUserActionForVerification`):
  - The system package installer always gets a user prompt.
  - An installer targeting **≤ 36** without `INSTALL_PACKAGES` is sent through `STATUS_PENDING_USER_ACTION` so the OS can show the context and possibly let the user bypass.
  - An installer targeting **> 36** gets a user prompt only for *non-blocking* failures (for example network). For a *blocking* "developer not verified" result it receives `STATUS_FAILURE_ABORTED` directly.
  - **Neutrodyne targets 37, so it is in the "> 36" group.**
- **Unverified:** how the proprietary verifier reports an unregistered app when the advanced flow is on. If it sets the policy to `NONE`, the session succeeds; Google's "Install anyway" warning would then come from the normal confirmation UI, and a silent self-update might show none. If instead it reports a blocking failure with a warn policy, a targetSdk-37 self-updater gets `STATUS_FAILURE_ABORTED`, and only the system installer path shows "Install anyway". The updater must therefore:
  - map `DEVELOPER_BLOCKED` to a help sheet: "Android blocked this update because Neutrodyne is not registered with Google. Options: turn on *Allow apps from unverified developers* (Developer options; 24-hour wait; choose *indefinitely*) · update with ADB or Obtainium+Shizuku · download in browser". Offer to start `EXTRA_INTENT` when present;
  - map `NETWORK_UNAVAILABLE` to retry;
  - offer the **hand-off fallback**: download, then `ACTION_VIEW` on the GitHub asset URL (browser → system installer) or on a `content://` URI from a `FileProvider`. **Unverified:** whether the system installer's session is attributed to Neutrodyne as installer (AOSP's `InstallRepository` copies the validated `EXTRA_INSTALLER_PACKAGE_NAME`). If so, the hand-off behaves like the direct session for verification purposes, and only the browser path differs.
- **Test hook:** AOSP adds `adb shell pm set-developer-verification-result <pkg> <policy> <result…>` and `clear-developer-verification-result`. Example: `pm set-developer-verification-result ch.lkmc.neutrodyne 1 2` sets policy "open" and result "reject" for the next session. It only takes effect where a verifier package is configured (`shouldUseVerificationService()` returns false otherwise), so test on a Pixel on Android 17, where Android Authority reports `com.google.android.verifier` present (2026-07-20). **Unverified** on a real device. Add this to the M-milestone device checklist that introduces the updater.
- **Coexistence with Obtainium:** after a self-update Neutrodyne is the installer of record, so Obtainium's next update may need a confirmation tap. Settings › Updates shows "Managed by another app (Obtainium)" when `InstallSourceInfo.getInstallingPackageName()` is Obtainium's package. That needs `<queries>` for `dev.imranr.obtainium` or a name match without the visibility query. Default the updater to `notify` in that case.

### 4. User guidance (draft content)

**README "Install and update" (keep it short; the in-app help mirrors it):**

1. Download `neutrodyne-X.Y.Z.apk` only from `https://github.com/<owner>/Neutrodyne/releases`. There is no Play Store version. Any other copy is not ours.
2. Optional check: compare the SHA-256 with `SHA256SUMS`, or use AppVerifier with:
   ```
   ch.lkmc.neutrodyne
   <AA:BB:… certificate SHA-256>
   ```
   Advanced users can run `gh release verify-asset vX.Y.Z neutrodyne-X.Y.Z.apk -R <owner>/Neutrodyne` or `gh attestation verify neutrodyne-X.Y.Z.apk -R <owner>/Neutrodyne`.
3. Android asks once to allow your browser (or Files app) to install unknown apps.
4. **Phones with Google Play (from 2027):** Android will block apps from developers who have not registered with Google. Neutrodyne is not registered (the reason is linked). To install or update it:
   - Settings › System › Developer options › *Allow apps from unverified developers*;
   - follow the steps (restart, 24-hour wait, fingerprint or PIN);
   - choose **indefinitely** (with *7 days*, updates stop working after a week);
   - each install or update then shows a warning: tap *Install anyway*.
   You can switch Developer options off afterwards.
5. Alternatives: `adb install -r neutrodyne-X.Y.Z.apk` from a computer; Obtainium with Shizuku; phones without Google certification (GrapheneOS, LineageOS, /e/OS) need none of this.
6. Updates: the app checks GitHub once a day (Settings › Updates), or use Obtainium. Badge link format, per the Obtainium wiki: `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/<owner>/Neutrodyne` (the plan's `redirect.html` form also answers 200). Testers enable "Include prereleases".
7. Android Auto: enable *Unknown sources* in Android Auto's developer settings.

**Wording caution:** Google built the flow against "coaching" by scammers, and Neutrodyne's guidance is legitimately similar. Keep it factual: say what is lost (protection against unverified apps), never urge haste, and never instruct while the user is in a hurry (no countdowns, no "do it now or lose the app").

### 5. Mitigations evaluated honestly

| Mitigation | Works today? | Limits |
|---|---|---|
| Advanced flow, "indefinitely" | Yes; launched Aug 2026, gradual rollout | 24 h first-time delay; Developer options needed during setup; Google can change it server-side; per-install warning; **Unverified** per-profile scope |
| ADB | Yes; exempt by design | Needs a PC or Wireless debugging (Android 11+; Android 8–10 need USB every time); unrealistic for monthly hotfixes |
| Shizuku (+ Obtainium) | Yes per AOSP code (shell UID → `INSTALL_FROM_ADB`) | Restart after every reboot; Wireless debugging needs Wi-Fi and Developer options on; apps that refuse Developer options; Google could add `forceVerification` for shell sessions or check the originating UID; **Unverified** on an enforcing device |
| Root / LSPosed hooks | Possible (a hook on the install-time check, per agnostic-apollo) | Niche; breaks Play Integrity-dependent apps; do not document beyond a mention |
| Non-certified ROM | Yes | Requires a supported device; out of our hands |
| Later registration (if the PO changes their mind) | Only with the **same signing key**; the package must not have been claimed by someone else | Google: "If you lose your signing key you won't be able to register your packages" |

### 6. GitHub-only release engineering

**No longer needed (remove from 09, 01, PLAN):**

| Item | Why it existed | Action |
|---|---|---|
| `play` flavor, `check-play-dex.sh`, E8 `PlayFlavorYouTubeTest`, `bundlePlayRelease`, `playDebug` CI legs, Play wording lint, `PlayStringsPolicyTest` | Google Play | Remove the flavor. Keep `:youtube:streams` isolated so an emergency "no extraction" build can still be cut (risk L1). Licensing research decides whether a non-GPL variant is still wanted |
| Play App Signing / PEPK, upload key, `PLAY_SERVICE_ACCOUNT_JSON`, `PLAY_PUBLISHING`, Gradle Play Publisher | Play | Remove |
| Data safety, FGS declarations (Play Console), content rating, target audience (PO-29), store listing, screenshots for stores | Play | Remove the paperwork. **Keep** the manifest FGS types and `dataSync` limits, which the platform enforces |
| F-Droid recipe `fdroid/ch.lkmc.neutrodyne.yml`, `AllowedAPKSigningKeys`, `NonFreeNet` text, fdroidserver dry run, IzzyOnDroid inclusion | F-Droid / IzzyOnDroid | Remove |
| `fastlane/metadata/android/**`, `check-fastlane.sh` | Consumed by F-Droid / IzzyOnDroid (Obtainium ignores it) | Replace with `changelogs/<versionCode>.txt` (release body + `update.json` notes + in-app "What's new") |
| Reproducible-build **gating** (`verify-repro` with `apksigcopier compare`, M11 blocking repro job) | F-Droid shipping our signature | Downgrade: keep the cheap hygiene and the nightly two-build diff as **report-only** (independent verifiability is still a trust signal), drop the release-blocking step |
| "No binaries under `src/`" for F-Droid's scanner | F-Droid | Keep as hygiene only; no longer a gate |
| Developer-verification registration steps | PO-5 default A | Replace with the "unregistered distribution" section (this document) |
| Target-API floor deadlines from stores | Play | No store enforces them, but **keep targeting the newest API**: platform behaviours, Android 15+'s minimum installable targetSdk of 24 (not an issue for a current app), and the installer-side verification rules depend on the target |

**Still matters (keep or add):**

1. **Signing key custody: the most important item now.**
   - No Play-held copy exists; a lost key means users must uninstall and reinstall, losing data except through the manual or auto backup, and later developer registration becomes impossible.
   - Keep 09's ceremony (offline RSA-4096, two holders, two encrypted copies, yearly check) and the CI copy in the `release` environment.
   - Record the certificate SHA-256 in the README, every release body and `update.json`.
2. **Signature schemes:** v1 off (minSdk 26), v2 + v3 on. v4 is irrelevant (incremental ADB installs).
3. **Key-compromise runbook (v3 / v3.1 rotation):**
   - `apksigner rotate --out lineage --old-signer --ks old.p12 --new-signer --ks new.p12`.
   - Then `apksigner sign --ks old.p12 --next-signer --ks new.p12 --lineage lineage app.apk`. The rotated key is used through the v3.1 block on API 33+ by default; `--rotation-min-sdk-version 28` uses it on API 28+.
   - API 26–27 devices verify v2 and keep trusting **only the old key**. AOSP calls rotation "not recommended for Android 12 (API level 31) and earlier".
   - Rotation therefore protects Android 13+ users after a compromise; older devices stay exposed to the old key.
   - Rotation needs the old key, so it does not help after key **loss**.
   - AGP's `signingConfig` has no lineage support, so rotation means re-signing the release APK with `apksigner` in `release.yml`.
4. **GitHub immutable releases** (GA 2025-10-28): enable in repository settings.
   - `release.yml` must create a **draft**, upload all assets (APK, `SHA256SUMS`, `neutrodyne-update.json`, mapping, corresponding source if GPL), then publish.
   - Once published, assets cannot change and the tag is locked. Tag names cannot be reused after deleting a release.
   - A bad asset therefore means a new version, which matches the "version code never reused" rule.
   - Publishing generates a **release attestation** (Sigstore bundle), verifiable with `gh release verify` / `gh release verify-asset` (gh 2.89.0).
5. **Build provenance:** `actions/attest@v4` (latest v4.2.2; `actions/attest-build-provenance` v4 is now a wrapper) on the signed APK and `SHA256SUMS`, with permissions `id-token: write`, `attestations: write`, `artifact-metadata: write`.
   - This gives SLSA v1.0 Build Level 2 on GitHub-hosted runners; Level 3 needs a reusable workflow.
   - Pin by SHA, as 09 already requires.
6. **Checksums:** `SHA256SUMS` remains for users without `gh`. Signing it with a separate key (minisign or SSH) is optional; attestations cover authenticity.
7. **Universal APK** (C1) under 25 MB; re-evaluate if native libraries grow.
8. **Release notes:** the release body comes from `changelogs/<versionCode>.txt` plus the certificate fingerprint plus verify commands. Pre-releases are flagged `prerelease: true`, which keeps them out of `latest` and makes Obtainium need "include prereleases".
9. **Obtainium compatibility** (v1.6.17, released 2026-09-13):
   - Its GitHub source calls `api.github.com/.../releases?per_page=100`, so the 60/h per IP limit applies; users can add a PAT.
   - It considers only APK or container assets (zips and tarballs only when enabled), so `SHA256SUMS`, JSON, mapping and `.tar.gz` do not confuse it.
   - Settings exist for `apkFilterRegEx`, `includePrereleases`, `verifyLatestTag`, `autoApkFilterByArch`.
   - With one APK per release, no regex is needed. Keep tag = `v` + `versionName` so its version detection matches.
10. **Version codes:** keep 09's monotonic scheme. Android refuses downgrades without uninstall, and testers on pre-releases must still receive stable hotfixes (09's `release/X.Y` rule).

### 7. Package name `ch.lkmc.neutrodyne`

- Valid applicationId: at least two segments, each starting with a letter, only `[A-Za-z0-9_]`. The Kotlin `namespace` can be `ch.lkmc.neutrodyne` too. Debug: `applicationIdSuffix = ".debug"` gives `ch.lkmc.neutrodyne.debug`, installed side by side over ADB (exempt).
- Nothing verifies reverse-DNS ownership for GitHub sideloading. Using a domain the PO controls (`lkmc.ch`) is the convention and avoids collisions.
- Optional upside: `https://lkmc.ch/.well-known/assetlinks.json` could later enable verified App Links.
- Google's console would only check a website (Search Console) for **organisation** accounts, which is not relevant here.
- Freeze it in M0 before the first signed pre-release. Changing the applicationId later is a new app for Android: no in-place update, and data stays with the old package.

### 8. Concrete plan changes

| Document | Change |
|---|---|
| PLAN §3 D2 | One distribution: GitHub Releases (`foss` only); the `play` flavor is removed (coordinate with the licensing outcome) |
| PLAN §3 D61 | One key, never lost; immutable releases + attestations; no Play/F-Droid; registration deliberately not done (PO-5 = "no") |
| PLAN PO-2 / PO-5 / PO-29 | Resolved: GitHub only / no verification / not applicable |
| PLAN risks | Rewrite **P3** (likelihood high, impact high for users on certified devices; mitigations: advanced-flow guidance, updater with failure handling, pre-enforcement notice, ADB/Shizuku docs). Add **P5** "GitHub is the single channel" (account or repo takedown) and **P6** "package name claimed by a third party before we have installs" (low/medium) |
| PLAN M11 AC | Remove F-Droid/IzzyOnDroid/Play items. Add: immutable release with valid `gh release verify`; provenance attestation verifies; updater installs over the previous release on API 26, 31 and 37; simulated verification rejection (`pm set-developer-verification-result`) shows the help sheet |
| 01 Permissions | Add `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (`:feature:update` or `:core:update`), and `<queries>` for Obtainium only if the "managed by Obtainium" hint is wanted. PLAN amendment, because 01 lists the set as closed |
| 07 Downloads | Updater download uses the transfer engine; `updates` transfer class (no auto-retry storms; Wi-Fi-only not required for ~25 MB, PO choice) |
| 08 UI | Settings › Updates (mode, channel, check now, last check, source); About › "Install & updates" help; update-ready notification; "update blocked by Android" sheet; one-time pre-enforcement notice |
| 09 | Replace Distribution channels, Developer verification, Play App Signing and store metadata with this design; `release.yml`: draft → assets → `actions/attest@v4` → publish; `update.json` generated by `scripts/ci/make-update-json.sh`; network inventory ID `updates`; repro job report-only |
| 04 hotfix runbook | The final step relies on the updater (stable) and Obtainium; record median time-to-update from release download counts |

---

## Verified facts (with source URLs and dates)

All read on 2026-10-05 unless stated otherwise.

| # | Fact | Source |
|---|---|---|
| V1 | Timeline: Aug 2026 APIs, limited distribution and advanced flow launch; 2026-09-30 "Protections begin for all users who install apps from participating stores in Brazil, Indonesia, Singapore, and Thailand on certified devices running Android 7+"; "Verification capability will be expanded globally for all apps on certified Android devices in 2027" | https://developer.android.com/developer-verification |
| V2 | Seven participating stores (Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps); "The verification capability will soon be expanded to all third-party Android app stores"; certified devices: "these requirements apply to you, regardless of your app's download source" (page updated 2026-08-18) | https://developer.android.com/developer-verification/guides |
| V3 | FAQ (updated 2026-09-30): sideloads and non-listed stores not affected in the Sept phase (entry 2026-07-15); off-Play enforcement "only … mobile and tablet form factors" (2026-07-15); ADB exempt (2025-09-03 / 2026-03-23); 24-hour wait, not for ADB, no ADB bypass of the wait (2026-03-25); Developer options need not stay on; updates of unregistered apps fail when the advanced flow is disabled (2026-03-25); enterprise managed devices exempt; sanctioned countries: checks do not run; "Android 7 or higher … delivered through Google Play services"; $25 full-distribution fee, waived for limited; lost key: "you won't be able to register your packages"; multiple keys per package allowed; limited → full conversion only; developers who stay unverified: "distribute your unregistered app to users who have activated advanced flow" | https://developer.android.com/developer-verification/guides/faq |
| V4 | Help Center: "certified Android devices running Android 8 and up"; verifier package `com.google.android.verifier`; "AOSP and non-certified devices are exempt … does not apply in regions where Google Mobile Services are unsupported or in territories subject to applicable trade sanctions"; covers installs and updates | https://support.google.com/android/answer/17065026?hl=en |
| V5 | Advanced flow steps: Settings › System › Developer options › "Allow apps from unverified developers"; "one-time process will take 24 hours"; "seven days or indefinitely"; "You will still get a warning … tap Install anyway" | https://support.google.com/android/answer/17588095?hl=en |
| V6 | Blog 2026-03-19 (M. Forsythe): enable developer mode → coercion check → restart and reauthenticate → "one-time, one-day wait" + biometrics or PIN → "7 days or indefinitely"; warning with "Install Anyway"; limited distribution: 20 devices, no ID, no fee | https://android-developers.googleblog.com/2026/03/android-developer-verification.html |
| V7 | Blog (Mar 2026; note added 2026-09-29: "developer verification protections will begin rolling out to participating stores in select regions"); Verifier service from April 2026; "Unregistered apps can be sideloaded with ADB or advanced flow" | https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html |
| V8 | Blog 2026-06-18 (updated 2026-07-15): seven stores; Developer ID Status API and Developer Console API with OAuth delegation; global "2027 and beyond" | http://android-developers.googleblog.com/2026/06/android-developer-verification.html |
| V9 | Limited distribution: up to 20 devices "that end-users have explicitly authorized" via QR or link handshake; Google Account + 2-Step Verification + Google payments profile (legal name and address); no government ID; free (updated 2026-09-30) | https://developer.android.com/developer-verification/guides/limited-distribution |
| V10 | Duplicate package name rules (> 50 % installs priority; ≥ 50 installs "sizeable cluster"; else first come, first served); registration flow with signed-APK ownership proof (updated 2026-09-30) | https://developer.android.com/developer-verification/guides/android-developer-console |
| V11 | Open-source registration guide: `adi-registration.properties` asset snippet for ownership proof; users of unregistered apps use "Advanced flow setup" | https://developer.android.com/developer-verification/guides/open-source-app-registration |
| V12 | LineageOS (2026-07-04): verifier `com.google.android.verifier` wired through `config_developerVerificationServiceProviderPackageName` / `config_developerVerificationPolicyDelegatePackageName`; LineageOS ships neither, so it is unaffected; the advanced flow is "a one-time toggle" | https://lineageos.org/Developer-Verification/ |
| V13 | AOSP `android16-qpr2-release`: `PackageInstallerService` adds `INSTALL_FROM_ADB` when `isRootOrShell(callingUid)`; `PackageInstallerSession.shouldUseVerificationService()` returns false without a verifier package or for `INSTALL_FROM_ADB` unless `forceVerification`; `shouldSendUserActionForVerification`: the system installer always prompts, an installer targeting > 36 prompts only for non-blocking failures, ≤ 36 prompts unless it holds `INSTALL_PACKAGES`; `pm set-developer-verification-result` / `clear-developer-verification-result` exist | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java · …/PackageInstallerService.java (main) · …/PackageManagerShellCommand.java |
| V14 | `PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` and `DEVELOPER_VERIFICATION_FAILED_REASON_*` "Added in version 36.1"; target-SDK-dependent status codes as summarised above; `getDeveloperVerificationServiceProvider()` | https://developer.android.com/reference/android/content/pm/PackageInstaller |
| V15 | `setRequireUserAction` (API 31): conditions include target ≥ 35 on API 37 ("Android C"), installer = update owner, installer of record or "Updating itself", and declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (normal, API 31) | https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams · https://developer.android.com/reference/android/Manifest.permission |
| V16 | `InstallConstraints` (API 34): `setAppNotInteractingRequired` covers "playing or recording audio/video", "sending or receiving network data"; `GENTLE_UPDATE`; `commitSessionAfterInstallConstraintsAreMet` | https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints.Builder · https://developer.android.com/reference/android/content/pm/PackageInstaller |
| V17 | `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (API 26, `package:` URI); `canRequestPackageInstalls()` requires `REQUEST_INSTALL_PACKAGES` | https://developer.android.com/reference/android/provider/Settings · https://developer.android.com/reference/android/content/pm/PackageManager |
| V18 | Android Developer Verifier app: stub since Android 16 QPR2, present on Android 17 stable Pixels and One UI 9 beta; delivered by Google System Updates (2026-07-20) | https://www.androidauthority.com/android-developer-verifier-app-rollout-3689106/ |
| V19 | Advanced flow rollout began, gradual (2026-08-18 Android Authority; 2026-08-19 XDA: "if you turn off Allow Apps From Unverified Developers after installing an unverified app, you won't be able to update it") | https://www.androidauthority.com/google-android-advanced-flow-sideloading-rollout-begins-3700073/ · https://www.xda-developers.com/googles-controversial-advanced-flow-sideloading-rolling-out-ahead-of-stricter-developer-verification/ |
| V20 | "The advanced flow will be available this August for all versions of Android (via Google Play services)" (2026-03-19) | https://9to5google.com/2026/03/19/android-advanced-flow-sideloading/ |
| V21 | Bypass overview incl. ADB, Shizuku ("Relies on on-device wireless debugging"), custom ROMs; no hands-on test (2026-10-01) | https://www.androidauthority.com/how-to-bypass-android-developer-verification-sideloading-rules-3718002/ |
| V22 | Shizuku: "the startup steps need to be performed again after each reboot"; Wireless debugging on Android 11+ | https://shizuku.rikka.app/guide/setup/ |
| V23 | F-Droid open letter (2026-02-24): "We unequivocally advise against signing up for this program, now or ever." | https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html |
| V24 | Keep Android Open FAQ: lockdown date shown as January 2027; the date for F-Droid users "is not yet published" | https://keepandroidopen.org/faq/ |
| V25 | Google's video quoted: "backwards compatibility leveraging Google Play Protect"; install hook asks the single on-device verifier with package name and signing-key hash; pre-auth tokens; network required in the worst case | https://gist.github.com/agnostic-apollo/b8d8daa24cbdd216687a6bef53d417a6 (secondary; quotes Google's video https://www.youtube.com/watch?v=A7DEhW-mjdc) |
| V26 | GitHub REST: unauthenticated 60 requests/hour "associated with the originating IP address" | https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api |
| V27 | Conditional requests exempt only "if a 304 response is returned and the request was made while correctly authorized with an Authorization header" | https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api |
| V28 | Tightened unauthenticated limits (REST, git over HTTPS, raw.githubusercontent.com), 2025-05-08 | https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/ |
| V29 | "Latest release is the most recent non-prerelease, non-draft release"; `make_latest` | https://docs.github.com/en/rest/releases/releases#get-the-latest-release |
| V30 | `/releases/latest/download/asset-name` link format | https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases |
| V31 | Redirect chain observed: `github.com/…/releases/latest/download/X` → `github.com/…/releases/download/v…/X` → `release-assets.githubusercontent.com/…?se=<+1h>…` | curl, 2026-10-05 (Obtainium asset) |
| V32 | `releases.atom` lists releases including pre-releases (Obtainium v1.6.17 2026-09-13, v1.6.16 pre-release) | https://github.com/ImranR98/Obtainium/releases.atom (WebFetch 2026-10-05) |
| V33 | Immutable releases GA 2025-10-28; assets and tag locked; draft → attach → publish; release attestations; tag names not reusable | https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/ · https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases |
| V34 | `actions/attest` v4 (tags up to v4.2.2), permissions `id-token`, `attestations`, `artifact-metadata: write`; `attest-build-provenance` v4 "is simply a wrapper on top of actions/attest"; free for public repos | https://github.com/actions/attest · https://github.com/actions/attest-build-provenance (git ls-remote 2026-10-05) |
| V35 | Artifact attestations give SLSA v1.0 Build Level 2; reusable workflows for Level 3; "not a security guarantee" | https://docs.github.com/en/actions/concepts/security/artifact-attestations |
| V36 | `gh release verify [<tag>]`, `gh release verify-asset [<tag>] <file>`, `gh attestation verify` (gh 2.89.0, 2026-03-26) | local `gh --help` |
| V37 | `apksigner rotate` / `--lineage` / `--rotation-min-sdk-version` (v3.1 for API 33+ by default; 28 to cover Android 9+) (page updated 2026-03-05) | https://developer.android.com/tools/apksigner |
| V38 | v3: API < 28 verify v2 then v1; "APK key rotation is not recommended for Android 12 (API level 31) and earlier" | https://source.android.com/docs/security/features/apksigning/v3 |
| V39 | Obtainium: latest release v1.6.17 (main `pubspec.yaml` 1.6.18+2357); `shizuku_apk_installer` dependency; GitHub source uses `releases?per_page=100`; asset filter `isApkOrContainerFile`; settings `apkFilterRegEx`, `includePrereleases`, `verifyLatestTag`, `autoApkFilterByArch`, `github-creds` | https://github.com/ImranR98/Obtainium (`pubspec.yaml`, `lib/app_sources/github.dart`, `lib/providers/source_provider.dart`) |
| V40 | Obtainium deep links: `obtainium://add/<url>`, `obtainium://app/<json>`, badge redirect `https://apps.obtainium.imranr.dev/redirect?r=…` | https://wiki.obtainium.imranr.dev/deep_links/ |
| V41 | Play Protect enhanced fraud protection blocks internet-sideloaded apps requesting RECEIVE_SMS, READ_SMS, notification listener or accessibility (SG, TH, BR, IN pilots). Neutrodyne requests none of these | https://security.googleblog.com/2024/02/ (Feb 2024) |
| V42 | Android Auto shows sideloaded apps only with its developer "Unknown sources" toggle | https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/ |
| V43 | GitHub precedent: youtube-dl DMCA takedown reversed 2020-11-16 | https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/ |
| V44 | Android 15 refuses installs of apps targeting below API 24 (Android 14: below 23), whatever the source | https://9to5google.com/2024/03/25/android-15-block-older-apps/ (secondary, 2024-03-25) |

---

## Pitfalls & risks

1. **Unknown 2027 date.** Google says only "2027". A notice shipped too late reaches only users who already update; one shipped too early is ignored.
   - Mitigation: ship the guidance pages now.
   - Ship the one-time in-app notice in the first release of December 2026, or immediately when Google names a date, whichever is earlier.
   - Watch https://developer.android.com/developer-verification (a calendar issue every 2 weeks).
2. **TargetSdk 37 self-updater vs. the verification gate (Unverified).** Under AOSP rules an installer targeting > 36 gets no bypass prompt for a *blocking* "not verified" result.
   - Whether the advanced flow turns that into "no policy" (install proceeds) or a warning only the system installer can show is not documented.
   - Design for both: hand-off fallback, clear help sheet.
   - Test with `pm set-developer-verification-result` on a Pixel running Android 17 when the updater lands; retest on a real enforcing device in 2027.
3. **Silent self-updates may hide the "unverified" warning, or may be forced to show it (Unverified).** Never promise users silent updates in the README.
4. **7-day trap.** Users who pick "7 days" silently lose updates afterwards. Guidance says "indefinitely". The updater detects `DEVELOPER_BLOCKED` and explains.
5. **Google can change the advanced flow without an OS update** (it lives in Google-updated components). Shizuku and ADB exemptions depend on AOSP behaviour Google controls (`forceVerification` exists).
6. **Package-name squatting** before we have installs (first come, first served below 50 installs, V10). Consequences for unregistered installs are **Unverified**.
   - Mitigation: none without registering.
   - Keep the key (later registration requires it); public releases quickly build the "majority cluster".
7. **Key loss = permanent fork.** No Play escrow; users must uninstall; registration becomes impossible. Two holders, offline copies, yearly drill (09).
8. **Key compromise on old devices.** After a v3.1 rotation, API 26–32 devices still accept the old key (API < 28 only verify v2; rotation is not recommended ≤ 31). Document the residual exposure.
9. **Immutable releases cannot be fixed.** A wrong `update.json` or APK means a new version.
   - CI must validate `update.json` (schema, SHA-256, certificate, versionCode) before publishing the draft.
   - Never publish a release without `update.json`, otherwise `latest/download` 404s and the stable updater goes blind.
10. **GitHub rate limits and CGNAT.** Use `latest/download` and Atom, not the REST API. Add jitter; back off on 403/429.
11. **GitHub as the single point of failure.** A DMCA notice, abuse report or account suspension removes the only channel and breaks in-app update checks.
    - The youtube-dl precedent was reversed, but only after weeks.
    - Mitigation options (PO decision): a secondary mirror of releases; `update.json` on the PO's own domain, `lkmc.ch`; or accept the risk.
12. **Two updaters fighting** (in-app plus Obtainium): duplicate prompts and installer-of-record churn. Detect and default to notify-only.
13. **Process death during install.** `STATUS_SUCCESS` kills the app. Never install during playback or downloads (gentle constraints on 34+, app-side checks below 34).
14. **Background activity start restrictions.** `STATUS_PENDING_USER_ACTION` from a worker must go through a notification, not a direct `startActivity`.
15. **Android Auto** needs its "Unknown sources" developer toggle for any non-Play install. This is permanent friction for car users; document it.
16. **Play Protect prompts** for unknown apps can still appear on certified devices (**Unverified** frequency and wording). Nothing can be done without registration.
17. **Advanced flow per profile (Unverified).** Work-profile or secondary-user installs may need their own setup.
18. **Banking apps vs. Developer options.** The setup requires Developer options; users can switch them off afterwards (V3). The Shizuku route cannot.

---

## Open questions for the product owner

1. **Update checks: default on or opt-in?** Default on (daily, `github.com` and `release-assets.githubusercontent.com` only) gets YouTube hotfixes to users. Opt-in matches "only hosts the user chose" more strictly. Recommendation: ask on first run, preselect "Notify me".
2. **In-app updater scope:** full installer (recommended) or notifier only (no install permission)?
3. **Pre-enforcement notice timing and tone:** OK to show one dismissible in-app notice, from December 2026 or when Google names the date, explaining the advanced flow and recommending "indefinitely"?
4. **Remove the `play` flavor entirely?** It has no channel now. Removing it simplifies CI, tests and wording rules. Keep an internal "no-extraction" build profile for emergencies.
5. **Mirror for resilience?** Accept GitHub as the single point of failure, or also publish `update.json` and APKs on `lkmc.ch` (it has the same signing key, so it is safe to install over)?
6. **Signing-key holders (PO-8):** who is the second holder of the offline backup? This is now the only recovery path.
7. **Beta testers:** keep GitHub pre-releases plus Obtainium "include prereleases" plus the in-app beta channel? Who are the testers in BR/ID/SG/TH, so that a real enforcing device is available in 2027?
8. **README tone about Google:** state neutrally why Neutrodyne is unregistered (PO's choice: no identity tied to a YouTube-extracting app), or link F-Droid / Keep Android Open?
9. **Reproducible builds:** keep the nightly report-only diff as a trust signal, or drop it entirely now that no store rebuilds the app?
