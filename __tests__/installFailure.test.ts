import {classifyInstallFailure, cleanInstallLog, installFailureCopy} from '../src/terminal/installFailure';

describe('installFailure', () => {
  test('trusts Android about being offline, then reads the log', () => {
    expect(classifyInstallFailure('', 'shell_failed', false)).toBe('offline');
    expect(classifyInstallFailure('npm error code ENOTFOUND', 'shell_failed', true)).toBe('network');
    expect(classifyInstallFailure('curl: (6) Could not resolve host: claude.ai', 'shell_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('E: Failed to fetch http://deb.debian.org/debian/pool/x.deb', 'apk_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('npm error code ENOSPC', 'shell_failed', true)).toBe('storage');
    expect(classifyInstallFailure('npm warn EBADENGINE Unsupported engine', 'shell_failed', true)).toBe('node_version');
    expect(classifyInstallFailure('EACCES: permission denied', 'shell_failed', true)).toBe('permissions');
    expect(classifyInstallFailure('ERROR: unsatisfiable constraints', 'apk_failed', true)).toBe('packages');
    expect(classifyInstallFailure('', 'shell_failed', true)).toBe('unknown');
  });

  test('cleans escapes, markers, redraws, and repeated progress from the log', () => {
    const raw = '\u001b[32mHORUS_INSTALL_STAGE=shell\u001b[0m\r\nDownloading Linux rootfs… 10%\nDownloading Linux rootfs… 20%\nhalf\rwhole\nnpm error boom\n';
    expect(cleanInstallLog(raw)).toBe('Downloading Linux rootfs… 20%\nwhole\nnpm error boom');
    expect(cleanInstallLog('HORUS_INSTALL_STAGE=start\n')).toBeUndefined();
  });

  test('names the toolchain in the failure copy and always offers a mirror for network failures', () => {
    expect(installFailureCopy({kind: 'network'}, 'shell').advice).toContain('Settings › Downloads');
    expect(installFailureCopy({kind: 'network'}, 'github').advice).toContain('Settings › Downloads');
    expect(installFailureCopy({kind: 'offline'}, 'shell')).toEqual(expect.objectContaining({title: 'Couldn’t install Linux shell', reason: 'Your phone is offline.'}));
    expect(installFailureCopy({kind: 'offline'}, 'github').title).toBe('Couldn’t install GitHub CLI');
  });
});
