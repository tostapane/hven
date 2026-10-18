# Hven

An open-source Android app: a **private assistant that runs a small LLM entirely on-device** and can actually do things on the phone (reminders, calendar, notes, notifications), with no cloud and no dependency on Google services.

**Name:** Hven is the island where Tycho Brahe built Uraniborg and made the most precise observations of his era without a telescope. Simple instruments, maximum precision: a small model on a phone, made reliable.

## Goals

- **Reliability and speed** are the edge. Being less powerful than Google's assistant is fine; being flaky or slow is not.
- **vs PocketPal AI** (generic GGUF runner with a chat UI): curated, validated models, zero setup, and actions instead of just chat.
- **vs Google** (Gemini, AICore, AppFunctions, ADK for Android): independent, open models, works on de-Googled ROMs.
- **vs GUAPPA, PokeClaw, MobileClaw, Aiden (iOS):** those are thin apps over generic runtimes.

### Milestones

- **M0 — Skeleton:** a minimal app that builds and installs from the command line and runs on a physical device.
- **M1 — Inference:** load one model on-device and stream tokens to the UI.
- **M2 — Tool calls:** model adapter for the chosen model, grammar-constrained tool calls, about 5 tools (alarm, timer, calendar event, note, open app).
- **M3 — KV cache:** save and load prefix state to disk. Measure time-to-first-token with vs without.
- **M4 — Eval suite:** automated task set on real devices measuring success rate, time-to-first-token, tokens/sec and battery. Compare candidate models (Gemma 4 E2B vs Qwen3 1.7B/4B) and pick the v1 model.
- **M5 — Assistant features:** keep the model warm in the background, read notifications, persistent memory.
- **Later:** model-specific kernels, GPU backend, NPU, AppFunctions backend, more validated models.

### Out of scope for now

Voice, iOS, cloud fallback, a generic model browser.

## Core principles

1. **KISS.** Everything is implemented in the simplest way that works and can be easily explained. If a design can't be explained simply, it's the wrong design.
2. **Few models, each fully validated.** Curated list only; no loading arbitrary models. Start with one, add more through the same validation pipeline.
3. **Model adapter interface from day one.** It covers prompt template, tool-call format and parser, grammar, and quantization config per model.
4. **Persistent KV prefix cache.** The system prompt and tool definitions are prefilled once, saved to disk, and reloaded instantly. This attacks slow prefill, the main latency problem on phones.
5. **Grammar-constrained tool calls**, so the model can't emit malformed calls.
6. **Fully on-device.** No network except the one-time model download.
7. **Measure everything:** tool-call success rate, time-to-first-token, tokens/sec, battery.

## Platform constraints

- **Android first.** iOS does not allow cross-app actions by third-party apps.
- **AppFunctions** (Android 16+) is experimental, and calling other apps' functions needs `EXECUTE_APP_FUNCTIONS`, which is allowlisted on API 37+. Treat it as an optional future backend.
- Primary tool paths are Intents, ContentProviders (e.g. CalendarContract), NotificationListenerService, and AccessibilityService as a fallback.

## Tech stack

Every choice must follow KISS and be agreed on before any code depends on it. Agreed so far:

- **App:** Kotlin + Jetpack Compose.
- **Inference:** llama.cpp (C/C++, via the NDK), called from Kotlin through JNI. Later, hot paths get replaced by our own model-specific code.
- **Build:** Gradle + CMake.

Everything else gets decided when a milestone needs it.

## Structure

No code yet. The layout gets defined at M0 and documented here.

- `AGENTS.md`: rules, structure and goals. Stable; no progress tracking here.
- `CLAUDE.md`: points to this file.
- `TODO.md`: local and gitignored. What's done, what's missing, open questions.

## Working rules

- **Discuss before implementing.** Design is a Socratic dialogue: the human proposes, the agent critiques and proposes alternatives, until the human fully understands the chosen approach. No code for a feature whose design hasn't been agreed.
- **Peers, not order-taking.** The agent writes most of the code and follows the human's final decisions, but it must say clearly when it thinks something is wrong, and why.
- **The human must understand all code.** Code nobody can explain doesn't get merged.
- **Avoid overengineering.** Build what the current milestone needs, nothing more.
- **Commits:** the human makes most commits. Before committing, the agent always asks; if the human says yes, the commit gets the agent's `Co-Authored-By` trailer, otherwise it carries no agent attribution.
- **Keep `TODO.md` current** when something is done or a new gap appears.

## Code rules

- Pin dependency versions.
- Language-specific rules get added once the stack is chosen.

## Dev environment

- Arch Linux, Neovim. Command-line toolchain for building; Android Studio is fine for testing and debugging.
- Android SDK platform-tools (`adb`) for installing, logs (`adb logcat`) and profiling (`simpleperf`, Perfetto, `adb shell dumpsys batterystats`). The rest of the toolchain follows the stack choice.
- Always test on a **physical device**; emulators are useless for performance.
