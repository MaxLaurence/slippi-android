include(FetchContent)
remove_definitions(-std=c++1y)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(XZ_NLS OFF CACHE BOOL "" FORCE)
set(XZ_ENCODERS "lzma1;lzma2" CACHE STRING "" FORCE)
set(XZ_DECODERS "lzma1;lzma2" CACHE STRING "" FORCE)
set(XZ_MICROLZMA_ENCODER OFF CACHE BOOL "" FORCE)
set(XZ_MICROLZMA_DECODER OFF CACHE BOOL "" FORCE)
set(XZ_LZIP_DECODER OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_XZ OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_XZDEC OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_LZMADEC OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_LZMAINFO OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_SCRIPTS OFF CACHE BOOL "" FORCE)
set(XZ_DOC OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  xz
  GIT_REPOSITORY https://github.com/tukaani-project/xz.git
  GIT_TAG v5.8.3
  GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(xz)

FetchContent_Declare(
  xdelta3
  GIT_REPOSITORY https://github.com/jmacd/xdelta.git
  GIT_TAG v3.1.0
  GIT_SHALLOW TRUE)
FetchContent_GetProperties(xdelta3)
if(NOT xdelta3_POPULATED)
  FetchContent_Populate(xdelta3)
endif()
set(XDELTA3_SOURCE ${xdelta3_SOURCE_DIR}/xdelta3/xdelta3.c)
set_source_files_properties(${XDELTA3_SOURCE} PROPERTIES
  COMPILE_DEFINITIONS "XD3_MAIN=1;NOT_MAIN=1;XD3_ENCODER=1;HAVE_LZMA_H=1;SECONDARY_LZMA=1;XD3_DEBUG=0;XD3_USE_LARGEFILE64=1;XD3_USE_LARGESIZET=1;SIZEOF_SIZE_T=8;SIZEOF_UNSIGNED_INT=4;SIZEOF_UNSIGNED_LONG=8;SIZEOF_UNSIGNED_LONG_LONG=8;XD3_POSIX=1;XD3_STDIO=0;XD3_WIN32=0")

list(APPEND LIBS core uicommon)

set(SRCS ButtonManager.cpp
         MainAndroid.cpp
         TrainingModePatcher.cpp
         ${XDELTA3_SOURCE})

set(SHARED_LIB main)
add_library(${SHARED_LIB} SHARED ${SRCS})
if(ANDROID AND _M_ARM_64)
  add_dependencies(${SHARED_LIB} main_hook file_redirect_hook hook_impl gsl_alloc_hook)
endif()
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
liblzma
)
target_include_directories(${SHARED_LIB} PRIVATE
  ${xdelta3_SOURCE_DIR}/xdelta3
  ${xz_SOURCE_DIR}/src/liblzma/api)

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

# Playback gecko codes ini files. PC Dolphin ships two flavors of
# Sys/GameSettings/GALE01r2.ini: a netplay one (Slippi Online + Recording)
# in Data/Sys, and a playback one (Slippi Playback + Recording) in
# Data/PlaybackGeckoCodes. The playback Dolphin build packages the playback
# flavor where Sys/GameSettings/ would normally be. Our Android build is
# always the netplay flavor (so live netplay works), and at replay-launch
# time we write the playback flavor as a user-dir override
# (${UserDir}/GameSettings/) which SConfig::LoadGameIni reads with higher
# priority than the bundled Sys copy. The Java side reads these from
# assets/PlaybackGeckoCodes/.
file(REMOVE_RECURSE ${CMAKE_SOURCE_DIR}/Source/Android/app/src/main/assets/PlaybackGeckoCodes)
file(COPY ${CMAKE_SOURCE_DIR}/Data/PlaybackGeckoCodes
     DESTINATION ${CMAKE_SOURCE_DIR}/Source/Android/app/src/main/assets/)

set(CPACK_PACKAGE_EXECUTABLES ${CPACK_PACKAGE_EXECUTABLES} ${SHARED_LIB})
