/* Host unit test of the patched PRoot socket.c with tracee memory/path mocks. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

/* Replace only PRoot's tracee/path dependencies; compile its actual socket code. */
#define SOCKET_H
#define TRACEE_H
#define TRACEE_MEM_H
#define BINDING_H
#define TEMP_H
#define PATH_H
#define ARCH_H
#define COMPAT_H
typedef uintptr_t word_t;
typedef struct { void *ctx; } Tracee;
typedef struct { int unused; } Binding;
static const char *host_path_fixture;
static const char *temp_path_fixture;
static int read_data(Tracee *t, void *out, word_t address, size_t size)
{ (void)t; memcpy(out, (void *)address, size); return 0; }
static int write_data(Tracee *t, word_t address, const void *in, size_t size)
{ (void)t; memcpy((void *)address, in, size); return 0; }
static word_t alloc_mem(Tracee *t, size_t size)
{ (void)t; return (word_t)calloc(1, size); }
static int translate_path(Tracee *t, char *out, int fd, const char *in, bool dereference)
{ (void)t; (void)fd; (void)in; (void)dereference; strcpy(out, host_path_fixture); return 0; }
static int detranslate_path(Tracee *t, char *out, void *binding)
{ (void)t; (void)binding; strcpy(out, "/tmp/claude-messaging-61001/12264.sock"); return 0; }
static char *create_temp_name(void *ctx, const char *prefix)
{ (void)ctx; (void)prefix; return strdup(temp_path_fixture); }
static Binding *insort_binding3(Tracee *t, void *ctx, const char *host, const char *guest)
{ (void)t; (void)ctx; (void)host; (void)guest; static Binding binding; return &binding; }
static void *talloc_reparent(void *old, void *new, void *ptr)
{ (void)old; (void)new; return ptr; }
static int peek_int32(Tracee *t, word_t address)
{ (void)t; errno = 0; return *(int *)address; }
static void poke_int32(Tracee *t, word_t address, int value)
{ (void)t; errno = 0; *(int *)address = value; }

#include SOCKET_SOURCE

static void test_bind_connect(const char *host)
{
  Tracee tracee = {0};
  struct sockaddr_un guest = {.sun_family = AF_UNIX};
  strcpy(guest.sun_path, "/tmp/claude-messaging-61001/12264.sock");
  host_path_fixture = host;
  word_t address = (word_t)&guest;
  int length = translate_socketcall_enter(&tracee, &address,
    offsetof(struct sockaddr_un, sun_path) + strlen(guest.sun_path) + 1);
  assert(length == (int)(offsetof(struct sockaddr_un, sun_path) + strlen(host) + 1));
  int listener = socket(AF_UNIX, SOCK_STREAM, 0);
  int client = socket(AF_UNIX, SOCK_STREAM, 0);
  assert(listener >= 0 && client >= 0);
  assert(bind(listener, (struct sockaddr *)address, length) == 0);
  assert(listen(listener, 1) == 0);
  free((void *)address);
  address = (word_t)&guest;
  length = translate_socketcall_enter(&tracee, &address, sizeof(guest));
  assert(connect(client, (struct sockaddr *)address, length) == 0);
  int accepted = accept(listener, NULL, NULL);
  assert(accepted >= 0);
  free((void *)address);
  close(accepted); close(client); close(listener);
  assert(unlink(host) == 0);
}

int main(void)
{
  char directory[] = "/tmp/horus-socket-XXXXXX";
  assert(mkdtemp(directory) != NULL);
  char host[PATH_MAX];
  snprintf(host, sizeof(host), "%s/12264.sock", directory);
  test_bind_connect(host);
  Tracee tracee = {0};
  struct sockaddr_un guest = {.sun_family = AF_UNIX};
  strcpy(guest.sun_path, "/tmp/socket");
  word_t address = (word_t)&guest;
  char long_path[PATH_MAX];
  memset(long_path, 'x', 200); long_path[200] = '\0';
  host_path_fixture = long_path;
  temp_path_fixture = "/tmp/horus-short-XXXXXX";
  int length = translate_socketcall_enter(&tracee, &address, sizeof(guest));
  assert(length > 0 && length <= (int)sizeof(guest));
  assert(strlen(((struct sockaddr_un *)address)->sun_path) < 108);
  free((void *)address);
  temp_path_fixture = long_path;
  address = (word_t)&guest;
  assert(translate_socketcall_enter(&tracee, &address, sizeof(guest)) == -EINVAL);
  guest.sun_path[0] = '\0'; /* Abstract socket addresses must remain untouched. */
  assert(translate_socketcall_enter(&tracee, &address, sizeof(guest)) == 0);
  assert(address == (word_t)&guest);
  guest.sun_family = AF_INET;
  assert(translate_socketcall_enter(&tracee, &address, sizeof(guest)) == 0);
  assert(rmdir(directory) == 0);
  puts("Unix socket translation: bind/connect, addrlen, fallback and abstract addresses passed");
  return 0;
}
