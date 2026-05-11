# OUR SOURCES
set(SRCS
  png.h
  pngconf.h
  png.c
  pngerror.c
  pngget.c
  pngmem.c
  pngpread.c
  pngread.c
  pngrio.c
  pngrtran.c
  pngrutil.c
  pngset.c
  pngtrans.c
  pngwio.c
  pngwrite.c
  pngwtran.c
  pngwutil.c
)
add_definitions(-Wno-self-assign)
# The arm/*.c NEON implementation isn't vendored in this Externals copy,
# so explicitly disable NEON optimizations to avoid the linker complaining
# about png_init_filter_functions_neon on aarch64 builds (Android/Thor).
add_definitions(-DPNG_ARM_NEON_OPT=0)
add_library(png STATIC ${SRCS})
