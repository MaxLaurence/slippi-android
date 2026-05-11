list(APPEND LIBS core uicommon)

set(SRCS ButtonManager.cpp
         MainAndroid.cpp)

set(SHARED_LIB main)
add_library(${SHARED_LIB} SHARED ${SRCS})
target_link_libraries(${SHARED_LIB}
log
android
EGL
vulkan
"-Wl,--no-warn-mismatch"
"-Wl,--whole-archive"
${LIBS}
"-Wl,--no-whole-archive"
slippi_rust_extensions
)

# Mirror Data/Sys into the app's assets/ so the C++ core can read its
# defaults at runtime. The UserDirectoryBootstrap in Java copies this tree
# out of assets into the user's private files dir on first launch.
#
# Critical contents:
#   - codehandler.bin / bootloader.gct: the runtime that *applies* Gecko
#     codes to PowerPC memory. Without these, codes load but never patch.
#   - GameFiles/GALE01/: Slippi's modded Melee game files (slpCSS.dat is the
#     Online menu CSS replacement) — without these, the title screen never
#     auto-advances and the Main Menu has no "Online" entry.
#   - Slippi/InjectionLists/: runtime patch lists read by the Rust EXI
#     extensions for matchmaking-time injections.
file(REMOVE_RECURSE ${CMAKE_SOURCE_DIR}/Source/Android/app/src/main/assets/Sys)
file(MAKE_DIRECTORY ${CMAKE_SOURCE_DIR}/Source/Android/app/src/main/assets/Sys)
file(COPY ${CMAKE_SOURCE_DIR}/Data/Sys/GameSettings
          ${CMAKE_SOURCE_DIR}/Data/Sys/GameFiles
          ${CMAKE_SOURCE_DIR}/Data/Sys/Slippi
          ${CMAKE_SOURCE_DIR}/Data/Sys/GC
          ${CMAKE_SOURCE_DIR}/Data/Sys/Wii
          ${CMAKE_SOURCE_DIR}/Data/Sys/Shaders
          ${CMAKE_SOURCE_DIR}/Data/Sys/Resources
          ${CMAKE_SOURCE_DIR}/Data/Sys/codehandler.bin
          ${CMAKE_SOURCE_DIR}/Data/Sys/bootloader.gct
     DESTINATION ${CMAKE_SOURCE_DIR}/Source/Android/app/src/main/assets/Sys/)

set(CPACK_PACKAGE_EXECUTABLES ${CPACK_PACKAGE_EXECUTABLES} ${SHARED_LIB})
