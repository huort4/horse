import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {AppExitedOverlay, appExitKind, type AppExit} from '../src/terminal/AppExitedOverlay';

test.each([
  ['HTTP 401: Bad credentials', 'gh auth login --hostname github.com'],
  ['? Hostname: invalid-host', 'GitHub Enterprise hostname'],
  ["Cloning into '/workspace/project'...\nReceiving objects: 81%", 'new empty folder'],
  ["proot error: can't create temporary file: No space left on device", 'Free device storage'],
])('GitHub CLI error offers retry advice without a crash report: %s', async (output, advice) => {
  const restart = jest.fn();
  const report = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<AppExitedOverlay exit={{exitCode: 1, output}} toolchain="github" onRestart={restart} onReport={report} />);
  });
  expect(renderer.root.findAllByProps({children: 'GitHub CLI command failed'}).length).toBeGreaterThan(0);
  expect(renderer.root.findByProps({testID: 'terminal-app-exited-advice'}).props.children).toContain(advice);
  expect(renderer.root.findAllByProps({testID: 'terminal-app-report'})).toHaveLength(0);
  expect(renderer.root.findByProps({testID: 'terminal-app-exited-output'}).props.children).toBe(output);
  await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID: 'terminal-app-restart'}).props.onPress(); });
  expect(restart).toHaveBeenCalledTimes(1);
  expect(report).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => { renderer.unmount(); });
});

test.each<AppExit>([
  {exitCode: 137}, {signal: 'sigkill'}, {signal: 'sigsegv'},
  {exitCode: 2, output: 'panic: invalid memory address'},
  {exitCode: 1, output: 'proot error: ptrace failed'},
  {exitCode: 1, output: "could not prompt: unexpected escape sequence: ['\\x1b' '\\t']"},
])('real process faults remain reportable: %j', exit => {
  expect(appExitKind('github', exit)).toBe('unexpected');
});

test('normal exits, ordinary shell command errors and GitHub interrupts stay distinct', () => {
  expect(appExitKind('shell', {exitCode: 0, output: 'Build failed\nexit'})).toBe('closed');
  expect(appExitKind('shell', {exitCode: 1, output: 'Build failed'})).toBe('command_failed');
  expect(appExitKind('github', {exitCode: 130})).toBe('interrupted');
  expect(appExitKind('github', {signal: 'sigint'})).toBe('interrupted');
  expect(appExitKind('codex', {exitCode: 1})).toBe('unexpected');
});
