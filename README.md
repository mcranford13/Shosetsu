# Shosetsu Audiobook

An unofficial fork of [Shosetsu](https://gitlab.com/shosetsuorg/shosetsu), the free and open source Android app for reading light novels.

This fork adds on-device audiobook narration with distinct character voices, plus a few reading and update conveniences. It is maintained separately from the official project. Please report fork-specific problems in [this repository's issues](https://github.com/mcranford13/Shosetsu/issues), not to the upstream Shosetsu team.

## Download

Get the latest signed APK from [GitHub Releases][Latest].

This build uses the same package id as official Shosetsu (`app.shosetsu.android`) but a different signing key. Uninstall an official Shosetsu install before installing this fork. Once installed, later releases from this repository can update in place from inside the app.

Voice models are not bundled in the APK. Download them in the app under **Settings → Reader → On-device voice packs**.

## What this fork adds

### Audiobook narration

- **On-device neural voices.** Read chapters with downloadable [Piper](https://github.com/rhasspy/piper) voices or the higher-quality Kokoro voice pack, powered by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Narration runs offline once a pack is downloaded. The Android system voice is still available and remains the fallback.
- **Character voices.** Dialogue is detected from quotation marks and speech tags such as `Clara said` or `Clara: "..."`, and each speaker can be assigned a different voice. Narration and unidentified speakers have their own default voices.
- **A cast remembered per novel.** Voice assignments are saved for each novel, so a character keeps the same voice across chapters. Open the cast sheet from the reader to change who sounds like whom.
- **More natural pacing.** Paragraphs are spoken one sentence at a time, upcoming sentences are prepared while the current one plays, and sentences finish before the next begins. Pauses between lines and between paragraphs are adjustable in reader settings.
- **Listening with the screen off.** Narration keeps playing when the phone is locked. A media notification shows the chapter and current speaker, and can pause, resume, or stop playback.
- **Voice pack management.** Download and remove voice packs and the shared phoneme data from their own settings screen.

### Reading and library

- **Mark a chapter range.** From a novel's menu, choose **Mark chapter range** and enter a first and last chapter position to mark that span as read or unread, instead of selecting each chapter by hand.

### Updates

- **Updates from this fork.** The standard app checks this repository's GitHub releases for updates and installs the signed standard APK in place, keeping your library.

## License

This fork retains Shosetsu's [GNU General Public License v3.0](LICENSE).

[Latest]: https://github.com/mcranford13/Shosetsu/releases/latest
