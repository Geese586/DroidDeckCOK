/* The handful of build-time facts PulseAudio 13's headers read, for compiling one module against
 * the upstream source tree without running its configure. The daemon and libraries the app ships
 * were built for the same target (Android arm64, bionic), so these match them. */
#ifndef DROIDDECK_PA_CONFIG_H
#define DROIDDECK_PA_CONFIG_H
#define PACKAGE "pulseaudio"
#define PACKAGE_NAME "pulseaudio"
#define PACKAGE_VERSION "13.0"
#define HAVE_ATOMIC_BUILTINS 1
#define HAVE_ATOMIC_BUILTINS_MEMORY_MODEL 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_FAST_64BIT_OPERATIONS 1
#define HAVE_SYS_RESOURCE_H 1
#define HAVE_MEMFD 1
#endif
