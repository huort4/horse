#!/usr/bin/env node

const path = require('node:path');
const fs = require('node:fs');
const {spawnSync} = require('node:child_process');

const jestCommand = process.platform === 'win32' ? 'jest.cmd' : path.join('node_modules', '.bin', 'jest');
// Exercise a real assertion failure; the former target no longer exists and
// "no tests found" alone does not demonstrate failure propagation.
const directory = fs.mkdtempSync(path.join('__tests__', 'failure-probe-'));
try {
  const fixture = path.join(directory, 'probe.test.js');
  fs.writeFileSync(fixture, "test('HORUS_FORCE_TS_UNIT_FAILURE', () => { expect(true).toBe(false); });\n");
  const result = spawnSync(jestCommand, ['--runInBand', '--watchAll=false', '--runTestsByPath', fixture], {
    stdio: 'inherit',
  });
  process.exitCode = result.status === null ? 1 : result.status;
} finally {
  fs.rmSync(directory, {recursive: true, force: true});
}
