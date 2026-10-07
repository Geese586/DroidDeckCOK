/*
 * The free space a library reports.
 *
 * A library's games can live somewhere the folder that names the library does not. The card
 * library is exactly that: its steamapps/common is bound to the card, but the directory above it
 * belongs to the runtime image in the phone's own storage, and that directory is what the client
 * measures. So it offers both install locations with the phone's free space against each, and the
 * card's real figure - seventeen gigabytes more, on the device this was found on - never appears.
 *
 * The number is not only cosmetic: the client refuses an install it believes will not fit. Once
 * the phone fills up it would turn down a card install with room to spare, and say nothing about
 * why.
 *
 * Moving the whole library onto the card would fix the number and break something worse. The
 * prefixes under steamapps/compatdata want symlinks and file locks, and the card is served over
 * FUSE, which gives us neither - that is why they are kept in the runtime image in the first
 * place. So the question is answered where the content actually is: a query about a library root
 * named in BL_LIBRARY_SPACE is served from its steamapps/common, the bind that points at the
 * card. Every other path is passed through untouched.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>

/* A library root, or its steamapps, resolved to the directory that holds the games. Returns the
 * rewritten path in `buf`, or NULL when `path` is not one of ours and should be left alone. */
static const char *content_of(const char *path, char *buf, size_t len) {
  const char *roots = getenv("BL_LIBRARY_SPACE");
  if (!path || !roots || !*roots)
    return NULL;

  size_t path_len = strlen(path);
  while (*roots) {
    const char *end = strchr(roots, ':');
    size_t root_len = end ? (size_t)(end - roots) : strlen(roots);
    if (root_len > 0) {
      /* The root itself, or the steamapps directly beneath it. Anything deeper is already inside
       * a bind and answers for itself. */
      int match = (path_len == root_len && !strncmp(path, roots, root_len)) ||
                  (path_len == root_len + 10 && !strncmp(path, roots, root_len) &&
                   !strcmp(path + root_len, "/steamapps"));
      if (match) {
        if (root_len + sizeof("/steamapps/common") > len)
          return NULL;
        memcpy(buf, roots, root_len);
        strcpy(buf + root_len, "/steamapps/common");
        return buf;
      }
    }
    if (!end)
      break;
    roots = end + 1;
  }
  return NULL;
}

#define FORWARD(name, type)                                                    \
  int name(const char *path, type *out) {                                      \
    static int (*real)(const char *, type *);                                  \
    char buf[4096];                                                            \
    const char *content;                                                       \
    if (!real)                                                                 \
      real = dlsym(RTLD_NEXT, #name);                                          \
    if (!real)                                                                 \
      return -1;                                                               \
    content = content_of(path, buf, sizeof(buf));                              \
    return real(content ? content : path, out);                                \
  }

FORWARD(statfs, struct statfs)
FORWARD(statvfs, struct statvfs)
#ifdef __USE_LARGEFILE64
FORWARD(statfs64, struct statfs64)
FORWARD(statvfs64, struct statvfs64)
#endif
