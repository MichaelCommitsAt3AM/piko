# Assistant upgrade assessment and plan

The existing app uses Kotlin 2.2.10, AGP 8.13.2, Compose Material 3, API 30 minimum and API 36 target. AgentService owns one AgentController; AgentRuntime provides shared state, cancellation, steering and confirmation. ObservationEngine redacts accessibility trees and ScreenshotEngine respects secure windows. ActionExecutor already prefers semantic nodes and supports native app launching. LlmClient preserves OpenCode Go and all existing protocol routing, retries, cancellation and usage. Room v1 stores task traces, DataStore stores settings, and Keystore protects credentials. Existing voice input is visible, session-based STT; overlays are task controls. No memory, reusable workflow store, scheduler, TTS or assistant-role services exist.

Existing uncommitted UI changes are retained. Extend the action schema with a validated native-tool action and route through ActionExecutor, retaining controller policy gates and trace storage. Add screen-question mode with explicit provider consent and fresh observations. Extend visible voice input and add TTS. Add supported VoiceInteractionService/session and Quick Settings activation. Add local structured memory and workflow persistence without replacing the task database. Expose these features through a dedicated Compose assistant hub. Schedules notify for explicit user-started execution; they never launch background screen automation. Add targeted unit/Robolectric tests and manual device scenarios, then assemble and lint.

Optional always-on hotword support needs a separately licensed/trained engine. Porcupine requires an AccessKey and platform-specific custom .ppn model; do not ship a fake detector or use continuous SpeechRecognizer as hotword detection. Push-to-talk and assistant gesture are supported fallbacks. Android background microphone and manufacturer restrictions still apply.

Sources consulted:
- https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://picovoice.ai/quick-start/wake-word-android/
