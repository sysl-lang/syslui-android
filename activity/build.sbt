// The Java-side half of the syslUI demo, written in Scala.
//
// **This is a second build system, and that is the whole cost of choosing Scala here.** The Android
// Gradle plugin compiles Java and Kotlin itself and has no Scala support, so the class has to be
// produced outside it and handed over as a jar. `app/build.gradle.kts` runs this build and puts the
// jar on the application's classpath; `./gradlew assembleDebug` is still the one command.
//
// **What it does not cost is anything at run time.** The class references nothing from the Scala
// standard library — checked, not assumed — so no `scala-library` is dexed into the APK and the
// method count is the two classes below and nothing else.

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "syslui-android-activity",

    // A jar named for what it is rather than for a Scala version, since the thing consuming it is
    // Gradle and knows nothing about either.
    crossPaths := false,

    // `android.jar` and SDL's `classes.jar`, handed over by Gradle — which already knows where both
    // are and would otherwise have to be guessed at from `ANDROID_HOME` and a glob over the
    // installed platforms.
    Compile / unmanagedJars ++= sys.env
      .getOrElse("ANDROIDKIT_CLASSPATH", "")
      .split(java.io.File.pathSeparator)
      .filter(_.nonEmpty)
      .map(p => Attributed.blank(file(p)))
      .toSeq,
  )
