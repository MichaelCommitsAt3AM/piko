# Piko testing

## Automated tests executed in this environment

Command:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Result at the time of writing:

- **106 tests, 0 failures, 17 suites** (Robolectric + JVM).
- **Lint: passing** (warnings only, mostly newer-version suggestions, Compose
  modifier ordering and localization notes).

### Coverage by suite

| Suite | What it covers |
|---|---|
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
| `LlmClientTest` | MockWebServer: auth header, tool-call parsing, 429 retry, 401 non-retry, tool-rejection JSON fallback, connection test inference+discovery, in-flight cancellation, SSE streaming tool-fragment assembly |
| `EndpointResolverTest` | OpenCode Go documented endpoint layout, OpenAI/Anthropic URLs, insecure HTTP gate, config normalization |
| `ScreenshotProcessorTest` | Robolectric: resize, aspect ratio, JPEG encoding, quality clamp |
| `TaskPersistenceTest` | Robolectric + Room: task/step relation, recent ordering, repository trace, orphan interruption, cascade delete |

A Compose instrumentation smoke test (`HomeScreenInstrumentedTest`) is included and
compiles (`:app:assembleDebugAndroidTest`), but it was **not executed** because no
device or emulator was available.

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
No physical-device test is claimed.

---

## Manual end-to-end checklist (not executed here)

Perform on a physical Android 11+ device with a spare account. Start with a
non-sensitive test account and watch the overlay/notification.

1. **Open Settings** — “Open Settings.” Verify `com.android.settings` is the
   observed app and Settings opens.
2. **Display settings** — “Open Settings, go to Display.” Verify the agent
   navigates and the trace shows the taps/verification.
3. **Enable dark mode** — “Open Settings and turn on dark mode.” Verify the
   final observation shows the Dark theme selected; finish should be Verified.
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
