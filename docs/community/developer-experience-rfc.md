# RFC: Improving the Horus developer experience

**Prepared draft — not published.** Publish only after an open-discussion
**Development & RFCs** category exists, and first check for an equivalent topic.

---

This RFC asks for evidence about the path from cloning Horus to producing a
useful, tested contribution. It is a discussion starter, not an approved work
plan or a release commitment.

Horus combines a React Native UI, Kotlin runtime code, JNI terminal code, and
PRoot components built from source. The [build instructions](https://github.com/scarif-labs/horus#build-from-source)
require Node.js, JDK 17, Android SDK/NDK tooling, and initialized submodules.
The repository also provides TypeScript, Kotlin, and connected-device tests.
Which steps are clear today, and which make your first contribution harder?

## Questions for contributors

1. **First build:** where did you get stuck, using which host OS, tool versions,
   and commit? Was the missing information a prerequisite, submodule step,
   environment mismatch, or an unclear error? Share a short sanitized excerpt.
2. **Choosing checks:** can you tell which tests are necessary for a documentation,
   React Native, Kotlin, or native-runtime change? Which small check would have
   caught a mistake before a full Android build?
3. **Runtime diagnosis:** when an agent fails to install or a session exits, what
   minimum non-sensitive evidence would let someone reproduce the problem?
   Consider agent/package versions, rootfs identity, free storage, and process
   state before collecting more logs.
4. **Device feedback:** what is the smallest reliable test on a phone for your
   change? Distinguish emulator results from a real ARM64 device and explain
   background or battery settings that matter.
5. **Documentation:** name one missing example, confusing step, or command whose
   output could better explain what to do next.

Recent Issues provide useful concrete cases: [Codex ARM64 installation #6](https://github.com/scarif-labs/horus/issues/6)
and its [open fix PR #9](https://github.com/scarif-labs/horus/pull/9),
[GitHub CLI prompt handling #2](https://github.com/scarif-labs/horus/issues/2),
and [storage exhaustion #11](https://github.com/scarif-labs/horus/issues/11).
Please add evidence to those threads for their specific failures. Linking them
here does not imply a fix has shipped.

## A useful proposal

Choose one pain point and reply with the current steps, a proposed improvement,
an alternative, the files or workflow affected, and a measurable way to verify
it. For example, a prerequisite check should identify a concrete missing tool,
explain a corrective step, and be testable without building the app.

Consider ongoing maintenance, Android/ARM64 coverage, privacy, and security.
Prefer a focused improvement that fits the existing build and test workflow.
Do not collect credentials, private terminal input, project contents, or device
identifiers as diagnostics. Any automation proposal should state what it reads
and where its output goes.

Maintainers can use this feedback to identify scoped follow-up work. This RFC
sets no deadlines and promises no particular implementation. What is the one
step you would improve first, and what evidence would show that it helped?
