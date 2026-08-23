package sh.sysl.syslui

import android.os.{Build, Bundle}
import android.util.Log
import android.view.{View, WindowInsets}
import org.libsdl.app.SDLActivity

/** The Java-side half of the program, which exists for two reasons.
  *
  * The first is that the manifest needs a launcher activity in the application's own package, and
  * `SDLActivity` lives in the AAR — so a subclass has to exist somewhere to have a name.
  *
  * '''The second is the system bars, and it is the only code here.''' `SDL_GetWindowSafeArea`
  * answers with Android's insets combined — `systemBars`, `systemGestures`,
  * `mandatorySystemGestures`, `tappableElement` and `displayCutout`, all at once — because it
  * answers ''where can a button go''. For a drawing that is far too conservative: on a
  * gesture-navigation phone the back-gesture strips take 78 pixels off each side and the mandatory
  * bottom gesture reaches above the navigation bar, none of which is obscured or untouchable for
  * something only being looked at.
  *
  * SDL exposes the combined rectangle and no way to ask for one kind, so a program that wants the
  * region ''between the bars'' has to read the insets on this side and hand them over.
  *
  * '''Two defaults are taken rather than overridden.''' `getMainSharedObject()` answers `libmain.so`
  * and `getMainFunction()` answers `SDL_main`, which is why `CMakeLists.txt` calls the library
  * `main` and why `main.sysl` exports that symbol.
  */
class MainActivity extends SDLActivity:

  /** Defined in `main.sysl`, not here.
    *
    * JNI binds a native method by mangling the package and class into
    * `Java_sh_sysl_syslui_MainActivity_nativeSetSystemBars`, and that string is what the sysl
    * side `@export`s. '''Renaming this method, this class or this package renames the symbol''' —
    * the link still succeeds, because JNI resolves at run time, and the failure is an
    * `UnsatisfiedLinkError` the first time the insets change.
    *
    * '''It must not be `private`, and that is a Scala rule rather than a JNI one.''' A private
    * method reached from an inner class is renamed by the compiler to
    * `sh$sysl$syslui$MainActivity$$nativeSetSystemBars` so the inner class can see it — and JNI
    * then looks for `Java_..._sh_00024sysl_00024bouncing_00024MainActivity_00024_00024nativeSetSystemBars`,
    * which nothing defines. It compiles, links and dies at the first call. The listener below is an
    * inner class, so this is exactly that case.
    */
  @native def nativeSetSystemBars(left: Int, top: Int, right: Int, bottom: Int): Unit

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)

    // Listening on the decor view rather than on SDL's surface, which already has a listener of its
    // own — `SDLSurface` implements `OnApplyWindowInsetsListener` and is what feeds SDL's own safe
    // area. Taking that one over would break it.
    //
    // The insets are returned unconsumed, so everything below this in the hierarchy still sees them.
    getWindow.getDecorView.setOnApplyWindowInsetsListener(
      new View.OnApplyWindowInsetsListener:
        def onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets =
          if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R then
            // `systemBars()` alone — the status bar and the navigation bar, and none of the gesture
            // regions. That is the whole difference between this and SDL's safe area.
            val bars = insets.getInsets(WindowInsets.Type.systemBars())

            nativeSetSystemBars(bars.left, bars.top, bars.right, bars.bottom)

            // **A line of ordinary Scala, and it is here on purpose.** A `List`, a zip, a `map` and
            // an interpolated string all come from the standard library, so this is the thing that
            // would not link if `scala3-library` were not a dependency of the application — which
            // makes it a check on the build rather than a log message that happens to be in Scala.
            val named = List("left", "top", "right", "bottom")
              .zip(List(bars.left, bars.top, bars.right, bars.bottom))
              .map((side, px) => s"$side=$px")
              .mkString(" ")

            Log.i("bouncing", s"system bars: $named")

          insets
    )
