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
- Unit tests and APK build: **not run**. This editing environment has no Android
  SDK, and Gradle distribution download fails with `Network is unreachable`.
- Gboard/physical keyboard on a real device: **not tested**. Robolectric does
  not run Gboard or prove that a specific Gboard/device combination is fixed.
- GitHub fork/push: pending authentication; local source is prepared.

## References

- [InputConnectionWrapper](https://developer.android.com/reference/android/view/inputmethod/InputConnectionWrapper)
- [InputType](https://developer.android.com/reference/android/text/InputType)
- [Robolectric](https://robolectric.org/)
