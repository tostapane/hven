# Hven

An open-source Android app: a **private assistant that runs a small LLM entirely on-device** and can actually do things on the phone (reminders, calendar, notes, notifications), with no cloud and no dependency on Google services.

**Name:** Hven is the island where Tycho Brahe built Uraniborg and made the most precise observations of his era without a telescope. Simple instruments, maximum precision: a small model on a phone, made reliable.

## Goals

- **Reliability and speed** are the edge. Being less powerful than Google's assistant is fine; being flaky or slow is not.
- **vs PocketPal AI** (generic GGUF runner with a chat UI): curated, validated models, zero setup, and actions instead of just chat.
- **vs Google** (Gemini, AICore, AppFunctions, ADK for Android): independent, open models, works on de-Googled ROMs.

### Milestones

- **M0 — Skeleton:** a minimal app that builds and installs from the command line and runs on a physical device.
- **M1 — Inference:** load one model on-device and stream tokens to the UI.
- **M2 — Tool calls:** grammar-constrained tool calls for the chosen model, three tools (timer, alarm, calendar event), all through Intents.
- **M3 — Warm start:** prefill the system prompt and tool definitions at launch. Measure time-to-first-token with vs without.
- **M4 — First release (v0.1):** in-app model download, release build, an app a stranger can install and use.
- **M5 — Eval suite:** automated task set measuring tool-call success rate on a laptop, and time-to-first-token, tokens/sec and battery on real devices. Compare candidate models (Qwen3.5-2B vs Gemma 4 E2B) and pick the v1 model.
- **M6 — KV cache:** save and load prefix state to disk. Measure time-to-first-token with vs without.
- **M7 — More tools:** note, open app.
- **M8 — Assistant features:** keep the model warm in the background, read notifications, persistent memory.
- **Later:** model-specific kernels, GPU backend, NPU, AppFunctions backend, more validated models.

### Out of scope for now

Voice, iOS, cloud fallback, a generic model browser.

## Core principles

1. **KISS.** Everything is implemented in the simplest way that works and can be easily explained. If a design can't be explained simply, it's the wrong design.
2. **Few models, each fully validated.** Curated list only; no loading arbitrary models. Start with one, add more through the same validation pipeline.
3. **One config object per model.** It covers prompt template, tool-call format and parser, grammar, and quantization config. It becomes an interface only when a second model exists.
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

- `app/`: the Android app (one Gradle module).
  - `src/main/kotlin/io/github/tostapane/hven/`: Kotlin sources.
  - `src/main/cpp/`: native code (`CMakeLists.txt` builds `libhven.so`).
  - `src/main/AndroidManifest.xml`: app name, screens, permissions.
- `gradle/libs.versions.toml`: every dependency version, in one place.
- `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`: build config.
- `gradlew`, `gradle/wrapper/`: pinned Gradle; build with `./gradlew installDebug`.
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

### Comments

Applies to new and changed code; existing comments get fixed only when their code is touched.

- Every comment is concise, clear and simple.
- **File header:** at the very top, what the file does. At most 4 lines; usually 2.
- **Functions:** one or two lines above each function.
- **Inside functions:** no comments, except a rare, very short one-liner.
- Short examples of a format (e.g. sample lines of generated text) are fine and don't count toward the limits.

## Dev environment

- Arch Linux, Neovim. Command-line toolchain for building; Android Studio is fine for testing and debugging.
- Android SDK platform-tools (`adb`) for installing, logs (`adb logcat`) and profiling (`simpleperf`, Perfetto, `adb shell dumpsys batterystats`). The rest of the toolchain follows the stack choice.
- Always test on a **physical device**; emulators are useless for performance.
