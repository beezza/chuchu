# Android tmux session manager

On an SSH or Tailscale SSH profile with tmux enabled, tap **[tmux sessions]** in the terminal. The native bottom sheet also remains available when the selected tab has disconnected. Closing the sheet leaves the connection running.

Tap a row to connect in the selected ChuChu tab. The menu (also available on long press) offers Connect, Preview, Rename, Session info and Terminate session. **New session** accepts an optional name and working directory; blank names use the existing `chuchu-N` allocator. Creation refreshes the list; tap the new session to connect. Absolute paths and `~/` paths are supported. tmux does not permit control characters, `:` or `.` in names across supported versions, so new names exclude those characters.

Search is local and case insensitive. Refresh is explicit; the manager does not poll in the background. Preview shows the current active pane of the session's active window, up to the last 80 lines and 32 KiB. It is plain, read-only monospace text, with a two-second minimum refresh interval. Preview data is held in memory, cleared on dismissal, and never written to disk or logs.

**Close tab** disconnects one ChuChu SSH client. **Terminate session** kills the remote tmux session and its running processes, after confirmation; the dialog warns when clients are attached. Matching ChuChu tabs remain present but disconnected and cannot automatically recreate the terminated session. Select another session from the manager or close those tabs.

## Implementation

`TmuxCommands` frames session metadata using numeric fields and hex-encoded UTF-8 text. `TmuxSessionService` handles creation, duplicate checks, rename, termination and capture. Commands are quoted as shell arguments, and directory `#` characters are escaped against tmux format expansion. Invalid directories are checked before `new-session -c`, because tmux may otherwise silently fall back to its default directory.

All management commands use `TerminalSessionRepository.withTmuxService` → `withPreflightEngine` → an independent SSH exec connection on an engine dispatcher. No commands are sent through the terminal input stream. The native reader merges SSH stdout and stderr; the shell envelope captures combined diagnostics with the actual exit status. Command reading has a 20-second deadline and 1 MiB maximum; a management operation has a 30-second coroutine deadline, including waiting for the shared preflight mutex. Host-key prompts participate in cancellation. Native connection establishment has its existing native socket timeout and cannot be interrupted during a single JNI call.

Targets include session ID, creation time, server PID and server start time. `if-shell -F` checks this identity and queues the action in the same tmux server. This rejects IDs reused after server restart. Connecting reuses ChuChu's reconnect path with an identity-guarded existing-session PTY attachment, without `new-session -A`. Metadata updates apply only to tabs on the same endpoint. Duplication clears the old identity before allocating a fresh session.

`TmuxManagerController` serializes operations, binds results to a tab and endpoint, and rejects canceled or stale results. A host/tab change dismisses the old panel. The ViewModel holds state across configuration changes; dialog input uses Compose saveable state, while preview content stays only in memory. Session count, remote client count and ChuChu tab count are distinct. The current indicator tracks the session attached through ChuChu; switching the tmux client externally with tmux key bindings is not currently detected.

The full manager is intentionally disabled for Mosh, local shell and other multiplexers. Existing zmx controls remain available.

The command semantics follow the [upstream tmux manual](https://github.com/tmux/tmux/blob/master/tmux.1). Integration tests exercise the installed local tmux using a private socket and empty configuration. Remote systems need POSIX shell, tmux with `start_time`/format support, and standard `od`, `tr`, `sed`, `tail` and `head` utilities.

## Validation

```sh
make build
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

`TmuxSessionServiceTest` covers framing, metadata, shell quoting, duplicates, creation, rename, termination, stale identities, command failures, search and preview bounds. `TmuxManagerControllerTest` covers confirmation cancellation, duplicate submission, host/tab changes, stale responses, errors and preview throttling. `TmuxIntegrationTest` uses a temporary socket/configuration, including a PTY client, and cleans up only its own server. It skips explicitly if local tmux is unavailable. `TmuxSessionManagerUiTest` exercises the native sheet at 320/360 × 640 dp with Robolectric. Form tests use 320 dp to avoid [Robolectric issue #8460](https://github.com/robolectric/robolectric/issues/8460), which loops when text fields are shown in floating dialogs with wider size qualifiers. Real device validation is still required for Android Back/rotation, keyboard visibility, light/dark themes and actual SSH/Tailscale connectivity.

## Files changed

- UI: `TmuxSessionManager.kt`, `TmuxManagerController.kt`, `TerminalScreen.kt`, `TerminalViewModel.kt`.
- Multiplexer: `MultiplexerModels.kt`, `TmuxMultiplexer.kt`, `TmuxCommands.kt`, `TmuxSessionService.kt`.
- Connection/tab integration: `TabSpec.kt`, `TerminalSessionRepository.kt`, `TerminalSessionEngine.kt`.
- Build/test setup: `android/app/build.gradle.kts` (Material3, Compose test dependency, test heap).
- Tests: `TmuxMultiplexerTest.kt`, `TmuxSessionServiceTest.kt`, `TmuxIntegrationTest.kt`, `TmuxManagerControllerTest.kt`, `TmuxSessionManagerUiTest.kt`.
- Documentation/progress: this document and `.plans/progress.txt` (the plans directory is ignored by repository policy).

## Workspace validation result (2026-10-11)

Native `make build`, `testDebugUnitTest`, `assembleDebug` and `lintDebug` succeeded using the JDK17/Android SDK/NDK/Zig toolchain staged in `/tmp/chuchu-toolchain`. Tests: **158 passed, 5 existing backup skips, 0 failures/errors** (163 total), including five isolated tmux tests and three Compose UI tests. Lint completed with 80 warnings and three hints, no errors. APK signature and all four JNI library entries were verified. No Android device was connected; actual Android-to-SSH execution and device Back/rotation/keyboard/theme behavior remain unverified. Terminal input, key mapping, terminal canvas and native Ghostty input files were not modified.
