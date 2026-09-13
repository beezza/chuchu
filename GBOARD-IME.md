# Gboard Japanese hardware-keyboard input

Experimental fix based on upstream `jossephus/chuchu` commit
`42ba561`. Branch: `fix/gboard-hardware-ime`.

## Changes

- Initialize the standard `EditText` input connection and wrap it for terminal
  emission. This restores Android's editor batch, selection, composing-region,
  monitored extracted-text and cursor-update handling.
- Remove `TYPE_TEXT_FLAG_NO_SUGGESTIONS` from both the view and `EditorInfo`.
  The flag suppresses IME candidates; it is not specific to English autocorrect.
- Intercept Android 13 text-attribute calls and Android 14 `replaceText` so the
  wrapper does not silently bypass terminal emission.
- Route code-point deletion to the terminal, avoid double deletes after batched
  commits/deletes, and avoid splitting supplementary Unicode characters during
  candidate replacement.
- Keep ordinary physical-key routing after Android's IME dispatch. No pre-IME
  interception is added; Ctrl/Alt/Shift terminal shortcuts retain their path.

## Command completion notifications

When a command runs for at least five seconds, Chuchu can post a notification
after it finishes if the app is in the background or another terminal tab is
visible. The notification shows the exit status and duration, never the command
text; tapping it returns to that session. The hook is installed only in the
current interactive shell, so it also follows SSH sessions inside tmux or
Zellij without changing remote dotfiles. Bash, zsh, and fish are supported.
For tmux, ChuChu enables the current pane's `allow-passthrough` option and
uses tmux DCS passthrough so OSC 9 is not consumed by tmux. This requires a
tmux version with `allow-passthrough` (3.3 or newer); if the server policy
disallows changing pane options, add `set -g allow-passthrough on` to
`~/.tmux.conf` and restart or reload the tmux server.

The existing live terminal preview of composing text is retained. Intermediate
readings are still sent to the remote terminal and replaced with Backspace when
the IME changes candidates. This is not a commit-only input mode, and terminal
applications that react immediately to each character need device testing.

## Build and install

On the fork, enable GitHub Actions if GitHub has disabled inherited workflows.
Push this branch, or run **Gboard debug APK** with this branch selected. The
workflow runs unit tests, builds the native arm64 library and uploads
`chuchu-gboard-arm64-debug` containing `app-debug.apk`.

The debug package is `com.jossephus.chuchu.gboard`, so it can coexist with the
upstream app. It has separate app data and uses the standard debug signing key.
Do not use it as a release-signed update. Each fresh CI runner can generate a
different debug key; subsequent test builds may need uninstall/reinstall.
The source does not contain an APK or a signing secret.

With the upstream Nix development environment, local commands are:

```sh
nix develop -c bash -c 'cd android && ./gradlew :app:testDebugUnitTest'
nix develop -c bash -c 'cd zig-src && zig build -Doptimize=ReleaseSmall -Dtarget=aarch64-linux-android.24 jni'
nix develop -c bash -c 'cd android && ./gradlew :app:assembleDebug'
```

## Device acceptance test

Use Gboard with Japanese enabled and a connected USB/Bluetooth keyboard. Record
the Android version, Gboard version, keyboard layout, and whether the software
keyboard is visible. Run the checks with it visible and hidden.

1. Select Japanese input, type `nihongo`, choose `日本語` with Space/candidate
   selection, then confirm with Enter. It should leave exactly `日本語`.
2. Repeat conversion and confirm the currently displayed candidate without
   changing it. Confirming must not delete the word or duplicate it.
3. Press Backspace after confirming. It should delete one character.
4. Switch to English and type uppercase letters and shifted punctuation.
5. Check Ctrl+C, Ctrl+A, Alt+arrow, Tab completion, arrows, and Enter. Test your
   Zellij session as well as an ordinary shell.
6. Press an accessory-bar key during composition, then enter Japanese again.
   Old composing text should not be replayed.
7. Try the software keyboard and voice dictation as regression checks.

## Verification status (2026-09-08)

- `git diff --check`: passed.
- 14 Robolectric regression tests added using Android API 34, including the
  platform editor batch lifecycle and Android 13/14 input APIs.
- Android unit tests (including all 14 new IME tests), native library build,
  debug APK assembly and native-library packaging check: **passed** in
  [Actions run 34239943887](https://github.com/beezza/chuchu/actions/runs/34239943887)
  at commit `cd03b158b09299da67813af259c1f135b1002df5`.
- APK SHA-256: `a242df681f680e6162937f5f6ccd759d95fba4b65b2e36173828190fb11737fc`.
  The downloaded archive digest matched GitHub Actions, and the APK includes
  `lib/arm64-v8a/libchuchu_jni.so`.
