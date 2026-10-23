# Piko assistant upgrade

## Implementation and compatibility

This upgrade preserves `com.piko.agent`, OpenCode Go, existing provider configuration, Keystore credentials, accessibility automation, task controls, and task history. No additional runtime dependencies were added. Room schema 2 adds memories, workflows and schedules through the tested `MIGRATION_1_2`; destructive migration was removed.

| Capability | Implemented behavior | Platform/device dependency |
|---|---|---|
| Voice | Visible push-to-talk, editable partial/final transcripts, language, cancellation, 45-second session bound; optional TTS, rate/voice controls, audio focus, mute/stop | Installed recognition/TTS engines, microphone permission; spoken recognition and audio quality need manual device testing |
| Wake word | Supported fallback activation; **no always-on detector is shipped** | A trained model and a suitable licensed engine are still required; no hidden microphone or continuous SpeechRecognizer workaround |
| Default assistant | VoiceInteractionService, session service, real recognition-service delegation, assistant-role request, settings link | User must select Piko; gesture behavior varies by OEM. Selection and voice sessions remain manually unverified |
| Screen questions | Per-question provider consent, fresh observations, redaction, vision capability/settings checks, hard read-only execution policy | Accessibility required; protected apps/screens remain blocked; screenshots are in memory only |
| Persistent memory | Local approved facts/preferences, normalized keys, replacement, categories, provenance, timestamps/expiry, bounded relevant retrieval, management UI, explicit remember/forget | Relevant approved memories are included in requests to the selected provider; no synchronization |
| Workflows/schedules | Ordered AI, native, semantic-tap, launch, back/home, wait and conditional steps, per-step confirmation, limited safe retries, stop/continue policy, variables, review/save/replay, reminders | Workflows currently require model configuration and accessibility even when all their steps are native; schedules notify for user-started execution |
| Floating assistant | Optional idle bubble, bottom-anchored draggable panel, stage headline without a step counter, approval review, editable message/Send, microphone, single Stop, collapse/dismiss, persisted position | Existing accessibility overlay; system overlay permission when a non-accessibility context is used. Gesture/keyboard/rotation behavior needs manual device testing |
| Hybrid execution | Closed tool catalog, argument schemas/validation, capability resolution, native state verification, supported intents and existing accessibility fallback | Flashlight needs camera permission; media control needs notification-listener access and a compatible active session; settings launches do not directly toggle protected settings |

## Setup and use

1. Build/install `dist/Piko-debug.apk` as an update; do not uninstall or clear app data.
2. Open **Assistant → Voice & access**. Grant only the capabilities you need. Existing detailed screen-capture/agent-limit/provider controls remain in Settings.
3. For AI automation, select a model and save your OpenCode Go key in the existing Models/Settings screens, and enable Piko accessibility.
4. Enable **Floating assistant while idle** to keep the panel available over other apps. Text and voice both use AgentService's single controller. While typing or recording an update, the current task waits. The floating panel keeps one Stop control; Pause and Resume remain in the notification and app.
5. Use **Talk or type**, the Home microphone, the floating Talk button, or add **Talk to Piko** to Quick Settings. Select a BCP-47 language, such as `en-US`, if needed. Review the transcript before sending. On-device recognition is preferred; a system recognizer can process audio online. TTS is off initially; enable spoken answers, select an installed voice and rate, or stop speech immediately.
6. Choose **Select default assistant** and complete Android's chooser yourself. Use your phone's assistant gesture/button. Piko never changes this setting silently and does not request screen content through the assist-session callback.
7. Open the target app, then the floating panel's **Ask screen** button. Approve the named provider for that question and send it. Questions cannot execute actions. Finish/stop a running task before starting a separate screen question. To request a fix, submit a new normal instruction; recent conversation supplies context and the screen is observed again.
8. Use **Assistant → Memory** to save, search, edit, approve or delete preferences. Use a stable key (for example `music app`) to replace a conflicting preference. Optional expiration is specified in days. Clear all requires a dialog. Up to five relevant approved memories are included with model requests. Password/token/card-like content is rejected; heuristic detection is not perfect.
9. Direct commands also work: `Remember music app = I prefer Spotify`, `Remember that I prefer concise responses`, `Forget music app`. These explicit commands run locally without a model key. Model suggestions are off initially; when enabled, they require a visible confirmation showing key/content before anything is stored. The Memory UI also supports reviewing unapproved records.
10. **Assistant → Workflows** accepts one AI instruction per line, or structured JSON. Give it a name, review all content, and save. Use **Review reusable steps from last successful task** for an editable draft. Reusable native actions and unique semantic taps are retained; fragile/incomplete traces fall back to an AI instruction, never a blind coordinate replay. Sensitive/unsupported actions are not automatically converted to deterministic steps.
11. Run a workflow manually, or add an inexact one-time/daily/weekly reminder. Review and unlock the device before running. **History** contains a task entry for each executed workflow step. A failed or unverifiable step stops by default. Pause/Resume/Stop operate through the same current controller. A denied approval stops deterministic steps without replaying them.

## Native commands and tool protocol

Exact commands such as `Turn on my flashlight, set volume to 30%, and open YouTube` use a conservative deterministic parser. The whole request returns to AI planning when any clause is unsupported (for example `Open Spotify and play my liked songs`). Deterministic native commands need no API key or accessibility permission; app/intent verification is limited without accessibility. The configured step/time limits and device-lock checks still apply. Accepted operations with no reliable readback are reported **unverified**, and no automatic UI replay occurs.

Available tools: Wi-Fi/Bluetooth/display settings, application details, HTTP(S) websites, media volume, flashlight, dialer, SMS/email drafts, sharing text, clipboard, navigation, calendar-event drafts, alarms with visible UI, YouTube/Spotify search, and compatible active media play/pause. Message/email/share/clipboard/calendar/alarm operations require explicit review. Native tools cannot send a message, purchase, grant permissions or execute arbitrary shell commands.

The existing action protocol now also accepts:

```json
{"version":1,"type":"native_tool","toolId":"media_volume","parameters":{"percent":30}}
```

Models receive the catalog through the existing OpenCode/provider prompt and tool schema, with the same JSON fallback, retries, cancellation and usage reporting. Native-only plans have zero model token usage. Volume is read back; torch state waits for Android's callback; playback state is polled. Intent dispatch is explicitly distinguished from task completion. Trace logs omit native argument payloads that may contain private data.

## Structured workflow examples

```json
[
  {"kind":"tool","toolId":"media_volume","parameters":{"percent":"{{volume}}"},"maxAttempts":2},
  {"kind":"launch","foregroundPackage":"com.google.android.youtube"},
  {"kind":"wait","milliseconds":500},
  {"kind":"ai","instruction":"Search for {{topic}}"}
]
```

Variables are collected for each run, substituted as data, and validated again. Native numeric/boolean fields retain their required types. The editor's simple mode can also use variables in AI instructions.

A semantic tap uses `{"kind":"tap","resourceId":"com.example:id/play","targetText":"Play"}`. It must resolve uniquely on the current screen. Conditions use `kind: condition`, `foregroundPackage`, and `skipIfFalse` to skip forward over a bounded number of steps. `confirmation: true` requests approval. `failurePolicy` is `stop` (default) or `continue`. `maxAttempts` is 1–3 and may exceed one only for waits, media volume, flashlight and media play/pause. There are no coordinate-script, shell or unrestricted intent steps.

## Scheduling behavior

Alarms are inexact and can be delayed by Doze/OEM policies. Repeating schedules use the device's current timezone, including DST adjustment. A one-time reminder uses the next occurrence of the selected clock time. Enabled schedules re-arm after reboot, timezone/clock changes and app updates. Missed repeating occurrences are recorded and advanced; missed one-time reminders are queued after recovery. Notification denial is recorded instead of launching UI in the background. Disable/cancel removes the alarm; cancellation also clears its reminder notification. Android force-stop prevents reminders until the user reopens the app or Android permits delivery; do not promise guaranteed execution.

Scheduled records report reminder delivery/missed delivery, not workflow completion. Actual step outcomes are in task history. Schedules do not restore a partially executed workflow after process death. Workflow execution is deliberately user-started.

## Permissions

- **Accessibility**: observations and UI actions; assistant selection does not grant it.
- **Microphone**: requested only for visible voice capture; no background audio service runs.
- **Camera**: optional flashlight control; the app does not capture camera images.
- **Notifications**: task controls, approvals and scheduled reminders.
- **Notification-listener access**: optional active media sessions; explicitly selected in Android settings. The listener does not persist notification content.
- **Overlay**: optional non-accessibility overlays; the accessibility overlay uses the enabled accessibility service.
- **MediaProjection**: optional session-scoped fallback; accessibility screenshots work where supported.
- **Default assistant**: voluntary assistant-role selection.
- **Calendar/location/write system settings**: no new direct permission is required; event creation/navigation/protected settings use the destination app's UI.
- **Boot completion**: restores reminder alarms, never starts UI automation or microphone capture.
- **Shizuku**: not implemented; never assumed available.

Permission/authentication surfaces, blocked apps and lock-screen transitions suppress the floating overlay. Screenshot suppression preserves this security suppression. Screen/app content remains untrusted, and screenshots are never persisted.

## Wake-word investigation and remaining work

[Porcupine's Android SDK](https://picovoice.ai/quick-start/wake-word-android/) supports local detection but requires an AccessKey and a platform-specific custom `.ppn` model. SDK/source licensing does not remove model/account terms; those need review for the intended distribution. [openWakeWord](https://github.com/dscripka/openWakeWord) has Apache-2.0 code, while its included pretrained models use CC BY-NC-SA 4.0. Neither supplies a reviewed, device-tested custom Hey Piko model in this repository. No untrained or dummy detector is included.

Always-on wake-word detection, confidence calibration, false-positive/battery measurement and microphone-interruption recovery remain unimplemented. A future detector must start visibly with the appropriate microphone foreground-service type/permissions and obey [Android background microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Push-to-talk, the tile and [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) are the implemented activation alternatives. No claim of reliable unrestricted hotword access is made.

## Verification and remaining device checks

See [TESTING.md](TESTING.md) for actual results. This upgrade has automated coverage for native arguments/resolution, read-only policy, voice callback cancellation, TTS mute/stop/failure, Room migration and memory retrieval/deletion, workflow serialization/variables/ordering/branches/retries/cancellation, scheduling/DST/cancellation, and permission-dialog overlay suppression. Connected-device smoke tests cover the Assistant hub, existing Home behavior and screenshot flags.

Still require manual testing: real speech/partial results, TTS voices/audio focus on calls, assistant-role selection and gesture/session dismissal, actual model screen Q&A/vision, flashlight and media hardware, floating keyboard/drag/rotation, permission revocation, reboot/Doze/timezone reminders and multi-app workflow recovery. Workflow creation, replay and scheduling are managed from the Assistant UI; arbitrary natural-language workflow/schedule commands are not parsed. Optional third-party APIs/OAuth integrations and Shizuku are not included. Safety redaction/classification is heuristic, and AI task-level completion remains model-reported evidence.
