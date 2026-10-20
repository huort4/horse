# Contributing to Horus

Horus runs coding agents and a Linux workspace on Android. Contributions that
make setup, terminal use, agent compatibility, documentation, and optional USB
remote access more reliable are welcome. Please follow our
[Code of Conduct](.github/CODE_OF_CONDUCT.md).

## Find the right place

Search [Discussions](https://github.com/scarif-labs/horus/discussions) and
[Issues](https://github.com/scarif-labs/horus/issues) before opening a topic.
Add information to an existing thread when it describes the same problem.

- **Q&A:** installation, troubleshooting, device compatibility, and how-to
  questions. Use a descriptive title, explain your goal, list what you tried,
  and include your Horus and Android versions, device, and affected agent or shell.
  Mark a helpful reply as the answer when your question is resolved.
- **Ideas:** feature requests and feedback. Describe the problem, who it affects,
  your current workaround, and what a useful result would look like.
- **General:** broader conversations about using Horus.
- **Show and tell:** projects, scripts, and workflows others can learn from.
- **Announcements:** official maintainer updates.

Technical RFCs belong in **Development & RFCs** once that category is configured.
Until then, discuss proposals in General and label the title `RFC:`. Include
context, a concrete design, alternatives, compatibility and security effects,
and a way to test the change. Check the [community setup notes](docs/community/README.md)
for the launch configuration status.

Use an **Issue** for a reproducible defect or a concrete, agreed piece of work.
A Discussion can help narrow a problem before an Issue is needed. Link the
related thread in both places. Report security concerns according to the
[security policy](.github/SECURITY.md), not in public troubleshooting threads.

## Report a bug

Use the [bug report form](https://github.com/scarif-labs/horus/issues/new?template=bug_report.yml)
when it is available, or include these details in a blank issue:

1. Horus version and installation source; commit SHA for a source build.
2. Android version/API level, device model, CPU architecture, and custom ROM
   if relevant. The documented app requirements are Android 7.0/API 24+ and
   ARM64 (`arm64-v8a`). Do not include a device serial or other unique identifier.
3. Affected agent and its version, plain shell, or optional host CLI version.
   Include the distribution/rootfs if known.
4. Numbered steps starting from a known state, a small reproducer, and whether
   the failure happens every time. Separate expected and actual behavior.
5. Relevant error text and a short, sanitized log excerpt, if available. For
   storage errors, include approximate free space; for background-session
   problems, describe battery restrictions and whether the screen was locked.

Never include passwords, tokens, SSH private keys, sign-in/device codes, private
repository contents, or personal information. Check screenshots and terminal
output before sharing. Do not reset your app or delete a workspace just to
collect a report; explain any recovery you have already tried.

## Propose a feature

Start in Ideas before investing in a large implementation. Link relevant issues,
explain a real workflow, and describe alternatives. Maintainers evaluate scope,
user benefit, Android and ARM64 compatibility, privacy and security, testing,
and ongoing maintenance cost. A request or RFC is input for discussion, not a
commitment to implement it or deliver it on a particular date.

## Submit a pull request

1. Fork the repository and create a focused branch from the current `main`.
2. Follow [Build from source](README.md#build-from-source) for prerequisites and
   submodule setup. Use `npm ci` for the application dependencies.
3. Keep the change focused. Explain behavior changes and update relevant docs.
   Follow the surrounding TypeScript, Kotlin, or native-code conventions.
4. Run checks appropriate to what changed. For application code, start with
   `npm run typecheck`, `npm run lint`, and `npm run test:unit`; for native
   Android behavior, also use `npm run test:kotlin` and device tests where
   relevant. Consult [Tests](README.md#tests) for commands. For documentation
   and forms, check links, YAML, paths, and the rendered text; Android builds
   are unnecessary.
5. Open a PR against `scarif-labs/horus:main`. Describe the problem, resulting
   behavior, related Issues/Discussions, validation, and any checks you could
   not run. Add sanitized screenshots for visible UI changes.

Use a descriptive commit message such as `docs: clarify device requirements`
or `fix: handle interrupted workspace downloads`. Keep generated binaries,
credentials, and unrelated formatting out of the diff.

Pull requests run Node validation (including the CLI tests) and an unsigned
Android release build with JVM tests. See [CI and releases](docs/maintainers/ci-and-releases.md)
for the exact local commands, toolchain, reports, and maintainer release gates.

Review may ask for a smaller scope, additional evidence, or an alternative
approach. Address comments in the same PR. Maintainers decide whether a change
fits the project and can be maintained; there is no guaranteed review time or
acceptance. Small documentation corrections and reproducible bug reports are
useful contributions too.
