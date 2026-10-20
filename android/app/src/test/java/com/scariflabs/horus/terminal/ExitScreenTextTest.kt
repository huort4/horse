package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitScreenTextTest {
  @Test
  fun `ordinary github failures remain command errors after terminal soft wrapping`() {
    for (message in listOf("HTTP 401: Bad credentials", "? Hostname: invalid-host", "Cloning into '/workspace/project'...",
      "proot error: can't create temporary file: No space left on device")) {
      val columns = 16
      val text = exitScreenText(message.chunked(columns).map { row(it, columns) }, maxLines = 40)
      assertEquals(TerminalSessionContract.AppExitKind.COMMAND_FAILED,
        TerminalSessionContract.appExitKind("github", TerminalSessionContract.ExitStatus(1, null), text))
    }
    assertEquals(TerminalSessionContract.AppExitKind.INTERRUPTED,
      TerminalSessionContract.appExitKind("github", TerminalSessionContract.ExitStatus(130, null), "Cloning into 'project'..."))
  }

  @Test
  fun `signals and runtime faults retain unexpected classification`() {
    for (status in listOf(TerminalSessionContract.ExitStatus(null, "sigkill"), TerminalSessionContract.ExitStatus(137, null))) {
      assertEquals(TerminalSessionContract.AppExitKind.UNEXPECTED,
        TerminalSessionContract.appExitKind("shell", status, "build failed"))
    }
    for (message in listOf("panic: bad state", "proot error: ptrace failed", "unexpected escape sequence")) {
      assertEquals(TerminalSessionContract.AppExitKind.UNEXPECTED,
        TerminalSessionContract.appExitKind("github", TerminalSessionContract.ExitStatus(1, null), message))
    }
    assertEquals(TerminalSessionContract.AppExitKind.CLOSED,
      TerminalSessionContract.appExitKind("shell", TerminalSessionContract.ExitStatus(0, null), "build failed\nexit"))
  }

  private fun row(text: String, columns: Int = text.length): Array<String> =
    Array(columns) { index -> text.getOrNull(index)?.toString() ?: " " }

  @Test
  fun `text the terminal soft-wrapped reads as one line`() {
    val rows = listOf(
      row("? How would you like to authenticate GitHub CLI? Login w"),
      row("ith a web browser", 60),
      row("check your internet connection or https://githubstatus.c"),
      row("om", 60),
    )
    assertEquals(
      "? How would you like to authenticate GitHub CLI? Login with a web browser\n" +
        "check your internet connection or https://githubstatus.com",
      exitScreenText(rows, maxLines = 40),
    )
  }

  @Test
  fun `rows that end short, in a space, or in a border stay separate`() {
    val rows = listOf(
      row("short line", 16),
      row("next", 16),
      row("ends in space   ", 16),
      row("x", 16),
      row("────────────────", 16),
      row("> prompt", 16),
    )
    assertEquals("short line\nnext\nends in space\nx\n────────────────\n> prompt", exitScreenText(rows, maxLines = 40))
  }

  @Test
  fun `a wide character in the last column still continues the line`() {
    val wide = row("abcdefg", 9).also { it[7] = "界"; it[8] = "" }
    assertEquals("abcdefg界z", exitScreenText(listOf(wide, row("z", 9)), maxLines = 40))
  }

  @Test
  fun `trailing blank rows are dropped and only the last lines are kept`() {
    val rows = listOf(row("one", 8), row("two", 8), row("three", 8), row("", 8), row("", 8))
    assertEquals("two\nthree", exitScreenText(rows, maxLines = 2))
  }
}
