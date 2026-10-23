# Piko testing

## Assistant upgrade verification — 21 October 2026

- **165 JVM/Robolectric tests, 0 failures, 28 suites.**
- Latest device run: **5 passed, 2 skipped, 0 failed**, Samsung Galaxy A35 (SM-A356E), Android 16. Passed: three Home tests, Assistant hub navigation, Settings screenshot flags. Skipped: overlay lifecycle and approval notifications, because their platform permissions were unavailable. No permissions were silently granted for tests.
- New tests cover closed native-tool validation/resolution, deterministic multi-step parsing and actual offline volume execution without an API key/accessibility, read-only screen policy, voice session cancellation/stale callbacks, TTS mute/stop/shutdown/initialization failure, Room 1→2 migration preserving an existing task, memory persistence/retrieval/expiry/deletion, workflow JSON/variables/branching/order/failure/retries/cancellation, device-zone/DST schedules and alarm cancellation, and permission-screen overlay suppression.
- **Final build and lint: passed** (warnings remain).
- Commands: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, `:app:connectedDebugAndroidTest`. See the latest build output for lint; warnings include SDK/dependency suggestions, localization and KTX suggestions.
- Manual testing still needed for real speech and TTS audio, selecting/invoking the system assistant, model screen Q&A, flashlight/media hardware, floating keyboard/drag/rotation, reboot/Doze/timezone reminders, permission revocation and multi-app workflow recovery. Wake-word detection is not implemented.

### Manual assistant scenarios

1. Deny microphone; confirm typing still works. Start voice, cancel/rotate/background it, and ensure no late transcript or microphone remains. Test unsupported language and missing recognizer.
2. Enable spoken responses, vary rate/voice, interrupt with a call or new recording, mute/stop and check immediate release.
3. Select Piko using the visible assistant chooser; invoke/dismiss the OEM gesture. Decline selection and confirm tile/in-app activation still works.
4. Open a public article → floating Ask screen → review provider consent. Deny consent and confirm Send is disabled. Send a question, then try a prompt-injected page; verify no device actions. Repeat with screenshots disabled, FLAG_SECURE, rotation and an inaccessible tree.
5. Save/edit/expire/forget a keyed preference, restart, inspect approved-only retrieval, and clear all. Decline a model-proposed memory and ensure it is not persisted.
6. Review/save a successful task, parameterize its variables, run with a missing/ambiguous semantic target, deny a confirmation and cancel mid-step. Verify no coordinate replay or non-idempotent automatic retry.
7. Schedule once/daily/weekly, deny notifications, reboot/change timezone/enter Doze, disable/cancel, and verify reminders never start UI automation automatically. Reopen after force-stop to restore alarms.
8. Enable the idle bubble, drag/snap/minimize/dismiss, type/send, record, rotate and show a permission dialog. Verify the overlay hides on protected surfaces and Stop remains available in app/notification.
9. Run exact flashlight/volume/app-opening commands without an API key; deny camera and check an honest failure. For intent tools, check drafts rather than actual sends; verify pending action details before approval. Test media play/pause with notification access denied and multiple active sessions.

## Existing baseline tests and earlier device records

The sections below retain the original coverage and historical manual notes; they are not evidence that new hardware/voice/assistant features were verified.

## Automated tests executed in this environment

Command:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Result at the time of writing:

- **165 tests, 0 failures, 28 suites** (Robolectric + JVM).
- **Lint: passing** (warnings only, mostly newer-version suggestions, Compose
  modifier ordering and localization notes).

### Coverage by suite

| Suite | What it covers |
|---|---|
| `OverlaySuppressorTest` | Restoration after cancellation during the capture settle delay, failed captures, and serializing overlapping captures |
| `InstructionInboxTest` | Ordered/bounded direct user updates, cancelling pending inference, stale-revision rejection |
| `ActionContextGuardTest` | Same-app content changes, focus, dialogs, rotation, resizing and stale IDs |
| `NodeFinderTest` | Reused IDs, changed labels, moved/disabled/hidden targets, package identity and label normalization |
| `ScreenshotPrivacyTest` | Withholding screenshots for credential fields, OTP text and card descriptions |
| `ActionValidatorTest` | Action schema, element IDs, coordinate bounds, gestures, text limits, nested confirmations, finish/fail requirements |
| `ActionParserTest` | Native tool args, wrapped JSON, Markdown fences, prose, direct actions, nested function arguments, brace handling, invalid output |
| `AgentStateMachineTest` | Allowed/illegal transitions, pause/resume, terminal states |
| `CoordinateMapperTest` | Image↔screen mapping, clamping, rotation size swap, aspect-ratio resize |
| `LoopDetectorTest` | Duplicate actions, same-target taps, unchanged-screen progress limit, signature stability |
| `RetryPolicyTest` | Exponential backoff, Retry-After cap, retryable status codes |
| `SensitiveActionClassifierTest` | Send/payment/delete/install/share/permission/auth/security classification, password/card blocking, resource-ID detection, coordinate-target context |
| `SensitiveTextRedactorTest` | Password/OTP/card redaction and trace redaction |
| `UiFingerprintTest` | Stable ordering, text/checked/package changes |
| `UsageTest` | Per-million cost, cached discount, accumulation |
| `AgentPromptBuilderTest` | Safety prompt, observation formatting, history, warnings, screenshot note, untrusted-content marker |
| `LlmProtocolTest` | OpenAI Chat, Responses, Anthropic response parsing; request bodies; model lists; error mapping; key sanitization; endpoint URLs; protocol routing; provider normalization; tool schema |
| `LlmClientTest` | MockWebServer: auth header, tool-call parsing, 429 retry, 401 non-retry, tool-rejection JSON fallback, connection test inference+discovery, in-flight cancellation, SSE streaming tool-fragment assembly, redirect rejection for streaming and ordinary requests |
| `EndpointResolverTest` | OpenCode Go documented endpoint layout, OpenAI/Anthropic URLs, insecure HTTP gate, config normalization |
| `ScreenshotProcessorTest` | Robolectric: resize, aspect ratio, JPEG encoding, quality clamp |
| `TaskPersistenceTest` | Robolectric + Room: task/step relation, recent ordering, repository trace, orphan interruption, cascade delete |

Device checks on the connected Samsung Galaxy A35 (2026-10-21):

- `HomeScreenInstrumentedTest`: passed.
- `ApprovalNotificationInstrumentedTest`: passed; verifies high-priority approval notification, Approve/Deny/Review actions and cancellation when resolved.
- `OverlayInstrumentedTest`: passed; show/update/hide/re-show before any minimization no longer crashes.
- `ScreenshotPolicyInstrumentedTest`: passed; Home permits screenshots, Settings blocks them, returning Home permits them again.
- Normal app flow: “Open Calculator.” completed in four steps; Calculator was visibly foreground and the live result reported completion.
- Spoken recognition, voice steering, and other manual scenarios remain unverified. Voice testing was deferred at the user's request.

Run instrumentation independently of live agent tasks: instrumentation can
suspend or disconnect accessibility services. Reconnect the already-enabled Piko
service afterward before testing the normal app flow.

### What is not automated

These require a real Android device or an emulator with accessibility:

- Actual accessibility event delivery and node traversal against third-party apps.
- App launching and foreground verification.
- Gesture execution and UI changes.
- MediaProjection consent and frame capture.
- Overlay WindowManager rendering.
- Keystore encryption/decryption on a device.
- Notification controls.
- Real provider calls with a user's OpenCode Go key.

The provider wire protocol, routing, parsing, retries and streaming are covered
with MockWebServer. Room and screenshot processing are covered with Robolectric.
Only the device checks explicitly listed above are claimed.

---

## Manual end-to-end checklist (unverified except where listed above)

Perform on a physical Android 11+ device with a spare account. Start with a
non-sensitive test account and watch the overlay/notification.

1. **Open Settings** — “Open Settings.” Verify `com.android.settings` is the
   observed app and Settings opens.
2. **Display settings** — “Open Settings, go to Display.” Verify the agent
   navigates and the trace shows the taps/verification.
3. **Enable dark mode** — “Open Settings and turn on dark mode.” Verify the
   final observation shows the Dark theme selected; finish should be Model-verified.
4. **Chrome search** — “Open Chrome and search for ‘Android accessibility’.”
   Verify text entry, search submission, and result observation.
5. **Calculator** — “Open Calculator and calculate 48 × 7.” Verify 336 is
   visible and the finish evidence mentions it.
6. **Back navigation** — Ask the agent to navigate back through multiple screens.
   Verify Back actions and no loop abort.
7. **Unexpected dialog** — Trigger a permission prompt or app dialog. Verify the
   agent pauses/asks for confirmation or dismisses a safe dialog; it must not
   auto-allow sensitive permissions.
8. **Cancel during action** — Start a long task and tap Stop while a network
   request or gesture is running. Verify the state becomes Cancelled promptly and
   no further actions occur.
9. **API timeout** — Configure a short timeout or use an unreachable provider.
   Verify exponential retries are bounded and the task fails with a clear message.
10. **Accessibility disconnection** — Disable Piko's accessibility service during
    a task. Verify it pauses and does not continue acting. Re-enable and confirm
    resume/stop behavior.
11. **Screen lock** — Lock the device during a task. Verify Piko pauses; it must
    not act on the lock screen.
12. **Overlay** — Verify expanded/minimized, dragging, pause/resume, stop and
    approval-needed display. Verify the overlay is not present in screenshots
    sent to the model (inspect via a debug build/vision observation).
13. **Sensitive confirmation** — Ask Piko to send a message or delete a note.
    Verify Approve/Deny is required and Deny stops the action.
14. **Password block** — Ask Piko to enter a password. Verify it refuses and does
    not send the value to the model.
15. **History** — Complete and fail tasks; verify history, detail trace, delete
    all, disable history and retention behavior.
16. **Process death** — Kill Piko during a task. Relaunch; verify the task is
    marked interrupted and is not resumed automatically.
17. **Screen capture fallback** — Revoke/deny MediaProjection and verify
    accessibility screenshots still work or gracefully fall back.
18. **Rotation** — Rotate during observation. Verify screen dimensions/rotation
    update and taps still land correctly.

19. **Screen changes during inference/approval** — Navigate within the same app,
    change focus, or open a dialog while the provider is answering or approval is
    pending. Verify the old action is withheld and a new observation is requested.
20. **Screenshot privacy** — Show a password/OTP field or OTP message and request
    a visual observation. Verify the provider receives no image. Repeat with a
    truncated tree. Custom-drawn secrets remain outside reliable detection.
21. **Minimal UI** — Check light/dark mode, large font sizes, keyboard scrolling,
    model selection, sequential setup, and Show details. Finish a task with history
    disabled and verify Last task still opens its result.

---

## Commands

```bash
# all local tests
./gradlew :app:testDebugUnitTest

# lint (fails on errors; warnings remain)
./gradlew :app:lintDebug

# debug APK
./gradlew :app:assembleDebug

# install
adb install -r dist/Piko-debug.apk
```

To run instrumentation tests on a device (if added later):

```bash
./gradlew :app:connectedDebugAndroidTest
```

There are currently no device-only instrumentation tests in the repository; the
manual checklist above is the source of truth for device verification.
