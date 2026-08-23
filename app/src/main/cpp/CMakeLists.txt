cmake_minimum_required(VERSION 3.22)

project(syslui_android C)

# **Gradle owns the link here, so the arrangement is upside down from an ordinary sysl build.**
# picokit's README puts it exactly: "The pico-sdk owns the link … So the arrangement is the other way
# up: `sysl build-c` compiles this program into an archive, and CMake links it." Android is the same
# shape with a different owner — the APK's `.so` is Gradle's output, and sysl contributes an archive
# to it rather than producing the binary itself.

# SDL comes from the AAR, through prefab. `fetch-sdl3.sh` puts the AAR in `app/libs/`, Gradle's
# `prefab true` unpacks it, and this is the package it publishes. Nothing here names a path into it:
# the two directories the sysl build needs are read back off the imported target below, so a new SDL
# version changes one line in `fetch-sdl3.sh` and nothing else.
find_package(SDL3 REQUIRED CONFIG)

# **The one thing CMake has to be told, because getting it wrong is not a link error.** `sysl` is a
# program CMake knows nothing about, so the target it compiles for is stated here rather than
# inferred. Only `arm64-v8a` is built (see `app/build.gradle.kts`), so there is one row to name.
set(SYSL_TARGET aarch64-android)

set(SYSL_ARCHIVE ${CMAKE_CURRENT_BINARY_DIR}/libsyslui.a)
set(SYSL_PROGRAM ${CMAKE_CURRENT_SOURCE_DIR}/../../../../syslui-android)

# **syslUI and its SDL driver are coordinates, so there is nothing to point at.** Both repositories
# are public and `package.hocon` names them; `sysl build-c` fetches them like any other dependency,
# and this file is one `--lib` and one `file(GLOB)` shorter than it was while the toolkit was private.

# Where SDL's headers and its `.so` actually are, asked of the imported targets rather than written
# down. `sh.sysl.sdl3` reads SDL's own headers for its constants instead of transcribing them, so the
# include path is needed at *compile* time and not only at the link — which is the half a project
# used to `-l` flags forgets.
#
# **The two answers come from two different prefab modules, and `SDL3::SDL3` has neither.** The AAR
# publishes `SDL3`, `SDL3-Headers`, `SDL3-shared` and `SDL3_test`; `SDL3::SDL3` is the one to *link*
# against and carries its parts by dependency, and `get_target_property` reads a target's own
# property rather than its transitive ones — so asking it returns an empty string. The headers are
# `SDL3-Headers`, the library is `SDL3-shared`, and both are asked directly.
get_target_property(SDL3_INCLUDE_DIR SDL3::SDL3-Headers INTERFACE_INCLUDE_DIRECTORIES)
get_target_property(SDL3_SO SDL3::SDL3-shared IMPORTED_LOCATION)

# **An empty one of these is not a warning, it is a corrupt command line.** `--link-path` with no
# value takes the next word as its directory, so a blank here turned `-o` into a path and sysl
# refused an "Unknown argument" naming the archive — a message about the output that was really about
# SDL. Anything read out of a target gets checked before it is passed on.
if(NOT SDL3_INCLUDE_DIR)
    message(FATAL_ERROR "SDL3::SDL3-Headers has no INTERFACE_INCLUDE_DIRECTORIES — did prefab unpack the AAR? Run ./fetch-sdl3.sh")
endif()

if(NOT SDL3_SO)
    message(FATAL_ERROR "SDL3::SDL3-shared has no IMPORTED_LOCATION — did prefab unpack the AAR? Run ./fetch-sdl3.sh")
endif()

get_filename_component(SDL3_LIB_DIR ${SDL3_SO} DIRECTORY)

# **`--include-path sdl3=<dir>`, named rather than bare.** The package declares
# `requires { pkg_config { sdl3 = … } }`, and a cross build cannot run pkg-config for another
# machine — so sysl refuses and says to name the directory. A bare `--include-path` adds a search
# path without answering the requirement, and the build stops with the same message.
# **`ANDROID_NDK_ROOT` is set for this one command, and leaving it out is a real hazard rather than
# untidiness.** sysl finds the NDK from the environment and takes the *newest* it sees; AGP uses the
# `ndkVersion` pinned in `app/build.gradle.kts`. A machine with two installed — which is the normal
# state, since AGP downloads its own — would then have sysl compiling against one NDK's headers while
# CMake links with another's. Handing sysl the NDK CMake is already using makes them the same by
# construction, and `${ANDROID_NDK}` is set by the toolchain file, so nothing here has to name a
# version or a path.
add_custom_command(
    OUTPUT ${SYSL_ARCHIVE}
    COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK}
            sysl build-c ${SYSL_PROGRAM}
            --target ${SYSL_TARGET}
            --include-path sdl3=${SDL3_INCLUDE_DIR}
            --link-path ${SDL3_LIB_DIR}
            -o ${SYSL_ARCHIVE}
    DEPENDS ${SYSL_PROGRAM}/main.sysl ${SYSL_PROGRAM}/package.hocon
    COMMENT "Compiling the syslUI demo for ${SYSL_TARGET}"
    VERBATIM)

add_custom_target(sysl_archive DEPENDS ${SYSL_ARCHIVE})

# **The library must be called `main`.** `SDLActivity.getMainSharedObject()` defaults to `libmain.so`
# and `getMainFunction()` to `SDL_main`; both are overridable in Kotlin and neither is worth
# overriding. There is no C in this project, so the archive is what satisfies CMake's rule that a
# target have sources, and the linker language has to be stated because there is no `.c` to infer it
# from — the same two lines picokit needs, for the same reason.
add_library(main SHARED ${SYSL_ARCHIVE})

set_target_properties(main PROPERTIES LINKER_LANGUAGE C)

add_dependencies(main sysl_archive)

# **`-u SDL_main` is load-bearing and its absence is silent.** An archive member is pulled in only to
# resolve a symbol something already needs, and nothing in this `.so` calls `SDL_main` — Java looks
# it up by name at run time, long after the link. Without this the library builds, links, contains
# none of the program, and fails at `dlsym` with a message about a missing function rather than about
# an empty archive.
target_link_options(main PRIVATE -u SDL_main)

target_link_libraries(main PRIVATE ${SYSL_ARCHIVE} SDL3::SDL3 m)
