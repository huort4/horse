#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
cp -R "$repo_root/native/proot/src" "$test_dir/src"
git -C "$test_dir" init -q
git -C "$test_dir" apply --recount "$repo_root/native/patches/proot-android.patch"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -I"$test_dir/src" \
  -DSOCKET_SOURCE="\"$test_dir/src/syscall/socket.c\"" \
  "$repo_root/native/tests/unix_socket_translation_test.c" -o "$test_dir/test"
"$test_dir/test"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -I"$test_dir/src" \
  "$repo_root/native/tests/proot_exit_status_test.c" -o "$test_dir/exit-test"
"$test_dir/exit-test"
# Compile the exact lock helper from the patched PRoot source.
sed -n '/^static int lock_l2s_directory(/,/^static int open_l2s_directory(/p' \
  "$test_dir/src/extension/link2symlink/link2symlink.c" | sed '$d' > "$test_dir/l2s-lock.h"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror \
  -DL2S_LOCK_SOURCE="\"$test_dir/l2s-lock.h\"" \
  "$repo_root/native/tests/hardlink_storage_lock_test.c" -o "$test_dir/lock-test"
mkdir "$test_dir/backing"
"$test_dir/lock-test" "$test_dir/backing"
