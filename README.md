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
**JDK between 17 and 25**, and `ANDROID_HOME` set. Nothing has to sit beside this repository:
Skitter and the driver are public packages named in `program/package.hocon`, and `sysl build-c`
fetches them — along with syslUI, SDL3 and PlutoVG, which they bring with them because imports are
transitive as of sysl 0.0.73.

```
export ANDROID_HOME=~/Library/Android/sdk
./fetch-sdl3.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n sh.sysl.syslui/sh.sysl.skitter.SkitterActivity
```

**The activity is named in full, and it is not in this application's package** — that is Skitter's
whole trick and the reason the `.MainActivity` shorthand no longer applies here. `android:name` takes
any class on the classpath, so the launcher activity is a library's and this application's id is a
string nothing else has to agree with.

**It needs sysl 0.0.82 or newer** — 0.0.61 was the first that knew `aarch64-android`, 0.0.73 the
first with transitive imports, and 0.0.82 is syslUI's own floor. `sysl targets` lists what a compiler
has.

An emulator works and an arm64 one is required: sysl has one Android target. On an Apple Silicon
machine the ordinary system images are arm64 already.

## The whole program is one export and a screen

**It was 345 lines, then 146, and the export it lost is the interesting one.** The window, the frame
loop, the event pump, the density, the texture upload and the system font are
[`syslui-sdl`](https://github.com/sysl-lang/syslui-sdl)'s — the same driver the desktop demo runs,
because the measurement said seventeen lines out of a hundred and twenty were genuinely about being
on a phone. The system bars, the orientation pair and the Java-side activity are
[`skitter`](https://github.com/sysl-lang/skitter)'s. What is left here is the interface and one
thing that cannot live in a library:

- **`@export("SDL_main")`**, because Android looks the symbol up rather than calling `main`.

**The second export used to be here and is the reason Skitter exists.** JNI mangles a native
method's symbol from the *class's* package, so while the activity had to be renamed into each
application's package, the sysl half of the bridge had to be written out by hand to match — four
spellings of one name kept in step by a person. Skitter fixes the activity at
`sh.sysl.skitter.SkitterActivity`, which fixes the symbol, which is what lets the bridge be a
library's. This program reads the bars now instead of receiving them.

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
  pixels off each side. So `SkitterActivity` reads `WindowInsets.Type.systemBars()` and calls a
  native method **defined in sysl** — Skitter's `insets.sysl` — and the interface is laid out in what
  is left. This program pulls the four numbers with `insets()` and hands them to the driver.

  **The pull is a seam that still wants closing, and it is one line in the wrong place.** Skitter's
  bars cannot be pushed: `sysl build-c` refuses an `@export` reaching module storage an initializer
  would have to fill, so the bridge writes four bare `int`s and somebody must read them. The driver
  rebuilds its tree only when a signal is written and a rotation writes none, so the read has to
  happen somewhere unconditional — which today is `window_ground`, the function that answers the
  clear colour. A driver that depended on Skitter and pulled the bars itself would delete that
  second job, its own four inset `var`s and its duplicated `SDL_ORIENTATIONS` hint together.

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
| `program/main.sysl` | the whole program — one export and the screen |
| `program/package.hocon` | two coordinates: Skitter and the driver |
| `gradle.properties` | **the only two lines naming this application**, per Skitter's arrangement |
| `app/src/main/cpp/CMakeLists.txt` | runs `sysl build-c`, links the archive into `libmain.so` |
| `activity/src/main/scala/sh/sysl/skitter/SkitterActivity.scala` | Skitter's `SDLActivity` subclass in **Scala**, which reads the insets |
| `activity/build.sbt` | compiles it — AGP has no Scala support, so sbt does and Gradle takes the jar |
| `app/build.gradle.kts` | `prefab true`, one ABI, the pinned NDK |
| `fetch-sdl3.sh` | downloads the SDL3 AAR |

**Everything from `CMakeLists.txt` down is byte-identical to
[`skitter-app`](https://github.com/sysl-lang/skitter-app)**, which is the point of porting: this
repository used to carry its own copy of roughly seven hundred lines of Gradle, CMake, manifest and
sbt scaffolding, of which about eight lines were genuinely its own. A fix to the template is now a
diff to apply rather than four repositories to remember.

**Four things in there are load-bearing and silent when wrong**, and all four are written up in
`skitter-app`: `-u SDL_main` in the link options, the library being called `main`,
`--include-path sdl3=<dir>` named rather than bare, and `ANDROID_NDK_ROOT` handed to `sysl build-c`.

### Checking the bridge is really in there

**There is no unit test for this and there cannot be one** — whether JNI finds the symbol is decided
by a class name in an APK on a device, and a test that mocked the lookup would be asserting the mock.
What *can* be checked in one command is that the archive carries the symbol at all, and that it is in
the same object as `SDL_main` so that `-u SDL_main` pulls it in:

```
sysl build-c program
ar p program/syslui-android.a sysl.code.o > /tmp/sysl.code.o
nm -g /tmp/sysl.code.o | grep "SkitterActivity_nativeSetSystemBars\|_SDL_main"
```

Two lines back is right. **One line back is the failure that installs and dies at the first
rotation**, and it is worth running after any change to the dependency list, because an archive
missing the bridge links exactly as cleanly as one carrying it.

A fifth was this repository's own and is **gone with the `--lib`**: while the toolkit was reached
by path, its sources had to be named in `DEPENDS` or the APK carried the old copy with nothing said —
which happened here, once, between a fix and the build that was supposed to prove it. A coordinate
has a version, and a version is not something a build can be stale against.

## Licence

ISC, like the rest of the org. SDL3 is zlib and PlutoVG is MIT; neither is carried here.
