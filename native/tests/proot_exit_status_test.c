#include <assert.h>
#include <signal.h>
#include <stdio.h>
#include "tracee/exit_status.h"

int main(void)
{
  ProotExitStatus status = { -1, -1 };
  record_proot_exit_status(&status, 2, 0); /* A successful startup helper. */
  record_proot_exit_status(&status, 1, SIGKILL);
  assert(get_proot_exit_status(&status) == 137);
  record_proot_exit_status(&status, 3, 0); /* Later helper exits cannot erase the kill. */
  assert(get_proot_exit_status(&status) == 137);
  status = (ProotExitStatus){ -1, -1 };
  record_proot_exit_status(&status, 2, SIGKILL); /* A child kill is not the shell's death. */
  record_proot_exit_status(&status, 1, 0);
  record_proot_exit_status(&status, 3, 23 << 8);
  assert(get_proot_exit_status(&status) == 0);
  status = (ProotExitStatus){ -1, -1 };
  record_proot_exit_status(&status, 1, 23 << 8);
  record_proot_exit_status(&status, 2, 0);
  assert(get_proot_exit_status(&status) == 23);
  record_proot_exit_status(&status, 1, (SIGSTOP << 8) | 0x7f);
  assert(get_proot_exit_status(&status) == 23);
  puts("PRoot exit status: leader SIGKILL, normal/failing exits and helper statuses passed");
  return 0;
}
