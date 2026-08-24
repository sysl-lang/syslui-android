# syslui-android

**syslUI on a phone.** A form with a text field, a text area, radio buttons and a keyboard that comes
up when you tap into one — drawn by PlutoVG into a block of pixels, handed to SDL3 as one texture,
and running on Android with no C in the repository at all.

![the form tab, with a name typed into it](shot.png)

The second tab is the controls, which are the same ones the desktop demo has and needed nothing
added to run here.

![the controls tab](shot-controls.png)

Both are screenshots off a running emulator, not renderings.

## What it is for

`sysl-lang/syslui-demo` is the toolkit's desktop demo. This is the same toolkit on the other kind of
machine, and it exists to answer three questions that a desktop cannot:

- **Does a tap work where a mouse press did?** It did not, and finding that out is what this
  repository is for. Focus was taken by a field noticing `pressed(r)` while it painted, which works
  with a mouse only because a mouse press lasts several frames — a tap is a press and a release
  arriving *between* two frames, so no field could ever be typed into. syslUI records the press now
  and reports it for exactly the frame that follows (`Canvas.tapped`).
- **Does the toolkit link when Gradle owns the link?** It did not: `sysl build-c` refused the whole
  package, because an `@export`ed `SDL_main` reaching computed module storage has no `main` to run
  the initializer that fills it. The write counter is a bare `var` now.
- **Is it legible at a phone's density?** Only if somebody scales it, and that somebody is the
  application — see below.

Both fixes are in `sysl-lang/syslui`, and neither could have been found by a test in that package.

## Building it

You need Android Studio's SDK with the **NDK** and **CMake** installed (SDK Manager → SDK Tools), a
**JDK between 17 and 25**, and `ANDROID_HOME` set. Nothing has to sit beside this repository: the
driver is a public package named in `syslui-android/package.hocon`, and `sysl build-c` fetches it —
along with syslUI, SDL3 and PlutoVG, which it brings with it because imports are transitive as of
sysl 0.0.73.

```
export ANDROID_HOME=~/Library/Android/sdk
./fetch-sdl3.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n sh.sysl.syslui/.MainActivity
```

**It needs sysl 0.0.73 or newer** — 0.0.61 was the first that knew `aarch64-android`, and 0.0.73 is
the first with transitive imports, which is what lets the manifest name one coordinate. `sysl targets` lists what a compiler has.

An emulator works and an arm64 one is required: sysl has one Android target. On an Apple Silicon
machine the ordinary system images are arm64 already.

## The whole program is two exports and a screen

**146 lines, and it was 345.** The window, the frame loop, the event pump, the density, the texture
upload and the system font are [`syslui-sdl`](https://github.com/sysl-lang/syslui-sdl)'s — the same
driver the desktop demo runs, because the measurement said seventeen lines out of a hundred and
twenty were genuinely about being on a phone. What is left here is the interface and the two things
that cannot live in a library:

- **`@export("SDL_main")`**, because Android looks the symbol up rather than calling `main`.
- **the JNI method that reports the system bars**, whose symbol is mangled from *this* application's
  package name. Four lines, and it calls the driver's `set_insets`.

## The three things a phone does differently

All three are the driver's now, and **none of them is in the toolkit**: `sh.sysl.ui` has no scale
factor, no orientation and no insets, which is the split working — a toolkit that had guessed at any
of them would have to be told it was wrong on every device that disagreed.

- **The density.** The tree is built and measured in *points*; the canvas is scaled by
  `window.display_scale()` before anything is drawn, so a rounded corner and a glyph are rasterized
  at the pixel size they end up at. Scaling the finished picture instead would be one bilinear blur
  over the whole interface. A laptop answers 1.0 or 2.0 there and a phone about 2.75.

  **A touch is reported in window units and the drawing is in pixels**, and on a high-density
  display those are not the same — so the ratio is measured from `size_in_pixels` against `size`
  rather than assumed, and then divided by the scale again to land in points.

- **The system bars.** From API 35 an app draws edge to edge whether it asks to or not.
  `window.safe_area()` is the obvious answer and the wrong rectangle: SDL builds it out of five inset
  types at once because it answers *where can a button go*, which on a gesture-navigation phone is 78
  pixels off each side. So `MainActivity` reads `WindowInsets.Type.systemBars()` and calls a native
  method **defined in sysl** — the `@export("Java_sh_sysl_syslui_MainActivity_nativeSetSystemBars")`
  in `main.sysl` — and the interface is laid out in what is left.

- **The keyboard is something you ask for.** `window.start_text_input()` raises it and
  `stop_text_input()` puts it away, and what decides is `c.focus()`: the id of whichever field has
  the keys, or `NOBODY`. Four lines in the frame loop, no view involved in either, and the toolkit
  never learns that a soft keyboard exists.

  **Typed text arrives as a `TextInput` event, already composed**, which is why a field takes a run
  of text rather than a character: a phone's suggestion bar inserts whole words, and an accent or a
  CJK composition never arrives any other way. `key_of` maps the dozen keys an editing surface cares
  about — the arrows, the two deletes, home, end, enter, tab — and answers `None` for everything
  else, so a shortcut stays the program's.

## How the two halves are joined

**Gradle owns the link, so the arrangement is upside down from an ordinary sysl build**: `sysl
build-c` compiles the program to an archive and CMake links it into the `libmain.so` the APK carries.

| file | what it does |
|---|---|
| `syslui-android/main.sysl` | the whole program — both exports and the screen |
| `app/src/main/cpp/CMakeLists.txt` | runs `sysl build-c`, links the archive into `libmain.so` |
| `activity/src/main/scala/…/MainActivity.scala` | an `SDLActivity` subclass in **Scala**, which reads the insets |
| `activity/build.sbt` | compiles it — AGP has no Scala support, so sbt does and Gradle takes the jar |
| `app/build.gradle.kts` | `prefab true`, one ABI, the pinned NDK |
| `fetch-sdl3.sh` | downloads the SDL3 AAR |

**Five things in there are load-bearing and silent when wrong.** Four are androidkit's and are
written up in that repository: `-u SDL_main` in the link options, the library being called `main`,
`--include-path sdl3=<dir>` named rather than bare, and `ANDROID_NDK_ROOT` handed to `sysl build-c`.

The fifth was this repository's own and is **gone with the `--lib`**: while the toolkit was reached
by path, its sources had to be named in `DEPENDS` or the APK carried the old copy with nothing said —
which happened here, once, between a fix and the build that was supposed to prove it. A coordinate
has a version, and a version is not something a build can be stale against.

## Licence

ISC, like the rest of the org. SDL3 is zlib and PlutoVG is MIT; neither is carried here.
