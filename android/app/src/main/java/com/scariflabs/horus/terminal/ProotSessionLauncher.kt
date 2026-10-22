package com.scariflabs.horus.terminal

import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * Launch policy for interactive guest sessions. Builds the
 * same fixed-argument-vector PRoot invocation the Phase 1 prober proved on
 * the device, extended with the interactive shell: no shell-string
 * concatenation anywhere, and the child environment is assembled
 * from scratch so no Android or host-shell variable leaks into the guest.
 *
 * The launcher binds only the guest rootfs, persistent home, explicit
 * app-private workspace, and the host /proc view required by process-aware
 * interactive tools such as `top`. If Android denies a global proc file that
 * BusyBox top requires, only that file gets a synthetic compatibility bind;
 * the live per-process directories remain from the host /proc view. The small
 * set of standard character devices required by Alpine tools is bound
 * explicitly; /sys and the rest of /dev remain unbound.
 */
class ProotSessionLauncher(
  private val runtime: ProotRuntimeLocator.LocatedRuntime,
  private val scratchDir: File,
  private val hostProcDir: File = File(HOST_PROC),
  private val hostDevDir: File = File(HOST_DEV),
  private val dnsServersProvider: () -> List<String> = { emptyList() },
  /** Boot time in Unix seconds, for the `btime` line of the stand-in /proc/stat. */
  private val bootTimeSeconds: () -> Long = { DEVICE_BOOT_TIME_SECONDS },
  /**
   * Internal diagnostic switch. When false (the default) PRoot runs with
   * PROOT_NO_SECCOMP=1; when true the variable is omitted so PRoot may use
   * its seccomp-filter acceleration. Only the seccomp device probe sets it.
   */
  private val seccompAcceleration: Boolean = false,
  /** Alpine mirror and npm registry for package installs inside the guest. */
  private val downloadSources: DownloadSources = DownloadSources(),
  private val hardlinkDir: File = File(scratchDir.absoluteFile.parentFile, "l"),
) {


  data class LaunchSpec(
    val argv: List<String>,
    val environment: List<String>,
    val workingDirectory: File?,
  )

  /**
   * Launches `/bin/sh -l` (login shell) inside the guest rootfs with the
   * deliberate guest environment: HOME, PWD, XDG paths, TERM, PATH, and LANG
   * are set here and nowhere else.
   */
  fun interactiveShellLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File? = null,
    guestUsername: String = GUEST_ROOT_USER,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    term: String = DEFAULT_TERM,
    sessionCommand: String? = null,
  ): LaunchSpec = buildLaunchSpec(
    rootfsDir = rootfsDir,
    guestHomeDir = guestHomeDir,
    workspaceDir = workspaceDir,
    guestUsername = guestUsername,
    guestUid = guestUid,
    guestGid = guestGid,
    term = term,
    // Keep the bootstrap shell non-interactive so it cannot print its own
    // prompt before zsh is ready. The selected launcher is an argv argument,
    // not a line typed into the PTY, so it can never echo into the terminal.
    command = if (sessionCommand == null) {
      listOf(GUEST_SHELL, "-c", "exec zsh -l")
    } else {
      require(sessionCommand.isNotEmpty() && sessionCommand.length <= MAX_SESSION_COMMAND_LENGTH) {
        "session command is invalid"
      }
      listOf(
        GUEST_SHELL,
        "-c",
        "stty echo 2>/dev/null || true; unset HORUS_BOOTSTRAP; exec ${GUEST_SHELL} -c \"${'$'}1\"",
        "horus-session",
        sessionCommand,
      )
    },
  )

  /** Runs the requested shell or harness bootstrap as root before its session. */
  fun toolchainProvisionLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    target: String,
    term: String = DEFAULT_TERM,
  ): LaunchSpec = buildLaunchSpec(
    rootfsDir = rootfsDir,
    guestHomeDir = guestHomeDir,
    workspaceDir = null,
    guestUsername = GUEST_ROOT_USER,
    term = term,
    command = listOf(GUEST_SHELL, "-c", TOOLCHAIN_PROVISION_SCRIPT),
    toolchainTarget = target,
  )

  /**
   * Runs the idempotent target installer in the visible PTY, then hands the
   * same terminal to the configured Alpine user. The persistent home is
   * mounted at both /root and the user's home while the installer is root so
   * the resulting CLI payload is immediately available to the user session.
   */
  fun toolchainSessionLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File? = null,
    guestUsername: String = GUEST_ROOT_USER,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    target: String,
    term: String = DEFAULT_TERM,
    sessionCommand: String? = null,
    provision: Boolean = true,
    /**
     * Starts the root helper behind `sudo` before the session drops to its
     * user. Only the profile user's own sessions get it, never a harness's.
     */
    rootHelper: Boolean = false,
  ): LaunchSpec {
    require(TerminalRuntimeContract.isValidToolchainTarget(target)) { "toolchain target is invalid" }
    val command = sessionCommand ?: "exec zsh -l"
    require(command.isNotEmpty() && command.length <= MAX_SESSION_COMMAND_LENGTH) {
      "session command is invalid"
    }
    val guestHomePath = if (guestUsername == GUEST_ROOT_USER) {
      GUEST_HOME
    } else {
      "/home/$guestUsername"
    }
    val sessionWorkingDirectory = if (workspaceDir == null) guestHomePath else "/workspace"
    if (rootHelper) installRootHelper(rootfsDir)
    val launch = buildLaunchSpec(
      rootfsDir = rootfsDir,
      guestHomeDir = guestHomeDir,
      workspaceDir = workspaceDir,
      guestUsername = guestUsername,
      term = term,
      command = listOf(
        GUEST_SHELL,
        "-c",
        TOOLCHAIN_SESSION_SCRIPT,
        "horus-toolchain-session",
        guestUsername,
        guestUid.toString(),
        guestGid.toString(),
        command,
        sessionWorkingDirectory,
      ),
      toolchainTarget = target,
      guestUid = guestUid,
      guestGid = guestGid,
      forceRootIdentity = true,
      // sudo runs commands on their own pseudo-terminal, as SSH logins do.
      extraDeviceBinds = if (rootHelper) ptyDeviceBinds() else emptyList(),
    )
    val environment = buildList {
      addAll(launch.environment)
      if (rootHelper) add("HORUS_ROOT_HELPER=1")
      if (!provision) add("HORUS_SKIP_TOOLCHAIN_PROVISION=1")
    }
    return launch.copy(environment = environment)
  }

  /**
   * Runs the opt-in USB SSH server. Root installs dropbear and prepares the
   * host key, then the server drops to the profile user, so that is the only
   * account a paired computer can log in as. The server binds the phone's
   * loopback only; computers reach it through `adb forward`.
   */
  fun remoteAccessLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File?,
    guestUsername: String,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    port: Int = RemoteAccess.PORT,
    /** App-private directory holding the phone's one host key; null keeps it in the user's home. */
    hostKeyDir: File? = null,
  ): LaunchSpec {
    require(guestUsername != GUEST_ROOT_USER) { "remote access needs a non-root profile user" }
    require(hostKeyDir == null || (hostKeyDir.isDirectory && !Files.isSymbolicLink(hostKeyDir.toPath()))) {
      "remote access host key directory is unavailable"
    }
    require(port in 1025..65535) { "remote access port is invalid" }
    installRootHelper(rootfsDir)
    return buildLaunchSpec(
      rootfsDir = rootfsDir,
      guestHomeDir = guestHomeDir,
      workspaceDir = workspaceDir,
      guestUsername = guestUsername,
      guestUid = guestUid,
      guestGid = guestGid,
      term = DEFAULT_TERM,
      command = listOf(
        GUEST_SHELL,
        "-c",
        REMOTE_ACCESS_SCRIPT,
        "horus-remote-access",
        guestUsername,
        guestUid.toString(),
        guestGid.toString(),
        port.toString(),
        if (hostKeyDir == null) "" else RemoteAccess.GUEST_HOST_KEY_DIR,
      ),
      forceRootIdentity = true,
      // Root's own /root stays the rootfs directory, so logins see their
      // home only as /home/<user>.
      bindHomeAtRoot = false,
      // SSH logins allocate their own pseudo-terminals inside the guest.
      extraDeviceBinds = ptyDeviceBinds(),
      extraBinds = listOfNotNull(hostKeyDir?.let { it to RemoteAccess.GUEST_HOST_KEY_DIR }),
    )
  }

  private fun buildLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File?,
    guestUsername: String,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    term: String,
    command: List<String>,
    toolchainTarget: String? = null,
    forceRootIdentity: Boolean = false,
    extraDeviceBinds: List<Pair<File, String>> = emptyList(),
    extraBinds: List<Pair<File, String>> = emptyList(),
    bindHomeAtRoot: Boolean = true,
  ): LaunchSpec {
    require(rootfsDir.isDirectory) { "rootfs directory is missing" }
    require(guestHomeDir.isDirectory) { "guest home directory is missing" }
    if (workspaceDir != null) {
      require(workspaceDir.isDirectory) { "workspace directory is missing" }
    }
    require(guestUsername == GUEST_ROOT_USER || USERNAME_PATTERN.matches(guestUsername)) {
      "guest username is invalid"
    }
    require(guestUid in 1..MAX_GUEST_ID && guestGid in 1..MAX_GUEST_ID) {
      "guest uid or gid is invalid"
    }
    if (toolchainTarget != null) {
      require(TerminalRuntimeContract.isValidToolchainTarget(toolchainTarget)) { "toolchain target is invalid" }
    }
    val requestedRoot = guestUsername == GUEST_ROOT_USER
    val launchAsRoot = forceRootIdentity || requestedRoot
    val guestHomePath = if (requestedRoot) GUEST_HOME else "/home/$guestUsername"
    val launchHomePath = if (launchAsRoot) GUEST_HOME else guestHomePath
    if (!requestedRoot) prepareGuestAccount(rootfsDir, guestUsername, guestUid, guestGid)
    if (!scratchDir.exists() && !scratchDir.mkdirs() && !scratchDir.isDirectory) {
      throw IllegalStateException("cannot create proot scratch directory")
    }
    require(!Files.isSymbolicLink(scratchDir.toPath())) { "proot scratch directory is a symbolic link" }
    // This is the guest /tmp for every rootfs and user, so give it the
    // rootfs tarball's 1777. The app-private parent still keeps it private.
    runCatching { Os.chmod(scratchDir.absolutePath, "1777".toInt(8)) }
    DistroStoreCore.pruneProotTemps(scratchDir, hostProcDir)
    require(!Files.isSymbolicLink(hardlinkDir.toPath())) { "proot hardlink directory is a symbolic link" }
    if (!hardlinkDir.isDirectory && !hardlinkDir.mkdirs() && !hardlinkDir.isDirectory) {
      throw IllegalStateException("cannot create proot hardlink directory")
    }
    prepareResolverFile(rootfsDir)
    prepareZshProfile(guestHomeDir)
    val rootfsPath = rootfsDir.canonicalFile.absolutePath
    val homePath = guestHomeDir.canonicalFile.absolutePath
    val workspacePath = workspaceDir?.canonicalFile?.absolutePath
    val deviceBinds = prepareDeviceBinds()
    val procCompatibilityBinds = prepareProcCompatibilityBinds()
    val homeGuestPaths = if (forceRootIdentity && !requestedRoot && !bindHomeAtRoot) {
      listOf(guestHomePath)
    } else if (forceRootIdentity && !requestedRoot) {
      listOf(GUEST_HOME, guestHomePath)
    } else {
      listOf(launchHomePath)
    }
    val guestPath = if (launchAsRoot) GUEST_PATH else "$guestHomePath/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    val argv = buildList {
      add(runtime.prootBin.absolutePath)
      if (launchAsRoot) add("-0")
      add("-r")
      add(rootfsPath)
      // Android's SELinux policy denies link(2) on app data, so emulate guest
      // hard links (apk installs e.g. zsh as one) with PRoot's symlink scheme.
      add("--link2symlink")
      if (!launchAsRoot) {
        add("-i")
        add("$guestUid:$guestGid")
      }
      homeGuestPaths.forEach { path ->
        add("-b")
        add("$homePath:$path")
      }
      if (workspacePath != null) {
        add("-b")
        add("$workspacePath:/workspace")
      }
      extraBinds.forEach { (source, guestPath) ->
        add("-b")
        add("${source.canonicalFile.absolutePath}:$guestPath")
      }
      add("-b")
      add("${scratchDir.canonicalPath}:/tmp")
      // Emulated links contain absolute host backing paths. Map only this
      // backing directory at the same path so apk/Node can stat those links.
      add("-b")
      add("${hardlinkDir.canonicalPath}:${hardlinkDir.canonicalPath}")
      deviceBinds.forEach { bind ->
        add("-b")
        add("${bind.source.canonicalFile.absolutePath}:${bind.guestPath}")
      }
      extraDeviceBinds.forEach { (source, guestPath) ->
        require(source.exists()) { "required host device is missing: ${source.absolutePath}" }
        add("-b")
        add("${source.absolutePath}:$guestPath")
      }
      // BusyBox top needs a live procfs for its per-process scan. PRoot
      // translates the host view through this bind; only denied global files
      // are overlaid below, so process directories remain live.
      add("-b")
      add("${hostProcDir.canonicalFile.absolutePath}:$GUEST_PROC")
      procCompatibilityBinds.forEach { bind ->
        add("-b")
        add("${bind.source.canonicalFile.absolutePath}:${bind.guestPath}")
      }
      add("-w")
      add(if (workspacePath == null) launchHomePath else "/workspace")
      addAll(command)
    }
    val environment = buildList {
      add("PROOT_LOADER=${runtime.loaderBin.absolutePath}")
      add("PROOT_LOADER_32=${runtime.loaderBin.absolutePath}")
      add("PROOT_TMP_DIR=${scratchDir.canonicalFile.absolutePath}")
      // Backing files beside the source keep a package directory nonempty
      // while another alias still exists. Keep them outside replaceable trees.
      add("PROOT_L2S_DIR=${hardlinkDir.canonicalPath}")
      // Keep the stable ptrace path on the supported Android API. PRoot's
      // seccomp acceleration crashes this device before the guest shell opens.
      if (!seccompAcceleration) add("PROOT_NO_SECCOMP=1")
      // Android PRoot exposes a live /proc, but cannot complete apk's
      // O_TMPFILE linkat commit through /proc/self/fd. Report that one probe
      // as unavailable so apk uses its named-temp/rename fallback instead.
      add("PROOT_NO_O_TMPFILE=1")
      add("LD_LIBRARY_PATH=${runtime.libraryDir.absolutePath}")
      add("HOME=$launchHomePath")
      add("PWD=${if (workspacePath == null) launchHomePath else "/workspace"}")
      add("XDG_CONFIG_HOME=$launchHomePath/.config")
      add("XDG_DATA_HOME=$launchHomePath/.local/share")
      add("XDG_CACHE_HOME=$launchHomePath/.cache")
      add("TERM=$term")
      toolchainTarget?.let { add("HORUS_TOOLCHAIN_TARGET=$it") }
      // Do not shadow Alpine's real BusyBox applets: both `top` and
      // `/bin/busybox top` must reach the guest binary with their argv intact.
      add("PATH=$guestPath")
      add("LANG=$GUEST_LANG")
      // Bound CoreCLR's GC reservations on Android ARM64's limited VA space.
      add("DOTNET_gcServer=0")
      add("DOTNET_GCHeapHardLimit=0x40000000")
      add("DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER=1")
      // SDK server opt-out and Roslyn shared compilation are separate knobs.
      // A detached VBCSCompiler otherwise keeps PRoot alive after shell exit.
      add("DOTNET_CLI_USE_MSBUILD_SERVER=0")
      add("UseSharedCompilation=false")
      add("TZ=${GuestTimeZone.forGuest(rootfsDir)}")
      addAll(downloadSources.guestEnvironment())
    }
    return LaunchSpec(
      argv = argv,
      environment = environment,
      // The host working directory is inherited deliberately, exactly like
      // the Phase 1 prober; the guest sees the selected bind through PRoot's -w.
      workingDirectory = null,
    )
  }

  private fun ptyDeviceBinds(): List<Pair<File, String>> =
    listOf(File(hostDevDir, "ptmx") to "$GUEST_DEV/ptmx", File(hostDevDir, "pts") to "$GUEST_DEV/pts")

  /** Writes `sudo` and its root helper into the rootfs. */
  private fun installRootHelper(rootfsDir: File) {
    ROOT_HELPER_FILES.forEach { (resource, guestPath) ->
      val content = requireNotNull(ProotSessionLauncher::class.java.getResourceAsStream("/com/scariflabs/horus/terminal/guest/$resource")) {
        "missing guest resource $resource"
      }.use { it.readBytes() }
      writeGuestFile(rootfsDir, guestPath, content, executable = true)
    }
  }

  /** Writes a file into the rootfs, refusing to follow symlinks on the way. */
  private fun writeGuestFile(rootfsDir: File, guestPath: String, content: ByteArray, executable: Boolean) {
    var directory = rootfsDir
    guestPath.trim('/').split('/').dropLast(1).forEach { part ->
      directory = File(directory, part)
      if (Files.isSymbolicLink(directory.toPath())) {
        throw IllegalStateException("guest path $guestPath goes through a symlink")
      }
      if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) {
        throw IllegalStateException("cannot create guest directory for $guestPath")
      }
    }
    val target = File(rootfsDir, guestPath.trim('/'))
    if (!Files.isSymbolicLink(target.toPath()) && target.isFile && (!executable || target.canExecute()) &&
      target.readBytes().contentEquals(content)
    ) {
      return
    }
    var temporary: File? = null
    try {
      temporary = File.createTempFile("${target.name}-", ".tmp", directory)
      temporary.writeBytes(content)
      temporary.setReadable(true, false)
      if (executable) temporary.setExecutable(true, false)
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary?.takeIf { it.exists() }?.delete()
    }
  }

  private fun prepareDeviceBinds(): List<DeviceBind> {
    require(hostDevDir.isDirectory) { "host dev directory is missing" }
    return DEVICE_FILES.map { name ->
      val source = File(hostDevDir, name)
      require(source.exists()) { "required host device is missing: ${source.absolutePath}" }
      DeviceBind(source, "$GUEST_DEV/$name")
    }
  }

  private fun prepareProcCompatibilityBinds(): List<ProcCompatibilityBind> {
    require(hostProcDir.isDirectory) { "host proc directory is missing" }
    var compatibilityDir: File? = null
    return procGlobalFiles(bootTimeSeconds()).mapNotNull { (name, content) ->
      val hostFile = File(hostProcDir, name)
      if (isReadable(hostFile)) return@mapNotNull null

      val directory = compatibilityDir ?: File(scratchDir, PROC_COMPATIBILITY_DIR).also {
        if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
          throw IllegalStateException("cannot create proc compatibility directory")
        }
        compatibilityDir = it
      }
      val fallback = File(directory, name)
      if (!fallback.exists() || fallback.readText() != content) {
        var temporary: File? = null
        try {
          temporary = File.createTempFile("$name-", ".tmp", directory)
          temporary.writeText(content)
          Files.move(
            temporary.toPath(),
            fallback.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } finally {
          temporary?.takeIf { it.exists() }?.delete()
        }
      }
      ProcCompatibilityBind(fallback, "$GUEST_PROC/$name")
    }
  }

  /**
   * Alpine minirootfs does not ship a resolver file. Android's resolver is
   * exposed through ConnectivityManager rather than a host `/etc/resolv.conf`,
   * so materialize only the current DNS addresses into the app-owned guest
   * rootfs. An empty provider deliberately leaves the guest file untouched
   * for JVM/unit-test launchers that do not model Android networking.
   */
  private fun prepareResolverFile(rootfsDir: File) {
    val addresses = dnsServersProvider()
      .asSequence()
      .map(String::trim)
      .filter { it.isNotEmpty() && !it.any { character -> character.isWhitespace() } }
      .distinct()
      .take(MAX_DNS_SERVERS)
      .toList()
    if (addresses.isEmpty()) return
    val directory = File(rootfsDir, RESOLV_CONF_DIRECTORY)
    if (!directory.isDirectory) {
      throw IllegalStateException("guest resolver directory is missing")
    }
    val resolver = File(directory, RESOLV_CONF_NAME)
    if (Files.isSymbolicLink(resolver.toPath())) {
      throw IllegalStateException("guest resolver path is a symlink")
    }
    val content = addresses.joinToString(separator = "", postfix = "") { "nameserver $it\n" }
    if (!resolver.exists() || resolver.readText() != content) {
      var temporary: File? = null
      try {
        temporary = File.createTempFile("$RESOLV_CONF_NAME-", ".tmp", directory)
        temporary.writeText(content)
        Files.move(
          temporary.toPath(),
          resolver.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        temporary?.takeIf { it.exists() }?.delete()
      }
    }
  }

  private fun prepareGuestAccount(rootfsDir: File, username: String, uid: Int, gid: Int) {
    synchronized(GUEST_ACCOUNT_MUTATION_LOCK) {
      prepareGuestAccountLocked(rootfsDir, username, uid, gid)
    }
  }

  private fun prepareGuestAccountLocked(rootfsDir: File, username: String, uid: Int, gid: Int) {
    val etc = File(rootfsDir, "etc")
    if (!etc.isDirectory || Files.isSymbolicLink(etc.toPath())) {
      throw IllegalStateException("guest etc directory is missing")
    }
    val homeRoot = File(rootfsDir, "home")
    val home = File(homeRoot, username)
    // Renaming back to a former name reuses its alias; drop the link first.
    if (isHomeAlias(home)) Files.delete(home.toPath())
    if (Files.isSymbolicLink(home.toPath()) || (home.exists() && !home.isDirectory)) {
      throw IllegalStateException("guest home directory is unavailable")
    }
    val passwd = File(etc, "passwd")
    val group = File(etc, "group")
    val shadow = File(etc, "shadow")
    listOf(passwd, group, shadow).forEach { file ->
      if (Files.isSymbolicLink(file.toPath()) || (file.exists() && !file.isFile)) {
        throw IllegalStateException("guest account file is not regular: ${file.name}")
      }
    }
    val originalPasswdLines = if (passwd.exists()) passwd.readLines(Charsets.UTF_8) else emptyList()
    val originalGroupLines = if (group.exists()) group.readLines(Charsets.UTF_8) else emptyList()
    val originalShadowLines = if (shadow.exists()) shadow.readLines(Charsets.UTF_8) else emptyList()
    var passwdLines = originalPasswdLines
    var groupLines = originalGroupLines
    var shadowLines = originalShadowLines
    var migratedLegacyProfile = false
    var formerUsername: String? = null
    val existingUser = passwdLines.firstOrNull { it.substringBefore(':') == username }
    if (existingUser != null) {
      val fields = existingUser.split(':')
      if (
        passwdLines.count { it.substringBefore(':') == username } != 1 ||
        fields.size < 7 ||
        fields[2] != uid.toString() ||
        fields[3] != gid.toString()
      ) {
        throw IllegalStateException("guest username already has a different identity")
      }
      if (isFixedProfileIdentity(username, uid, gid) &&
        passwdLines.count { it.split(':').getOrNull(2) == uid.toString() } != 1
      ) {
        throw IllegalStateException("fixed profile uid is ambiguous")
      }
    } else {
      val uidRows = passwdLines.filter { it.split(':').getOrNull(2) == uid.toString() }
      if (uidRows.isNotEmpty()) {
        if (!isFixedProfileIdentity(username, uid, gid)) {
          throw IllegalStateException("guest uid is already occupied")
        }
        formerUsername = uidRows.singleOrNull()?.substringBefore(':')
        val migrated = migrateLegacyProfileAccount(passwdLines, groupLines, shadowLines, username)
        passwdLines = migrated.passwd
        groupLines = migrated.group
        shadowLines = migrated.shadow
        migratedLegacyProfile = true
      } else {
        passwdLines = passwdLines + "$username:x:$uid:$gid:$username:${guestHomePathFor(username)}:/bin/sh"
      }
    }
    if (groupLines.none { it.split(':').getOrNull(2) == gid.toString() }) {
      val groupName = username.takeIf { name -> groupLines.none { it.substringBefore(':') == name } }
        ?: "horus_shared"
      if (groupLines.any { it.substringBefore(':') == groupName }) {
        throw IllegalStateException("guest group name is already occupied")
      }
      groupLines = groupLines + "$groupName:x:$gid:"
    }
    // The Android login gate verifies the salted password verifier. Keep the
    // guest shadow entry locked so a second, unaudited auth path cannot bypass
    // that gate, even if a prior root session edited the entry.
    val lockedShadowEntry = "$username:!:19000:0:99999:7:::"
    val shadowEntryIndex = shadowLines.indexOfFirst { it.substringBefore(':') == username }
    if (shadowEntryIndex < 0) {
      shadowLines = shadowLines + lockedShadowEntry
    } else if (shadowLines[shadowEntryIndex].split(':').getOrNull(1) != "!") {
      shadowLines = shadowLines.mapIndexed { index, line -> if (index == shadowEntryIndex) lockedShadowEntry else line }
    }

    // The migration is retryable if Android stops the service between file
    // replacements: write group and shadow first, then passwd as the commit
    // point so the old profile remains resolvable until its replacement is
    // ready. Every transform above is validated before the first write.
    if (migratedLegacyProfile) {
      if (groupLines != originalGroupLines) {
        atomicWrite(group, groupLines.joinToString("\n", postfix = "\n"))
      }
      if (shadowLines != originalShadowLines) {
        atomicWrite(shadow, shadowLines.joinToString("\n", postfix = "\n"))
      }
      if (passwdLines != originalPasswdLines) {
        atomicWrite(passwd, passwdLines.joinToString("\n", postfix = "\n"))
      }
    } else {
      if (passwdLines != originalPasswdLines) {
        atomicWrite(passwd, passwdLines.joinToString("\n", postfix = "\n"))
      }
      if (groupLines != originalGroupLines) {
        atomicWrite(group, groupLines.joinToString("\n", postfix = "\n"))
      }
      if (shadowLines != originalShadowLines) {
        atomicWrite(shadow, shadowLines.joinToString("\n", postfix = "\n"))
      }
    }
    if (!home.exists() && !home.mkdirs() || !home.isDirectory) {
      throw IllegalStateException("guest home directory is unavailable")
    }
    linkFormerHomes(homeRoot, formerUsername, username)
  }

  /**
   * After a rename, files that saved the old absolute path (virtualenvs,
   * rc files, tool configs) keep working: /home/<former> becomes a relative
   * link to the current name, and older aliases follow each later rename.
   * Only an empty mount-point directory is ever replaced; anything with
   * content is left alone. Running sessions keep their own bind instead.
   */
  private fun linkFormerHomes(homeRoot: File, former: String?, current: String) {
    if (former != null && former != current && USERNAME_PATTERN.matches(former)) {
      val old = File(homeRoot, former)
      val path = old.toPath()
      val replaceable = when {
        Files.isSymbolicLink(path) -> isHomeAlias(old)
        old.isDirectory -> old.list()?.isEmpty() == true
        else -> !old.exists()
      }
      if (replaceable) {
        Files.deleteIfExists(path)
        Files.createSymbolicLink(path, java.nio.file.Paths.get(current))
      }
    }
    homeRoot.listFiles()?.filter { isHomeAlias(it) && Files.readSymbolicLink(it.toPath()).toString() != current }?.forEach {
      Files.delete(it.toPath())
      Files.createSymbolicLink(it.toPath(), java.nio.file.Paths.get(current))
    }
  }

  /** A link Horus made for a former name: a symlink to a bare sibling username. */
  private fun isHomeAlias(file: File): Boolean =
    Files.isSymbolicLink(file.toPath()) &&
      runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrNull()?.let { it != "." && it != ".." && USERNAME_PATTERN.matches(it) } == true

  private data class GuestAccountLines(
    val passwd: List<String>,
    val group: List<String>,
    val shadow: List<String>,
  )

  /** The profile user's uid and gid, under whatever name the user chose. */
  private fun isFixedProfileIdentity(username: String, uid: Int, gid: Int): Boolean =
    username != GUEST_ROOT_USER && uid == GUEST_UID && gid == GUEST_GID

  /**
   * Renames the profile uid's account to [profileUsername]. Accepts only the
   * exact row format Horus writes, so it covers the 1.0.x custom-name accounts
   * and a rootfs whose account predates the configured name.
   */
  private fun migrateLegacyProfileAccount(
    passwdLines: List<String>,
    groupLines: List<String>,
    shadowLines: List<String>,
    profileUsername: String,
  ): GuestAccountLines {
    val uidRows = passwdLines.filter { it.split(':').getOrNull(2) == GUEST_UID.toString() }
    if (uidRows.size != 1) throw IllegalStateException("legacy profile uid is ambiguous")
    val fields = uidRows.single().split(':')
    val legacyUsername = fields[0]
    if (
      fields.size != 7 ||
      fields[1] != "x" ||
      fields[3] != GUEST_GID.toString() ||
      !USERNAME_PATTERN.matches(legacyUsername) ||
      legacyUsername == GUEST_ROOT_USER ||
      fields[4] != legacyUsername ||
      fields[5] != "/home/$legacyUsername" ||
      // Remote access gives the profile user its login shell.
      fields[6] !in setOf("/bin/sh", "/usr/local/bin/horus-ssh-shell") ||
      passwdLines.count { it.substringBefore(':') == legacyUsername } != 1
    ) {
      throw IllegalStateException("legacy profile account does not match the prior format")
    }
    if (passwdLines.any { it.substringBefore(':') == profileUsername && it != uidRows.single() }) {
      throw IllegalStateException("fixed profile username is already occupied")
    }
    if (groupLines.any { line ->
        val fields = line.split(':')
        fields.size == 4 && fields[0] != legacyUsername && fields[3].split(',').contains(legacyUsername)
      }
    ) {
      throw IllegalStateException("legacy profile has unexpected supplemental group memberships")
    }

    val legacyGroupIndexes = groupLines.indices.filter { groupLines[it].substringBefore(':') == legacyUsername }
    val profileGroupIndexes = groupLines.indices.filter { groupLines[it].substringBefore(':') == profileUsername }
    val gidRows = groupLines.filter { it.split(':').getOrNull(2) == GUEST_GID.toString() }
    if (legacyGroupIndexes.size > 1) throw IllegalStateException("legacy profile group is ambiguous")
    var migratedGroups = groupLines
    if (legacyGroupIndexes.isNotEmpty()) {
      val index = legacyGroupIndexes.single()
      val groupFields = groupLines[index].split(':')
      if (
        groupFields.size != 4 ||
        groupFields[1] != "x" ||
        groupFields[2] != GUEST_GID.toString() ||
        gidRows.size != 1 ||
        profileGroupIndexes.isNotEmpty() ||
        groupFields[3].isNotEmpty()
      ) {
        throw IllegalStateException("legacy profile group does not match the prior format")
      }
      migratedGroups = groupLines.mapIndexed { row, line ->
        if (row == index) listOf(profileUsername, groupFields[1], groupFields[2], "").joinToString(":") else line
      }
    } else if (profileGroupIndexes.isNotEmpty()) {
      // A prior interrupted migration left the new group name already in place.
      if (profileGroupIndexes.size != 1 ||
        groupLines[profileGroupIndexes.single()].split(':').getOrNull(2) != GUEST_GID.toString()
      ) {
        throw IllegalStateException("fixed profile group conflicts with migration")
      }
      migratedGroups = groupLines
    } else {
      if (gidRows.size > 1) throw IllegalStateException("legacy profile group id is ambiguous")
    }

    val legacyShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == legacyUsername }
    val profileShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == profileUsername }
    if (legacyShadowIndexes.size > 1 || profileShadowIndexes.size > 1 ||
      (legacyShadowIndexes.isNotEmpty() && profileShadowIndexes.isNotEmpty())
    ) {
      throw IllegalStateException("legacy profile shadow entry is ambiguous")
    }
    val lockedShadowEntry = "$profileUsername:!:19000:0:99999:7:::"
    val migratedShadow = when {
      profileShadowIndexes.isNotEmpty() -> {
        if (profileShadowIndexes.size != 1 || shadowLines[profileShadowIndexes.single()] != lockedShadowEntry) {
          throw IllegalStateException("fixed profile shadow entry conflicts with migration")
        }
        if (legacyShadowIndexes.isNotEmpty()) {
          throw IllegalStateException("legacy profile shadow entry conflicts with migration")
        }
        shadowLines
      }
      legacyShadowIndexes.isNotEmpty() -> {
        val legacyLockedShadowEntry = "$legacyUsername:!:19000:0:99999:7:::"
        if (shadowLines[legacyShadowIndexes.single()] != legacyLockedShadowEntry) {
          throw IllegalStateException("legacy profile shadow entry does not match the prior format")
        }
        shadowLines.mapIndexed { index, line ->
          if (index == legacyShadowIndexes.single()) lockedShadowEntry else line
        }
      }
      else -> shadowLines + lockedShadowEntry
    }

    val migratedPasswd = passwdLines.map { line ->
      if (line == uidRows.single()) {
        listOf(profileUsername, fields[1], fields[2], fields[3], profileUsername, "/home/$profileUsername", fields[6]).joinToString(":")
      } else {
        line
      }
    }
    return GuestAccountLines(migratedPasswd, migratedGroups, migratedShadow)
  }

  private fun guestHomePathFor(username: String): String = "/home/$username"

  /**
   * Give every interactive guest the same small zsh theme. Neither pinned
   * rootfs includes zsh, so the profile is ready for the first install and
   * automatically hands future login shells to zsh once it exists. User content outside these bounded blocks is preserved.
   */
  private fun prepareZshProfile(guestHomeDir: File) {
    val profile = File(guestHomeDir, PROFILE_NAME)
    val existingProfile = if (profile.exists()) profile.readText(Charsets.UTF_8) else ""
    val updatedProfile = repairManagedBlock(
      existingProfile,
      ZSH_PROFILE_BEGIN,
      ZSH_PROFILE_END,
      ZSH_PROFILE_BLOCK,
    )
    if (updatedProfile != existingProfile) atomicWrite(profile, updatedProfile)

    val zshrc = File(guestHomeDir, ZSHRC_NAME)
    if (Files.isSymbolicLink(zshrc.toPath())) {
      throw IllegalStateException("guest zshrc is a symbolic link")
    }
    if (zshrc.exists() && !zshrc.isFile) {
      throw IllegalStateException("guest zshrc is not a regular file")
    }
    if (zshrc.exists() && zshrc.length() > MAX_ZSHRC_BYTES) {
      throw IllegalStateException("guest zshrc is too large")
    }
    val existingZshrc = if (zshrc.exists()) zshrc.readText(Charsets.UTF_8) else ""
    val updatedZshrc = repairManagedBlock(
      existingZshrc,
      ZSHRC_BEGIN,
      ZSHRC_END,
      ZSHRC_BLOCK,
    )
    if (updatedZshrc != existingZshrc) atomicWrite(zshrc, updatedZshrc)
  }

  private fun repairManagedBlock(existing: String, begin: String, end: String, block: String): String {
    val start = existing.indexOf(begin)
    val finish = existing.indexOf(end)
    if ((start < 0) != (finish < 0) || (start >= 0 && finish < start)) {
      throw IllegalStateException("guest profile has an incomplete managed block")
    }
    if (start >= 0 && existing.indexOf(begin, start + begin.length) >= 0) {
      throw IllegalStateException("guest profile has duplicate managed blocks")
    }
    if (start >= 0) {
      return existing.substring(0, start) + block + existing.substring(finish + end.length)
    }
    val prefix = existing.trimEnd()
    return if (prefix.isEmpty()) "$block\n" else "$prefix\n\n$block\n"
  }

  private fun atomicWrite(target: File, content: String) {
    var temporary: File? = null
    try {
      temporary = File.createTempFile(".horus-profile-", ".tmp", target.parentFile)
      temporary.writeText(content, Charsets.UTF_8)
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary?.takeIf { it.exists() }?.delete()
    }
  }

  private fun isReadable(file: File): Boolean = try {
    file.inputStream().use {
      it.read()
      true
    }
  } catch (_: java.io.IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

  fun hasProvisionedToolchain(rootfsDir: File, guestHomeDir: File, target: String): Boolean {
    require(TerminalRuntimeContract.isValidToolchainTarget(target)) { "toolchain target is invalid" }
    val rootfsZsh = listOf(File(rootfsDir, "bin/zsh"), File(rootfsDir, "usr/bin/zsh"))
      .any { it.isFile && it.canExecute() }
    val userBin = File(guestHomeDir, ".local/bin")
    if (!rootfsZsh) return false
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL) return hasBaseUtilities(rootfsDir)
    val readyMarker = File(guestHomeDir, ".cache/horus/$target.ready")
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB) {
      val rootfsGh = listOf(File(rootfsDir, "bin/gh"), File(rootfsDir, "usr/bin/gh"))
        .any { it.isFile && it.canExecute() }
      return rootfsGh && readyMarker.isFile
    }
    return false
  }

  private fun hasBaseUtilities(rootfsDir: File): Boolean =
    listOf("rg", "curl", "jq", "python3").all { name ->
      File(rootfsDir, "usr/bin/$name").let { it.isFile && it.canExecute() }
    } && File(rootfsDir, "lib/ld-linux-aarch64.so.1").isFile

  private data class ProcCompatibilityBind(
    val source: File,
    val guestPath: String,
  )

  private data class DeviceBind(
    val source: File,
    val guestPath: String,
  )

  companion object {
    const val GUEST_SHELL = "/bin/sh"
    const val GUEST_HOME = "/root"
    const val GUEST_ROOT_USER = "root"
    const val GUEST_UID = 1000
    const val GUEST_GID = 1000
    private val GUEST_ACCOUNT_MUTATION_LOCK = Any()
    const val GUEST_PATH = "/root/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val GUEST_LANG = "C.UTF-8"
    const val GUEST_SUDO_PATH = "/usr/local/bin/sudo"
    const val GUEST_ROOT_HELPER_PATH = "/usr/local/lib/horus/sudo-helper"
    private val ROOT_HELPER_FILES = listOf("sudo" to GUEST_SUDO_PATH, "sudo-helper" to GUEST_ROOT_HELPER_PATH)
    const val HOST_PROC = "/proc"
    const val HOST_DEV = "/dev"
    const val GUEST_PROC = "/proc"
    const val GUEST_DEV = "/dev"
    private const val PROC_COMPATIBILITY_DIR = "proc-compat"
    private const val PROFILE_NAME = ".profile"
    private const val ZSHRC_NAME = ".zshrc"
    private const val MAX_PROFILE_BYTES = 256 * 1024L
    private const val MAX_ZSHRC_BYTES = 256 * 1024L
    private const val MAX_AGENT_INSTRUCTIONS_BYTES = 256 * 1024L
    private const val MAX_SESSION_COMMAND_LENGTH = 4096
    private const val MAX_GUEST_ID = 65_533
    const val ZSH_PROFILE_BEGIN = "# BEGIN HORUS_ZSH_V1"
    const val ZSH_PROFILE_END = "# END HORUS_ZSH_V1"
    const val ZSHRC_BEGIN = "# BEGIN HORUS_ZSHRC_V1"
    const val ZSHRC_END = "# END HORUS_ZSHRC_V1"
    private val ZSH_PROFILE_BLOCK = """
      $ZSH_PROFILE_BEGIN
      export HORUS_ZSH_THEME=horus
      umask 0002
      if [ "${'$'}{HORUS_BOOTSTRAP:-}" = 1 ]; then
        stty -echo 2>/dev/null || true
      fi
      $ZSH_PROFILE_END
    """.trimIndent()
    internal val ZSH_EXIT_SCRIPT = """
      # A bare exit, logout or Ctrl-D must not inherit the last command's
      # status (a failed build, or 130 after Ctrl-C). An explicit `exit N`
      # keeps N, and a killed shell still reports its signal. The status is
      # set before exiting because a login shell with a zlogout file (Debian
      # ships /etc/zsh/zlogout) ignores an exit inside zshexit; zshexit only
      # covers EOF without the line editor. Never apply this to command shells.
      if [[ -o interactive && -z "${'$'}{ZSH_EXECUTION_STRING-}" ]]; then
        exit() { if (( ${'$'}# )); then horus_exit_status=1; else set -- 0; fi; builtin exit "${'$'}@"; }
        logout() { if (( ${'$'}# )); then horus_exit_status=1; else set -- 0; fi; builtin logout "${'$'}@"; }
        zshexit() { [[ -n ${'$'}{horus_exit_status-} ]] || exit 0; }
        setopt ignore_eof
        horus-eof() { [[ -z ${'$'}BUFFER ]] && exit; zle delete-char-or-list; }
        zle -N horus-eof
        bindkey '^D' horus-eof
      fi
    """.trimIndent()
    internal val DOTNET_ENV_SCRIPT = """
      export DOTNET_gcServer="${'$'}{DOTNET_gcServer:-0}"
      export DOTNET_GCHeapHardLimit="${'$'}{DOTNET_GCHeapHardLimit:-0x40000000}"
      export DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER="${'$'}{DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER:-1}"
      export DOTNET_CLI_USE_MSBUILD_SERVER="${'$'}{DOTNET_CLI_USE_MSBUILD_SERVER:-0}"
      export UseSharedCompilation="${'$'}{UseSharedCompilation:-false}"
    """.trimIndent()
    private val ZSHRC_BLOCK = """
      $ZSHRC_BEGIN
${ZSH_EXIT_SCRIPT.prependIndent("      ")}
      setopt prompt_subst
      setopt transient_rprompt
      umask 0002
      # A stray ^S (XOFF) froze all output until ^Q, which a phone user has no
      # reason to know. zsh re-enables flow control itself, so turn it off here.
      unsetopt flow_control
      stty -ixon 2>/dev/null || true
      export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}{PATH:-/usr/sbin:/usr/bin:/sbin:/bin}"
      if [[ "${'$'}{HORUS_BOOTSTRAP:-}" == 1 ]]; then
        # Keep the first launcher command invisible while the React Native
        # side waits for this prompt. buildZshCommand restores echo before
        # handing control to zsh or the selected harness.
        stty -echo 2>/dev/null || true
        unset HORUS_BOOTSTRAP
      fi
      # duellj theme, kept inline so it works without Oh My Zsh on Alpine:
      # two-line user/host/path/history prompt without a per-line clock.
      PROMPT=$'%{\e[0;34m%}%B┌─[%b%{\e[0m%}%{\e[1;32m%}%n%{\e[1;34m%}@%{\e[0m%}%{\e[0;36m%}%m%{\e[0;34m%}%B]%b%{\e[0m%} - %b%{\e[0;34m%}%B[%b%{\e[1;37m%}%~%{\e[0;34m%}%B]%b%{\e[0m%} - %{\e[0;34m%}%B[%b%{\e[0;33m%}%!%{\e[0;34m%}%B]%b%{\e[0m%}\n%{\e[0;34m%}%B└─[%{\e[1;35m%}${'$'}%{\e[0;34m%}%B]%{\e[0m%}%b '
      PS2='%F{blue}>%f '
      $ZSHRC_END
    """.trimIndent()
    internal val APK_MIRROR_SCRIPT = """
      if [ -n "${'$'}{HORUS_ALPINE_MIRROR:-}" ] && [ -w /etc/apk/repositories ]; then
        sed -i -E "s@^[^#]*/(v[0-9]+\.[0-9]+|edge)/([a-z]+)/?\${'$'}@${'$'}HORUS_ALPINE_MIRROR/\1/\2@" /etc/apk/repositories || true
      fi
    """.trimIndent()
    /**
     * The guest's package manager and the packages each target needs from
     * it: apk on Alpine, apt on Debian. Detected inside the guest, so the
     * same script serves either rootfs. apt runs as root under PRoot, where
     * it cannot drop to its _apt sandbox user, and refreshes its lists at
     * most once a day.
     */
    internal val PACKAGE_MANAGER_SCRIPT = """
      if [ -x /usr/bin/apt-get ]; then
        horus_pkg=apt
        # Debian splits venv (and its ensurepip) out of python3.
        pkgs_shell="ca-certificates curl git jq less openssh-client python3 python3-venv ripgrep tzdata zsh"
        pkgs_github="gh git openssh-client zsh"
        has_python_venv() {
          for venv_dir in /usr/lib/python3.*/ensurepip; do [ -d "${'$'}venv_dir" ] && return 0; done
          return 1
        }
      else
        horus_pkg=apk
        pkgs_shell="ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh"
        pkgs_github="gh git openssh-client-default zsh"
        has_python_venv() { true; }
      fi
      # The latest progress line, for the app to show while tools install.
      pkg_progress=/root/.cache/horus/provision-progress
      pkg_status=/root/.cache/horus/pkg-status
      # Turns apt's Status-Fd lines, or apk's "(12/47) Installing ..." log
      # lines, into "Downloading packages… 37%" and "Installing packages…
      # 64%", printed and kept in pkg_progress.
      watch_packages() {
        watch_last=
        while sleep 1; do
          if [ "${'$'}horus_pkg" = apt ]; then
            watch_line=${'$'}(tail -n 1 "${'$'}pkg_status" 2>/dev/null) || continue
          else
            watch_line=${'$'}(grep -o '^([0-9]*/[0-9]*)' "${'$'}pkg_log" 2>/dev/null | tail -n 1 | tr -d '()') || continue
          fi
          watch_text=${'$'}(printf '%s\n' "${'$'}watch_line" | awk -F: '
            /^dlstatus:/ { printf "Downloading packages… %d%%\n", ${'$'}3; exit }
            /^pmstatus:/ { for (i = 3; i <= NF; i++) if (${'$'}i ~ /^[0-9]+(\.[0-9]+)?${'$'}/) { printf "Installing packages… %d%%\n", ${'$'}i; exit } }
            /^[0-9]+\/[0-9]+${'$'}/ { split(${'$'}0, part, "/"); if (part[2] > 0) printf "Installing packages… %d%%\n", part[1] * 100 / part[2]; exit }
          ')
          if [ -n "${'$'}watch_text" ] && [ "${'$'}watch_text" != "${'$'}watch_last" ]; then
            printf '%s\n' "${'$'}watch_text"
            printf '%s\n' "${'$'}watch_text" > "${'$'}pkg_progress"
            watch_last=${'$'}watch_text
          fi
        done
      }
      apt_with_status() { apt-get -o APT::Status-Fd=3 "${'$'}@" 3>>"${'$'}pkg_status"; }
      # pkg_install LOG PACKAGE... installs with the guest's package manager.
      # Callers pass a package list unquoted, so each package is one argument.
      pkg_install() {
        pkg_log=${'$'}1
        shift
        : > "${'$'}pkg_status"
        : > "${'$'}pkg_progress"
        watch_packages &
        pkg_watch_pid=${'$'}!
        if [ "${'$'}horus_pkg" = apt ]; then
          pkg_result=0
          if [ -z "${'$'}(find /var/lib/apt/lists -maxdepth 1 -name '*_Packages*' -mmin -1440 2>/dev/null | head -n 1)" ]; then
            run_logged "${'$'}pkg_log" apt-get update || pkg_result=${'$'}?
          fi
          if [ "${'$'}pkg_result" -eq 0 ]; then
            run_logged "${'$'}pkg_log" apt_with_status install -y --no-install-recommends "${'$'}@" || pkg_result=${'$'}?
          fi
        else
          if run_logged "${'$'}pkg_log" apk add --no-cache --no-progress "${'$'}@"; then pkg_result=0; else pkg_result=${'$'}?; fi
        fi
        kill "${'$'}pkg_watch_pid" 2>/dev/null || true
        wait "${'$'}pkg_watch_pid" 2>/dev/null || true
        : > "${'$'}pkg_progress"
        return "${'$'}pkg_result"
      }
    """.trimIndent()
    // npm may successfully install the JS wrapper while skipping its optional
    // native package. Install the exact declared alias as a required package
    // on recovery; both Linux layouts contain Alpine-compatible musl binaries.
    private val TOOLCHAIN_PROVISION_SCRIPT = """
      set -eu
      export HOME=/root
      export PATH=/root/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      target=${'$'}{HORUS_TOOLCHAIN_TARGET:-shell}
      case "${'$'}target" in
        shell|github) ;;
        *) exit 20 ;;
      esac
      printf '%s\n' "HORUS_INSTALL_TARGET=${'$'}target"
      mkdir -p /root/.local/bin /root/.cache/horus
      $APK_MIRROR_SCRIPT
      $PACKAGE_MANAGER_SCRIPT
      provision_stage=/root/.cache/horus/provision-stage
      mark_provision_stage() {
        printf '%s\n' "${'$'}1" > "${'$'}provision_stage"
        printf '%s\n' "HORUS_INSTALL_STAGE=${'$'}1"
      }
      # Copies a command's output to its log file and the terminal through a
      # pipe, so the last lines (usually the error) reach the screen before
      # the exit. A `tail -f` on the file polls once a second and was killed
      # before it read them, which left failure screens with no log.
      run_logged() {
        log_file=${'$'}1
        shift
        log_pipe=${'$'}log_file.pipe
        rm -f "${'$'}log_pipe"
        if ! mkfifo "${'$'}log_pipe" 2>/dev/null; then
          # No pipe: keep the log file, without live output.
          "${'$'}@" >"${'$'}log_file" 2>&1 &
          command_pid=${'$'}!
          set +e
          wait "${'$'}command_pid"
          command_status=${'$'}?
          set -e
          tail -n 40 "${'$'}log_file" 2>/dev/null || true
        else
          tee "${'$'}log_file" < "${'$'}log_pipe" &
          tee_pid=${'$'}!
          "${'$'}@" >"${'$'}log_pipe" 2>&1 &
          command_pid=${'$'}!
          set +e
          wait "${'$'}command_pid"
          command_status=${'$'}?
          set -e
          # tee ends once every writer closes the pipe. A daemon the command
          # left running may hold it open, so give up on it after 5 seconds.
          ( sleep 5; kill "${'$'}tee_pid" 2>/dev/null ) >/dev/null 2>&1 &
          tee_watchdog=${'$'}!
          wait "${'$'}tee_pid" 2>/dev/null || true
          kill "${'$'}tee_watchdog" 2>/dev/null || true
          wait "${'$'}tee_watchdog" 2>/dev/null || true
          rm -f "${'$'}log_pipe"
        fi
        if [ "${'$'}command_status" -ne 0 ]; then
          printf '%s\n' "HORUS_INSTALL_COMMAND_EXIT=${'$'}command_status"
        fi
        return "${'$'}command_status"
      }
      mark_provision_stage start

      if [ "${'$'}target" = shell ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 ||
          [ ! -x /usr/bin/rg ] || [ ! -x /usr/bin/curl ] || [ ! -x /usr/bin/jq ] ||
          [ ! -x /usr/bin/python3 ] || [ ! -e /lib/ld-linux-aarch64.so.1 ] ||
          [ ! -x /usr/bin/less ] || [ ! -e /usr/share/zoneinfo/UTC ] || ! has_python_venv; then
          mark_provision_stage apk
          if ! pkg_install /root/.cache/horus/alpine-bootstrap.log ${'$'}pkgs_shell; then
            # PRoot cannot create zsh's versioned hardlink (bin/zsh-5.9) on
            # this Android filesystem. Continue only when both the shell and
            # base utilities extracted successfully for visible clone sessions.
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || ! git --version >/dev/null 2>&1 ||
              ! /usr/bin/rg --version >/dev/null 2>&1 || ! /usr/bin/curl --version >/dev/null 2>&1 ||
              ! /usr/bin/jq --version >/dev/null 2>&1 || ! /usr/bin/python3 --version >/dev/null 2>&1 ||
              [ ! -e /lib/ld-linux-aarch64.so.1 ]; then
              mark_provision_stage apk_failed
              exit 21
            fi
          fi
        fi
      elif [ "${'$'}target" = github ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || [ ! -f /root/.cache/horus/github.ready ]; then
          mark_provision_stage github
          if ! pkg_install /root/.cache/horus/github-install.log ${'$'}pkgs_github; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || ! gh --version >/dev/null 2>&1; then
              mark_provision_stage github_failed
              exit 26
            fi
          fi
        fi
      fi
      # sudo's root helper is Python. The Bare terminal installs it with its
      # tools; install it with whatever opens first, so sudo works from the
      # first sign-in. A failure leaves sudo unavailable, not the app.
      if [ "${'$'}target" != shell ] && [ ! -x /usr/bin/python3 ]; then
        pkg_install /root/.cache/horus/alpine-bootstrap.log python3 || true
      fi
      mark_provision_stage base_ready

      target_marker=/root/.cache/horus/${'$'}target.ready

      mark_provision_stage verify
      if [ "${'$'}target" = shell ]; then
        test -x /bin/zsh || test -x /usr/bin/zsh
        git --version >/dev/null 2>&1
        /usr/bin/rg --version >/dev/null 2>&1
        /usr/bin/curl --version >/dev/null 2>&1
        /usr/bin/jq --version >/dev/null 2>&1
        /usr/bin/python3 --version >/dev/null 2>&1
        test -e /lib/ld-linux-aarch64.so.1
      elif [ "${'$'}target" = github ]; then
        test -x /bin/gh || test -x /usr/bin/gh
      fi
      if [ "${'$'}target" != shell ]; then
        printf '%s\n' ready > "${'$'}target_marker"
      fi
      mark_provision_stage ready

      printf '%s\n\033[1A\033[2K' HORUS_TOOLCHAIN_READY
    """.trimIndent()
    private val TOOLCHAIN_SESSION_SCRIPT = """
      set -eu
      session_username="${'$'}1"
      session_uid="${'$'}2"
      session_gid="${'$'}3"
      session_command="${'$'}4"
      session_pwd="${'$'}5"
      export HORUS_GUEST_USERNAME="${'$'}session_username"
      export HORUS_SESSION_COMMAND="${'$'}session_command"
      export HORUS_SESSION_PWD="${'$'}session_pwd"
      if [ "${'$'}{HORUS_SKIP_TOOLCHAIN_PROVISION:-0}" != 1 ]; then
        $TOOLCHAIN_PROVISION_SCRIPT
      else
        $APK_MIRROR_SCRIPT
        printf '%s\n\033[1A\033[2K' HORUS_TOOLCHAIN_READY
      fi
      # The install log has done its job; start the app on a clean screen
      # with no scrollback, as apps like Claude Code draw inline below it.
      printf '\033[H\033[2J\033[3J'
      # Markers stay exact output lines for the app's scanners; the trailing
      # cursor-up + erase-line keeps them off the user's screen.
      printf '%s\n\033[1A\033[2K' "HORUS_INSTALL_HANDOFF=${'$'}session_username"
      if [ "${'$'}session_username" = root ]; then
        exec /bin/sh -c "${'$'}session_command"
      fi
      chown "${'$'}session_uid:${'$'}session_gid" /root "/home/${'$'}session_username"
      chmod 700 /root "/home/${'$'}session_username"
      if [ -d /workspace ]; then
        chgrp "${'$'}session_gid" /workspace
        chmod 2770 /workspace
      fi
      # sudo cannot become root under PRoot once the session drops to its
      # user; a helper started now, while still root, runs its commands.
      if [ "${'$'}{HORUS_ROOT_HELPER:-0}" = 1 ] && [ -x /usr/bin/python3 ] && [ -f $GUEST_ROOT_HELPER_PATH ]; then
        mkdir -p /run/horus-sudo
        export HORUS_SUDO_SOCKET="/run/horus-sudo/s${'$'}${'$'}"
        /usr/bin/python3 $GUEST_ROOT_HELPER_PATH "${'$'}HORUS_SUDO_SOCKET" "${'$'}session_uid" </dev/null >/dev/null 2>>/run/horus-sudo/helper.log &
      fi
      unset HORUS_ROOT_HELPER
      user_script='
        cd "${'$'}HORUS_SESSION_PWD"
        umask 0002
        export HOME="/home/${'$'}HORUS_GUEST_USERNAME"
        export PWD="${'$'}HORUS_SESSION_PWD"
        export PATH="/home/${'$'}HORUS_GUEST_USERNAME/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        export XDG_CONFIG_HOME="${'$'}HOME/.config"
        export XDG_DATA_HOME="${'$'}HOME/.local/share"
        export XDG_CACHE_HOME="${'$'}HOME/.cache"
        export USER="${'$'}HORUS_GUEST_USERNAME" LOGNAME="${'$'}HORUS_GUEST_USERNAME"
        unset HORUS_BOOTSTRAP
        exec /bin/sh -c "${'$'}HORUS_SESSION_COMMAND"
      '
      if [ -x /bin/busybox ]; then
        exec /bin/busybox su -p -c "${'$'}user_script" "${'$'}session_username"
      fi
      # Debian has no BusyBox; util-linux setpriv changes ids without PAM,
      # which PRoot's emulated root cannot satisfy.
      exec setpriv --reuid="${'$'}session_uid" --regid="${'$'}session_gid" --clear-groups -- /bin/sh -c "${'$'}user_script"
    """.trimIndent()
    private val REMOTE_ACCESS_SCRIPT = """
      set -eu
      user="${'$'}1"
      uid="${'$'}2"
      gid="${'$'}3"
      port="${'$'}4"
      home="/home/${'$'}user"
      export HOME=/root
      export PATH=/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      stage() { printf 'HORUS_REMOTE_STAGE=%s\n' "${'$'}1"; }
      mkdir -p /root/.cache/horus
      if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ] ||
        { [ ! -x /usr/lib/ssh/sftp-server ] && [ ! -x /usr/lib/openssh/sftp-server ]; } ||
        [ ! -x /usr/bin/scp ] || [ ! -x /usr/bin/rsync ] || ! command -v zsh >/dev/null 2>&1 ||
        [ ! -x /usr/bin/python3 ]; then
        stage installing
        if [ -x /usr/bin/apt-get ]; then
          mkdir -p /etc/apt/apt.conf.d
          [ -f /etc/apt/apt.conf.d/99horus ] ||
            printf '%s\n' 'APT::Sandbox::User "root";' 'Acquire::Retries "3";' 'Dpkg::Use-Pty "0";' 'APT::Install-Recommends "0";' 'APT::Install-Suggests "0";' > /etc/apt/apt.conf.d/99horus
          remote_install() {
            DEBIAN_FRONTEND=noninteractive apt-get update &&
              DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends dropbear-bin openssh-client openssh-sftp-server python3 rsync zsh
          }
        else
          # python3 runs sudo's root helper for SSH logins.
          remote_install() { apk add --no-cache --no-progress dropbear openssh-client-default openssh-sftp-server python3 rsync zsh; }
        fi
        if ! remote_install; then
          if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ]; then
            stage install_failed
            exit 30
          fi
        fi
      fi
      if [ ! -e /usr/lib/ssh/sftp-server ] && [ -x /usr/lib/openssh/sftp-server ]; then
        mkdir -p /usr/lib/ssh
        ln -s /usr/lib/openssh/sftp-server /usr/lib/ssh/sftp-server
      fi
      # The app binds the phone's one host key here; without it, 1.0.4's
      # per-home key.
      keydir="${'$'}{5:-${'$'}home/.config/horus}"
      hostkey="${'$'}keydir/ssh_host_ed25519_key"
      mkdir -p "${'$'}keydir" "${'$'}home/.ssh" "${'$'}home/.cache/horus"
      if [ ! -s "${'$'}hostkey" ]; then
        rm -f "${'$'}hostkey" "${'$'}hostkey.new"
        # dropbearkey publishes with link(2), which PRoot turns into a
        # symlink chain. Copy the key in so the app finds a plain file.
        if ! dropbearkey -t ed25519 -f "${'$'}hostkey.new" >/dev/null 2>&1 ||
          ! cat "${'$'}hostkey.new" > "${'$'}hostkey.tmp" || ! mv -f "${'$'}hostkey.tmp" "${'$'}hostkey"; then
          rm -f "${'$'}hostkey.new" "${'$'}hostkey.tmp"
          stage hostkey_failed
          exit 31
        fi
        rm -f "${'$'}hostkey.new" "${'$'}hostkey.new.pub"
      fi
      touch "${'$'}home/.ssh/authorized_keys"
      chown "${'$'}uid:${'$'}gid" "${'$'}home" "${'$'}home/.ssh" "${'$'}home/.ssh/authorized_keys" "${'$'}keydir" "${'$'}hostkey" 2>/dev/null || true
      # dropbear refuses keys in group- or world-writable directories.
      chmod 700 "${'$'}home" "${'$'}home/.ssh"
      chmod 600 "${'$'}home/.ssh/authorized_keys" "${'$'}hostkey"
      if [ -d /workspace ]; then
        chgrp "${'$'}gid" /workspace 2>/dev/null || true
        chmod 2770 /workspace 2>/dev/null || true
      fi
      # dropbear hard-codes PATH=/usr/bin:/bin for commands. Give SSH logins
      # a shell that sets the full PATH (agents live in ~/.local/bin) and
      # starts zsh for interactive logins.
      login_shell=/usr/local/bin/horus-ssh-shell
      mkdir -p /usr/local/bin
      cat > "${'$'}login_shell.tmp" <<'HORUS_SHELL'
      #!/bin/sh
      export PATH="${'$'}HOME/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
      export LANG="${'$'}{LANG:-C.UTF-8}"
${DOTNET_ENV_SCRIPT.prependIndent("      ")}
      # A script loses dropbear's "-" login marker in ${'$'}0; an interactive
      # login is the call without arguments (commands arrive as -c ...).
      if [ "${'$'}#" -eq 0 ]; then
        # The host key no longer changes with the user, so say who this is.
        horus_os=${'$'}(. /etc/os-release 2>/dev/null && printf '%s' "${'$'}PRETTY_NAME")
        printf 'Horus: %s on %s\n' "${'$'}(id -un)" "${'$'}{horus_os:-Linux}" >&2
        if command -v zsh >/dev/null 2>&1; then exec zsh -l; fi
        exec /bin/sh -l
      fi
      exec /bin/sh "${'$'}@"
      HORUS_SHELL
      chmod 755 "${'$'}login_shell.tmp"
      mv -f "${'$'}login_shell.tmp" "${'$'}login_shell"
      grep -qx "${'$'}login_shell" /etc/shells 2>/dev/null || echo "${'$'}login_shell" >> /etc/shells
      sed -i "s#^\(${'$'}user:[^:]*:${'$'}uid:${'$'}gid:[^:]*:[^:]*:\).*#\1${'$'}login_shell#" /etc/passwd
      # Debian's zsh reads only /etc/zsh/zshrc, not zshrc.d; load it there.
      if [ -x /usr/bin/apt-get ] && [ -f /etc/zsh/zshrc ] && ! grep -q 'horus-zshrc.d' /etc/zsh/zshrc; then
        printf '%s\n' '# horus-zshrc.d: load the snippets Horus keeps in /etc/zsh/zshrc.d' \
          'for horus_rc in /etc/zsh/zshrc.d/*.zsh(N); do source "${'$'}horus_rc"; done; unset horus_rc' >> /etc/zsh/zshrc
      fi
      # sudo for SSH logins, as for the app's own terminal (see sudo-helper).
      if [ -x /usr/bin/python3 ] && [ -f $GUEST_ROOT_HELPER_PATH ]; then
        mkdir -p /run/horus-sudo
        /usr/bin/python3 $GUEST_ROOT_HELPER_PATH /run/horus-sudo/ssh "${'$'}uid" </dev/null >/dev/null 2>>/run/horus-sudo/helper.log &
      fi
      stage serving
      # Everything below execs in place, so this pid becomes dropbear's; the
      # app kills it and its sessions on stop (PRoot does not take them down).
      printf 'HORUS_REMOTE_PID=%s\n' "${'$'}${'$'}"
      export HORUS_REMOTE_PORT="${'$'}port" HORUS_REMOTE_HOSTKEY="${'$'}hostkey"
      # dropbear runs as the profile user, so that is the only account it can
      # log in. -s/-w: keys only, never root. Bound to loopback, so computers
      # reach it only through adb forward.
      # dropbear re-execs itself per connection with fexecve(), which PRoot
      # cannot follow. It skips that when it cannot open argv[0], so start it
      # by bare name from / and let each connection run in the forked child.
      dropbear_script='cd / && PATH=/usr/sbin:/usr/bin:/sbin:/bin exec dropbear -F -E -s -w -K 30 -p "127.0.0.1:${'$'}HORUS_REMOTE_PORT" -r "${'$'}HORUS_REMOTE_HOSTKEY" -P "${'$'}HOME/.cache/horus/dropbear.pid"'
      if [ -x /bin/busybox ]; then
        exec /bin/busybox su -s /bin/sh -c "${'$'}dropbear_script" "${'$'}user"
      fi
      exec setpriv --reuid="${'$'}uid" --regid="${'$'}gid" --clear-groups -- env HOME="${'$'}home" USER="${'$'}user" LOGNAME="${'$'}user" SHELL=/bin/sh /bin/sh -c "${'$'}dropbear_script"
    """.trimIndent()
    private const val RESOLV_CONF_DIRECTORY = "etc"
    private const val RESOLV_CONF_NAME = "resolv.conf"
    private const val MAX_DNS_SERVERS = 4
    private val DEVICE_FILES = listOf("null", "zero", "random", "urandom", "tty")

    /** A real terminal type: several target CLIs need more than "dumb". */
    const val DEFAULT_TERM = "xterm-256color"
    private val USERNAME_PATTERN = Regex("[A-Za-z0-9._-]{1,32}")

    /**
     * Stand-ins for global /proc files Android hides from apps: /proc/stat
     * (with a real `btime`) and loadavg, so BusyBox top and other tools
     * parse and render without claiming real global metrics.
     */
    internal fun procGlobalFiles(bootTimeSeconds: Long): List<Pair<String, String>> = listOf(
      "stat" to """
        cpu 0 0 0 0 0 0 0 0 0 0
        cpu0 0 0 0 0 0 0 0 0 0 0
        intr 0
        ctxt 0
        btime $bootTimeSeconds
        processes 0
        procs_running 1
        procs_blocked 0
        softirq 0 0 0 0 0 0 0 0 0 0 0
      """.trimIndent() + "\n",
      "loadavg" to "0.00 0.00 0.00 0/0 0\n",
    )

    /**
     * Read once per process so every session sees the same value; computing
     * it again could land on the neighbouring second.
     */
    private val DEVICE_BOOT_TIME_SECONDS: Long by lazy {
      runCatching { (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L }.getOrDefault(0L)
    }
  }
}
