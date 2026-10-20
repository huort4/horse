#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include L2S_LOCK_SOURCE

int main(int argc, char **argv)
{
    assert(argc == 2);
    int directory = open(argv[1], O_RDONLY | O_DIRECTORY);
    assert(directory >= 0);
    int shared = lock_l2s_directory(directory);
    assert(shared >= 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        int fd = openat(directory, ".gc.lock", O_RDWR);
        struct flock lock = { .l_type = F_WRLCK, .l_whence = SEEK_SET };
        assert(fcntl(fd, F_SETLK, &lock) == -1);
        assert(errno == EACCES || errno == EAGAIN);
        int second_session = lock_l2s_directory(directory);
        assert(second_session >= 0);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child && status == 0);
    close(shared);
    int exclusive = openat(directory, ".gc.lock", O_RDWR);
    struct flock lock = { .l_type = F_WRLCK, .l_whence = SEEK_SET };
    assert(fcntl(exclusive, F_SETLK, &lock) == 0);
    close(exclusive);
    assert(unlinkat(directory, ".gc.lock", 0) == 0);
    assert(symlinkat("/tmp", directory, ".gc.lock") == 0);
    assert(lock_l2s_directory(directory) == -ELOOP);
    puts("hardlink storage: shared sessions, exclusive cleanup, release and no-follow passed");
    return 0;
}
