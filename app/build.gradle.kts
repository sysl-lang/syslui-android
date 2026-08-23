import java.util.Properties

plugins {
    id("com.android.application")
}

// **The release signing key, which is deliberately not in this repository.** It is read from
// `~/.android/sysl-signing.properties` — keystore path, password and alias — and where that file is
// absent the release build is simply unsigned, so a fresh clone still builds without it. A key
// committed beside the thing it signs is not a key.
//
// **Losing it costs more than it looks.** Android identifies an app by its signature, so a new key
// means a new identity: an APK signed with a different one will not install over an existing
// install, and everybody who has it has to uninstall first.
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/sysl-signing.properties")

    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "sh.sysl.syslui"
    compileSdk = 36

    // **Pinned, and pinned to the stable one.** AGP downloads whatever it defaults to if this is
    // absent, so leaving it out means the toolchain changes under you when AGP does. It also has to
    // be a version this machine will actually keep: `~/Library/Android/sdk/ndk/` here also holds an
    // `r30` beta, and `CMakeLists.txt` hands *this* NDK to `sysl build-c` precisely so the two halves
    // of the build cannot end up on different ones.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "sh.sysl.syslui"

        // **26, and the number is the Scala standard library's rather than SDL's or sysl's.**
        // `scala-library` uses class-file features `d8` will only desugar from 26 up — *"Increase the
        // minSdkVersion to 26 or above"*, which is what it says rather than something inferred — so
        // an APK carrying the Scala runtime starts at Android 8.0. SDL's own floor is 21 and sysl's
        // triple states 24.
        //
        // **The three numbers do not have to agree, and the higher wins.** The `.so` is compiled
        // against `aarch64-linux-android24`'s declarations, which every device from 24 up has, and it
        // is installed only where the APK is — so a native half built for 24 running on 26 is exactly
        // as correct as one built for 26. What is not allowed is the other direction: a `minSdk`
        // *below* the triple's number installs on a device whose Bionic may not have what the `.so`
        // was compiled against.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_PLATFORM=android-24")
            }
        }

        // **One ABI, and it is not a limitation worth apologising for.** On an Apple Silicon host the
        // emulator runs `arm64-v8a`, and so does every Android device made since 2015 — so one build
        // covers the emulator and the hardware. `x86_64` matters only on an Intel host or a CI
        // runner, `armeabi-v7a` only for pre-2015 phones; adding either is a line here and a second
        // row in the sysl registry that does not exist yet.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        // What unpacks the SDL3 AAR into the prefab layout that `find_package(SDL3 CONFIG)` reads.
        prefab = true
    }

    signingConfigs {
        create("release") {
            signingProps.getProperty("SYSL_KEYSTORE")?.let {
                storeFile = File(it)
                storePassword = signingProps.getProperty("SYSL_KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("SYSL_KEY_ALIAS")
                keyPassword = signingProps.getProperty("SYSL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (signingProps.getProperty("SYSL_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            // **Shrinking is off, and that is a decision rather than a default.** R8 works by
            // reachability, and two things here are reached by neither: the activity is named in the
            // manifest as a string, and `nativeSetSystemBars` is called *from native code* through
            // JNI. Both need keep rules, and getting one wrong produces an app that installs and
            // dies at the first inset. The APK is a few megabytes larger and the demo runs.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        abortOnError = false
    }
}

// **The activity is Scala, which AGP cannot compile — so sbt does, and Gradle takes the jar.**
//
// That is the whole cost of the choice and it is worth stating plainly: the Android Gradle plugin
// builds Java and Kotlin itself and has no Scala support, so this is a second build system in the
// project. What it does *not* cost is anything at run time — the class references nothing from the
// Scala standard library, so no `scala-library` is dexed in and the APK carries two extra classes.
//
// `./gradlew assembleDebug` is still the one command: this task runs sbt, and the jar it writes is
// on the application's classpath below.
val activityDir = rootProject.file("activity")
val activityJar = activityDir.resolve("target/syslui-android-activity.jar")

val compileActivity = tasks.register<Exec>("compileActivity") {
    description = "Compiles the Scala activity with sbt, since AGP cannot."
    workingDir = activityDir

    // Both jars are handed over rather than looked for on sbt's side. SDL's `classes.jar` lives
    // *inside* the AAR, so it has to be unzipped by somebody, and this is where the AAR's path is
    // already known.
    doFirst {
        // **Every AAR, not the first one.** `firstOrNull()` was fine while there was one; with
        // SDL3_ttf beside SDL3 it picked whichever the filesystem listed first, and handing sbt
        // SDL3_ttf's `classes.jar` left `SDLActivity` off the classpath — which Scala reports as a
        // *cyclic reference* on the `extends` clause, not as a missing type.
        val aars = file("libs").listFiles { f -> f.name.endsWith(".aar") }?.sorted().orEmpty()

        if (aars.isEmpty()) throw GradleException("no AAR in app/libs — run ./fetch-sdl3.sh")

        val jars = aars.map { aar ->
            val into = layout.buildDirectory.dir("aar-classes/${aar.nameWithoutExtension}").get().asFile

            copy {
                from(zipTree(aar)) { include("classes.jar") }
                into(into)
            }

            into.resolve("classes.jar")
        }.filter { it.isFile }

        // `android.jar` from the SDK the rest of the build already requires. The newest installed
        // platform is taken rather than one matching `compileSdk`, because the directory may carry a
        // minor version (`android-36.1`) that the number does not.
        val sdk = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException("ANDROID_HOME is not set")

        val androidJar = File(sdk, "platforms").listFiles()
            ?.map { it.resolve("android.jar") }
            ?.filter { it.isFile }
            ?.maxByOrNull { it.parentFile.name }
            ?: throw GradleException("no android.jar under $sdk/platforms")

        environment(
            "ANDROIDKIT_CLASSPATH",
            (listOf(androidJar) + jars).joinToString(File.pathSeparator),
        )
    }

    commandLine("sbt", "-batch", "package")

    // sbt names the jar for the project and version; the build renames it to something Gradle can
    // predict, since a version bump would otherwise silently stop matching.
    doLast {
        val built = activityDir.resolve("target").walkTopDown()
            .firstOrNull { it.name.endsWith(".jar") && it.name.startsWith("syslui-android-activity") }
            ?: throw GradleException("sbt produced no jar in activity/target")

        if (built != activityJar) built.copyTo(activityJar, overwrite = true)
    }

    inputs.dir(activityDir.resolve("src"))
    inputs.file(activityDir.resolve("build.sbt"))
    outputs.file(activityJar)
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    dependsOn(compileActivity)
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("JavaWithJavac") }.configureEach {
    dependsOn(compileActivity)
}

dependencies {
    implementation(files(activityJar) { builtBy(compileActivity) })

    // **The Scala standard library, so the activity can use the language and not only its syntax.**
    // Nothing in the file below needs it today — an empty subclass and a listener reference nothing
    // from it — but an activity that grows real code will, and finding that out at the first `List`
    // is a worse time to find it out. R8 shrinks whatever is unreached in a release build, and
    // `minSdk 24` has native multidex, so the 64k method limit is not a consideration either.
    implementation("org.scala-lang:scala3-library_3:3.8.2")

    // **The AAR is not in this repository** — `./fetch-sdl3.sh` downloads it, and `.gitignore` keeps
    // it out. It is 16 MB of binaries built against an NDK and an API level somebody else chose, and
    // the org's rule against carrying a prebuilt `.so` in a package is the same argument one level
    // up: what is committed here should be readable, and a `.so` is not. picokit makes the same
    // choice about its 81 MB pico-sdk clone.
    implementation(fileTree("libs") { include("*.aar") })
}
