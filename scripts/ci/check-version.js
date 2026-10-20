'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');

const gradle = fs.readFileSync('android/app/build.gradle', 'utf8');
const versionName = gradle.match(/^\s*versionName "([^"]+)"/m)?.[1];
const versionCode = Number(gradle.match(/^\s*versionCode (\d+)/m)?.[1]);
assert.match(versionName || '', /^\d+\.\d+\.\d+$/, 'Expected an Android X.Y.Z versionName');
assert.ok(Number.isSafeInteger(versionCode) && versionCode > 0, 'Expected a positive versionCode');
assert.equal(require('../../package.json').version, versionName, 'package.json and Android versions differ');
const lock = require('../../package-lock.json');
assert.equal(lock.version, versionName, 'Lockfile version differs from Android');
assert.equal(lock.packages[''].version, versionName, 'Lockfile root package version differs from Android');
const changelog = `fastlane/metadata/android/en-US/changelogs/${versionCode}.txt`;
assert.ok(fs.readFileSync(changelog, 'utf8').trim(), `Missing or empty ${changelog}`);
console.log(`Version ${versionName} (${versionCode}): package and changelog agree`);

module.exports = {versionName, versionCode};
