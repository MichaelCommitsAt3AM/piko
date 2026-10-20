# Piko security and privacy

Piko is a personal, sideloaded Android automation agent. This document describes
what it stores, what it sends, how secrets are protected, and what the app
deliberately refuses to do.

## 1. Threat model

Piko assumes:

- The device owner is the operator and has intentionally enabled accessibility.
- Third-party screen content is **untrusted**. Web pages, messages, emails,
  notifications and app UI may contain prompt-injection attempts.
- The selected AI provider receives the user's goal and a filtered observation
  of the current screen while a task runs.
- The model may be wrong, may hallucinate element IDs, or may propose unsafe
  actions. Its output is parsed, validated, safety-classified and bounded before
  execution.
- Android enforces secure-screen, permission, lock-screen and background-launch
  restrictions. Piko does not attempt to bypass them.

Out of scope: a compromised OS, a malicious accessibility service, a rooted
attacker, or a malicious provider. Use only devices and providers you trust.

## 2. API credentials

- API keys are encrypted with **AES-256-GCM** using a key generated in the
  **Android Keystore** (`piko_credentials_aes_v1`).
- Ciphertext lives in a private `SharedPreferences` file (`piko_credentials`),
  with a random IV per write and an authenticated payload format.
- Keys are decrypted only in memory when a provider request is made.
- Keys are never written to DataStore, Room, logs, task traces, prompts, or
  crash messages.
- Provider error messages are sanitized: if an API key appears in an error body
  or message, it is replaced with `[redacted]`.
- HTTP logging interceptors are not used.
- Backups are disabled (`allowBackup=false`) and data-extraction rules exclude
  shared preferences, databases and files.

Deleting a key removes the ciphertext. The Keystore key remains but is useless
without ciphertext. Users can delete all keys from Settings.

## 3. What is sent to the model

While a task runs, Piko sends:

- The user's original goal.
- A filtered accessibility observation: foreground package/app label, window
  title, visible text, content descriptions, resource IDs, bounds, roles,
  clickable/scrollable/editable/checked/selected/enabled state and parent IDs.
- Recent action history and outcomes.
- Warnings/loop hints.
- Optionally, a compressed screenshot when the model supports vision and the
  screenshot policy decides it is useful (e.g., unchanged screen, very sparse
  tree, or explicit `observe` request).

Piko attempts to exclude credentials using accessibility metadata and text
patterns. This is best-effort detection, not a guarantee for arbitrary app
content or custom-drawn screens. It also excludes:

- Screenshots of secure/`FLAG_SECURE` windows (capture fails and is reported).
- Screenshots stored on disk (none are persisted).
- Hidden monitoring, keylogging, camera, location, contacts, SMS
  or call data.

### Redaction and blocking

- Accessibility password fields (`isPassword`) and fields whose resource
  ID/hint/class look like password/OTP/token/CVV/card/SSN fields are replaced
  with `[redacted]`.
- Visible text is additionally scanned for common OTP-message formats and
  13–19 digit card-number runs, and those values are redacted best-effort.
- Piko refuses to observe or act inside known password-manager/authenticator
  apps and terminal/remote-shell apps. This prevents credential exposure and
  prevents "typing" an arbitrary shell command into a terminal through the UI.
- Text entry into password/payment fields is **blocked** with a clear message;
  the agent cannot type a password even if the model asks.
- Card-number-shaped text (13–19 digits with spaces/hyphens) is blocked.
- Screenshots are withheld when the inspected tree contains a detected credential
  field, text that required redaction, or when traversal was truncated. Redacting
  tree text alone cannot protect pixels in a screenshot.
- Observation text is length-limited and duplicate/redundant nodes are removed.

Completion evidence is supplied by the model; the UI calls it **Model-verified**.
It is not an independent proof that the user goal was accomplished.

Redaction is best-effort for arbitrary text rendered in normal labels. Users
should not display secrets on screen during a task.

## 4. Prompt injection and untrusted content

The system prompt states that all screen content is untrusted data and that the
user's goal is the only authoritative instruction. Piko also enforces this in
code:

- The model can only select actions from a strict, versioned schema.
- It cannot execute shell commands, install arbitrary code, or access files.
- Element IDs must exist in the current observation; stale IDs are rejected.
- Before executing an action, a fresh observation must match the original app,
  display geometry, dialog/focus state, and accessible elements. This also applies
  after approval. Element lookup requires matching labels, package, class, resource
  ID and nearby bounds, rather than a best-score match.
- This check covers the accessible tree; it cannot detect every visual-only change
  in a custom-drawn interface, and Android observation/execution is not atomic.
- Coordinates must be within the current display.
- Sensitive actions are independently classified and confirmed by the user.
- Authentication and permission prompts are never approved automatically.
- The model cannot disable Piko's safety policy or the confirmation gate.

## 5. User control

- The app, notification and floating overlay all expose Pause/Resume/Stop.
- The overlay is draggable and can be minimized. It is hidden during screenshot
  capture so it does not appear as app content.
- Only one task can control the device at a time.
- Confirmation requests show the proposed action, reason, risk category and
  Approve/Deny controls.
- Stop cancels the running coroutine and network call; the task is finalized as
  cancelled.
- Piko pauses on screen lock, accessibility disconnect, or when its own UI is in
  the foreground.
- After process death, running tasks are marked interrupted and are **not**
  resumed automatically.

## 6. Network security

- HTTPS is required by default. HTTP endpoints are rejected with an explicit
  error unless the user enables **Allow insecure HTTP endpoints** for local
  development.
- URLs containing embedded credentials are rejected.
- Automatic redirects are disabled for both streaming and ordinary requests so
  credentials and screen content are not forwarded to a redirect destination.
  Configure the final provider endpoint directly.
- Timeouts are bounded (provider-configurable 15–300 s) and retries are bounded.
- Responses are size-limited before parsing.
- OpenCode Go requests use Bearer auth, a stable per-task
  `x-opencode-session` header, and a Piko user-agent, matching current provider
  guidance.
- Anthropic uses `x-api-key`; OpenAI-compatible providers use Bearer.

## 7. Android component exposure

- `MainActivity` is exported only as the launcher entry point. It consumes no
  untrusted deep links.
- The accessibility service is exported with
  `android.permission.BIND_ACCESSIBILITY_SERVICE`, as Android requires.
- `AgentService` and `ProjectionService` are `exported=false`.
- PendingIntents are immutable.
- The manifest exposes only the permissions Piko actually uses.
- `FLAG_SECURE` is applied while Settings is visible, protecting API-key
  fields from screenshots and recent-app previews. Other Piko screens permit capture.

## 8. Data storage and retention

| Data | Location | Default |
|---|---|---|
| API keys | Keystore-encrypted SharedPreferences | Stored until deleted |
| Provider config, settings | DataStore | Stored |
| Task goal, outcome, steps, trace | Room | Stored if history enabled; 30-day retention |
| Screenshots | Memory only | Never persisted |
| Debug logs | In-memory StateFlow | Cleared on process death; user can clear |
| Screen observations | Memory only (also in task step summaries if history enabled) | Not stored separately |

History can be disabled entirely. Retention is configurable (1–180 days).
“Delete all history” removes tasks and cascades steps. “Delete keys” removes all
saved API credentials. Uninstalling Piko removes all app data.

## 9. Safety limitations

- Sensitive-action detection is heuristic and localized keywords are not
  exhaustive. The model is also instructed to request confirmation; both layers
  can miss an unusual UI. Users should keep the overlay/notification visible
  during sensitive work.
- Secure windows cannot be observed visually; Piko reports this and relies on
  accessibility text or fails.
- Accessibility trees can be incomplete or misleading in WebViews, games, and
  custom-drawn apps.
- Android may block background app launches; Piko reports the failure rather
  than bypassing it.
- Task/step/cost limits are safety guards, not a guarantee against every
  unintended action.

## 10. Reporting

This is a personal project; there is no hosted service or telemetry. If you
modify Piko, keep the invariants above: validate actions, never log secrets,
never bypass Android security, and keep the confirmation gate for sensitive
actions.

## Voice and screenshot policy

API keys are entered only in Settings, which sets FLAG_SECURE. Home, Models,
provider configuration, task/history and voice input allow screenshots. Do not
share screenshots containing private task or screen content inadvertently.

Voice input uses RECORD_AUDIO only from a visible push-to-talk activity, with
runtime microphone permission. Piko prefers the on-device recognizer; if it is
unavailable, the system speech service may process audio online. There is no
background microphone service or always-on listener. The editable transcript is
sent only when the user presses Send/Start. While editing, an active task pauses;
a submitted instruction cancels stale inference and invalidates pending approvals.

Approval requests use a dedicated high-priority notification channel. Approve/Deny
PendingIntents carry the specific request ID, so stale notifications cannot approve
a different request. Permission/authentication actions always require approval,
independent of the general sensitive-action toggle. Notifications require Android
notification permission; review remains available in the app and floating panel.
