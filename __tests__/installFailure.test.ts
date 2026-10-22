import {classifyInstallFailure, cleanInstallLog, installFailureCopy} from '../src/terminal/installFailure';

describe('installFailure', () => {
  test('trusts Android about being offline, then reads the log', () => {
    expect(classifyInstallFailure('', 'github_failed', false)).toBe('offline');
    expect(classifyInstallFailure('curl: (6) Could not resolve host: deb.debian.org', 'github_failed', true)).toBe('network');
    expect(classifyInstallFailure('curl: (6) Could not resolve host: github.com', 'github_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('E: Failed to fetch http://deb.debian.org/debian/pool/x.deb', 'apk_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('curl: (28) Failed to fetch: ENOSPC', 'github_failed', true)).toBe('storage');
    expect(classifyInstallFailure('EACCES: permission denied', 'github_failed', true)).toBe('permissions');
    expect(classifyInstallFailure('ERROR: unsatisfiable constraints', 'apk_failed', true)).toBe('packages');
    expect(classifyInstallFailure('', 'base_ready', true)).toBe('unknown');
  });

  test('cleans escapes, markers, redraws, and repeated progress from the log', () => {
    const raw = '\u001b[32mHORUS_INSTALL_STAGE=github\u001b[0m\r\nDownloading packages… 10%\nDownloading packages… 20%\nhalf\rwhole\ncurl: (6) boom\n';
    expect(cleanInstallLog(raw)).toBe('Downloading packages… 20%\nwhole\ncurl: (6) boom');
    expect(cleanInstallLog('HORUS_INSTALL_STAGE=start\n')).toBeUndefined();
  });

  test('points to mirrors and names the toolchain that failed', () => {
    expect(installFailureCopy({kind: 'network'}, 'github').advice).toContain('Settings › Downloads');
    expect(installFailureCopy({kind: 'offline'}, 'github')).toEqual(expect.objectContaining({title: 'Couldn’t install GitHub CLI', reason: 'Your phone is offline.'}));
    expect(installFailureCopy({kind: 'offline'}, 'shell')).toEqual(expect.objectContaining({title: 'Couldn’t install Linux shell', reason: 'Your phone is offline.'}));
  });
});
