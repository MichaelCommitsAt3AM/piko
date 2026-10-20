# Piko

**An AI agent that operates your Android phone.**

Piko is a personal, sideloaded Android app that lets a configured AI model inspect the screen, plan steps, and perform real taps, swipes, scrolling and text entry through Android's accessibility APIs. It is not a mockup and does not simulate the phone inside its own UI.

- **Application ID:** `com.piko.agent`
- **Platform:** Android 11+ (API 30+), target/compile SDK 36
- **Distribution:** personal sideloaded debug APK
- **Primary provider:** OpenCode Go (BYO API key)
- **Also supported:** OpenAI, Anthropic, OpenAI-compatible and custom endpoints

> **Execution status in this repository:** the app compiles, the debug APK is built, and 106 JVM/Robolectric tests pass. Physical-device end-to-end tests were **not** executed in this environment (no Android device or emulator image was available). See [docs/TESTING.md](docs/TESTING.md) for the manual checklist and exactly what has and has not been verified.

---

## Features

### Agent

- Iterative **observe → reason → act → verify** loop with explicit states:
  `Idle`, `Preparing`, `Observing`, `Reasoning`, `Executing`, `Verifying`, `AwaitingUserConfirmation`, `Paused`, `Completed`, `Failed`, `Cancelled`.
- Strict, versioned JSON action schema: `tap`, `long_press`, `swipe`, `scroll`, `type_text`, `clear_text`, `back`, `home`, `open_notifications`, `launch_app`, `wait`, `observe`, `request_confirmation`, `finish`, `fail`.
- Native tool calling where the provider supports it, with structured-JSON fallback if tools are rejected.
- Bounded retries, model/action timeouts, step limit, task-duration limit, estimated spending limit, loop detection, stale-observation rejection, and UI fingerprinting.
- Verification after every action. Piko never treats "Android accepted the command" as success. `finish` only shows **Verified** when the model supplies evidence; otherwise it is reported as **Unverified**.
- One task controls the device at a time.

### Android control

- Properly configured `AccessibilityService` with `canTakeScreenshot`.
- Semantic accessibility actions first; gesture dispatch only when needed.
- Element IDs valid only for the current observation; stale nodes are rejected and re-observed instead of blindly tapping.
- Foreground app, window title, element text, descriptions, resource IDs, bounds, roles, state flags and parent relationships.
- Dialog heuristics and permission-prompt detection.
- Screenshot capture through accessibility first, MediaProjection fallback, resize/JPEG compression, rotation handling and secure-window detection.
- Screenshots are hidden from the floating overlay during capture.

### Providers

- **OpenCode Go** via the documented `https://opencode.ai/inference/go` endpoints:
  - OpenAI Chat Completions: `/openai/v1/chat/completions`
  - OpenAI Responses: `/openai/v1/responses`
  - Anthropic Messages: `/anthropic/v1/messages`
  - Model discovery: `/v1/models`
  - Bearer API-key auth, stable `x-opencode-session`, Piko user-agent.
- Automatic per-model protocol routing for documented OpenCode Go models.
- OpenAI Chat Completions, OpenAI Responses, Anthropic Messages.
- Dynamic model discovery, manual model IDs, per-model vision/tool capabilities, protocol override, prices, timeout and retries.
- Connection test validates inference (not just `/models`, which can be public).
- SSE streaming for OpenAI Chat Completions (optional), non-streaming for Responses/Anthropic for deterministic action parsing.
- Token usage and estimated cost tracking; auth, rate-limit, timeout, network and server errors handled.

### Safety and privacy

- Keystore-backed AES-256-GCM encryption for API keys.
- API keys and authorization headers are never logged.
- Password/OTP/payment fields are redacted from observations; credential entry is **blocked**, not merely confirmed.
- Password-manager/authenticator apps and terminal/remote-shell apps are blocked entirely, so the model cannot read credentials or type shell commands through a terminal UI.
- Sensitive actions require explicit confirmation: sending, payments, deletion, installs/uninstalls, external sharing, authentication/permission grants, and security settings.
- Heuristics also classify coordinate taps by the element under the coordinate, so coordinate actions cannot bypass confirmation.
- Screen content is explicitly marked **untrusted**. Instructions in web pages/messages/apps cannot override the user's goal or safety policy.
- HTTPS required by default; HTTP only when the user explicitly enables local development endpoints.
- Task history can be disabled, retained for a configurable period, or deleted entirely.
- Screenshots are never persisted to disk or task history.
- Piko's own UI uses `FLAG_SECURE`; backups are disabled and excluded by data-extraction rules.

### UI

- Compose Material 3 interface with Piko branding, dark/light mode and dynamic color support.
- Home: natural-language goal composer, capability tiles, active task, recent tasks, selected model.
- Live task: state, timeline, confirmation card, current app, live observation text, optional in-memory screenshot, token/cost metrics, pause/resume/stop.
- Models: provider list, key status, model selection, capability toggles, protocol override, connection test, model discovery.
- History: filters, outcomes, duration, steps, tokens, cost, detailed per-step trace.
- Settings: permissions, limits, confirmations, screenshots, streaming, history retention, insecure-endpoint gate, debug logs, destructive-data controls.

### Background operation and overlay

- Foreground agent service (`dataSync`) with persistent notification controls.
- Optional draggable floating overlay (expanded/minimized) showing status, goal, action, step, approval-needed state, pause/resume and stop.
- Overlay controls are direct in-process calls, so they stay responsive during network requests.
- If overlay permission is missing, notification controls and the app remain available.
- Pauses when the screen locks, accessibility disconnects, or Piko's own UI is in front.
- Does not auto-resume after process death; running tasks are marked interrupted.

> **Android handoff:** Android exposes only one foreground app to accessibility. When a task starts, Piko moves to the home screen after a brief moment. Opening Piko during a task pauses the agent; pressing Home/another app lets it resume automatically. The floating overlay is the intended live view while the agent is operating. This is a platform constraint, documented in [Known limitations](#known-limitations).

---

## Build

### Requirements

- JDK 17 or 21 (the Android Studio JBR 21 was used here)
- Android SDK Platform 36, Build Tools 36
- Gradle wrapper (included)

### Commands

```bash
# Windows (PowerShell)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
```

Output:

```
app/build/outputs/apk/debug/app-debug.apk
```

The APK is also copied for convenience to `dist/Piko-debug.apk`.

`local.properties` sets `sdk.dir` for this machine. Change it for your SDK location if needed.

### Install

```bash
adb install -r dist/Piko-debug.apk
```

The package is `com.piko.agent`.

---

## First-run setup

1. **Open Piko.**
2. **Accessibility:** Settings → Permissions → Accessibility control → enable **Piko device control**.
3. **Notifications:** allow notifications so the foreground service controls are visible.
4. **Floating overlay (optional but recommended):** Settings → Permissions → Floating overlay → allow.
5. **Screen capture (optional):** Settings → Permissions → Screen capture → grant one-session MediaProjection access. Without it, Piko uses `AccessibilityService.takeScreenshot` on Android 11+.
6. **Battery:** if long tasks are interrupted by the OEM, open Battery optimization and exempt Piko.
7. **Models → OpenCode Go**, paste the API key, **Refresh models**, select a model, **Test connection**, **Save**.
8. Return Home, enter an instruction, and tap **Start task**.

### OpenCode Go

1. Sign in at <https://opencode.ai/console>, subscribe to Go/Go Plus, and create an API key.
2. In Piko: **Models → OpenCode Go**.
3. Paste the key. It is encrypted with Android Keystore and never displayed again.
4. Press the refresh icon to fetch the live model list.
5. Choose a model. Piko routes documented Go models to their correct OpenAI/Responses/Anthropic endpoint automatically.
6. Press **Test connection**. This makes a minimal inference request; a public `/models` response alone is not accepted as proof of authentication.
7. Save.

Notes:

- Model availability and usage limits are controlled by OpenCode, not Piko.
- Prices shown are estimates used for the spending limit/UI, not billing data.
- Google Gemini routes from OpenCode Go use a Google-specific protocol that this build does not implement, so they are hidden from model discovery rather than shown as broken options.

### Other providers

- **OpenAI** — base URL `https://api.openai.com/v1`; GPT-5/6-class models route to the Responses API, older models to Chat Completions.
- **Anthropic** — base URL `https://api.anthropic.com/v1`, `x-api-key` auth, Messages API.
- **OpenAI-compatible** — any compatible `/chat/completions` server. Include `/v1` in the base URL if your server expects it.
- **Custom endpoint** — same connection model with a custom base URL/protocol.

For local HTTP endpoints, enable **Settings → Privacy & security → Allow insecure HTTP endpoints**. HTTPS is enforced otherwise.

---

## How a task runs

1. The user enters a goal and taps Start.
2. Piko starts a foreground service, validates accessibility/model/key, and hands the screen over to the agent.
3. Piko takes an accessibility observation (and a screenshot only when the model/vision settings need one).
4. The model returns one validated action.
5. The action executor prefers node actions; otherwise it dispatches a gesture.
6. Piko waits for the UI to settle, observes again, and records whether anything changed.
7. Confirmation gates stop the loop for sensitive actions.
8. Loop/retry/step/time/cost guards stop runaway tasks.
9. Only an explicit, evidence-backed `finish` action reports verified completion.
10. Pause, resume, stop and approve/deny work from the app, notification and overlay.

### Action schema example

```json
{"version":1,"type":"tap","elementId":"element_12"}
```

```json
{"version":1,"type":"swipe","startX":540,"startY":1700,"endX":540,"endY":500,"durationMs":400}
```

The complete schema and validation rules are in `core/ActionValidator.kt` and `ai/ActionToolSchema.kt`.

---

## Architecture

```
app/src/main/java/com/piko/agent/
  MainActivity.kt                 Compose entry point, FLAG_SECURE
  PikoApplication.kt              service locator, channels, orphan cleanup
  core/                           pure Kotlin: action schema, validation, state machine,
                                  loop detection, retries, redaction, fingerprints, cost
  model/                          observations, providers, settings
  data/                           Room, DataStore, Keystore credential store, task repo
  accessibility/                  service, observation engine, executor, screenshots
  ai/                             provider clients/protocols, prompt builder, streaming
  agent/                          controller, runtime StateFlow, timeline
  service/                        AgentService, ProjectionService
  overlay/                        draggable WindowManager overlay + capture suppression
  ui/                             Compose theme, screens, ViewModel
  test/                           106 unit/Robolectric tests
```

Key design choices:

- **Single foreground service owns the task.** Exactly one `AgentController` can run.
- **Room** stores tasks/steps; **DataStore** stores non-secret settings/providers; **Keystore + SharedPreferences** stores encrypted keys.
- **`AgentRuntime`** is the process-wide observable state consumed by Compose, the overlay and the notification.
- Observations are generated fresh for every step; element IDs never survive a new observation.
- Provider output is untrusted: parse → validate → safety-classify → execute.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Current results in this environment:

- **106 tests, 0 failures** across 17 suites.
- A Compose instrumentation smoke test is included and compiles, but was not run without a device.
- Covers action validation, parser, state machine, coordinate mapping, loop detection, retries, cancellation, sensitive-action classification, redaction, fingerprints, cost calculation, prompt construction, all three provider response dialects, endpoint routing, SSE streaming, MockWebServer request/auth/retry/tool-fallback/connection-test behavior, screenshot processing, and Room task persistence.
- `lintDebug` passes (warnings remain, mostly version suggestions and localization notes).

Physical-device scenarios (Settings dark mode, Chrome search, Calculator, dialogs, cancellation, timeouts, accessibility disconnection) are listed in [docs/TESTING.md](docs/TESTING.md) but **were not executed here**. They must not be reported as passed without a real device run.

---

## Security and privacy

See [docs/SECURITY.md](docs/SECURITY.md) for the full model.

Highlights:

- API keys encrypted with Android Keystore AES-GCM.
- Keys never logged; error bodies are sanitized.
- HTTPS by default; insecure HTTP requires an explicit setting.
- Password/OTP/card fields are redacted and credential entry is blocked.
- Piko never approves authentication or permission prompts automatically.
- Screen content is untrusted and cannot override the user goal.
- Screenshots are in-memory only, never written to disk.
- History can be disabled/deleted; backups disabled.
- No analytics, covert recording, hidden monitoring, root, or shell execution.

---

## Known limitations

- **One foreground app:** accessibility can only inspect the active app. Piko's own UI pauses the agent; the overlay/notification are the intended live controls while it operates.
- **Secure windows:** Android blocks screenshots of `FLAG_SECURE` content. Piko reports this and continues with accessibility text where possible; it does not bypass protections.
- **App-specific accessibility gaps:** WebViews, games, Flutter/custom-drawn UIs and some banking apps expose incomplete trees or reject node actions. Some text fields only accept IME input, not `ACTION_SET_TEXT`.
- **Background app launches:** Android may block `startActivity` from a background service. Piko verifies the foreground app and reports when the launch was blocked.
- **OpenCode Go Google/Gemini protocol** is not implemented; those models are filtered from discovery.
- **Streaming** is implemented for OpenAI Chat Completions only. Responses/Anthropic use non-streaming requests for deterministic action parsing.
- **Shizuku** is not included. The core accessibility agent works without it; see below.
- **Task-level token context** is bounded by trimming element count/history, not by a provider-specific tokenizer.
- **Cost data** is estimated from configured per-million prices; OpenCode Go peak/off-peak and plan pricing are not modeled exactly.
- **MediaProjection** consent is session-scoped and must be granted again after process death/revocation.
- **No physical-device tests were run in this environment.**

### Shizuku

Shizuku is intentionally not implemented in this build. The user requirements make it optional and instruct implementing it only after the accessibility agent works. The core app does not require it, root, or ADB. A future integration should sit behind a separate capability interface and must not be assumed to bypass Android permission/background restrictions.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Accessibility is not enabled" | Enable Piko in Android Settings → Accessibility. Reopen the app. |
| Task immediately pauses after opening Piko | This is intentional. Press Home or switch apps; Piko resumes automatically. |
| Screenshot unavailable | Grant MediaProjection in Settings, or use a screen that is not `FLAG_SECURE`. Accessibility auto-screenshots are usually enough. |
| "No API key is stored" | Models → select provider → paste key → Save. |
| Connection test fails with 401/403 | Check the key/workspace permissions. A `/models` response can be public; the test also performs inference. |
| Launch app blocked | Android blocked a background activity launch. Keep the app in recents or grant overlay permission; Piko reports the failure. |
| Provider 400 with tools | Piko automatically retries in structured-JSON mode. If it persists, disable native tool calling for that model. |
| Task stops at step/cost/time limit | Adjust Settings → Agent execution, or choose a cheaper/faster model. |
| App killed in background | Exempt Piko from battery optimization; tasks are not auto-resumed after process death. |

---

## Repository notes

- `dist/Piko-debug.apk` — debug APK.
- `app/schemas/` — exported Room schema for future migrations.
- `local.properties` — local SDK path (machine-specific).
- No analytics, ads or network calls other than the configured model provider.

Piko is a personal automation tool. Use it only on devices and accounts you control, and only with providers you trust with the screen content you expose to it.
