import {installStepForStage, lastDownloadProgress, lastInstallStage} from '../src/terminal/InstallProgressOverlay';

describe('install progress stages', () => {
  test('maps provisioning stages onto the four visible steps', () => {
    expect(installStepForStage('start', 'shell')).toBe(0);
    expect(installStepForStage('apk', 'shell')).toBe(1);
    expect(installStepForStage('github', 'shell')).toBe(1);
    expect(installStepForStage('github', 'github')).toBe(2);
    for (const stage of ['base_ready', 'normalize', 'verify']) {
      expect(installStepForStage(stage, 'shell')).toBe(2);
    }
    expect(installStepForStage('ready', 'shell')).toBe(3);
  });

  test('reads the last stage marker in a chunk', () => {
    expect(lastInstallStage('(1/7) Installing ada-libs\n')).toBeUndefined();
    expect(lastInstallStage('HORUS_INSTALL_STAGE=start\nHORUS_INSTALL_STAGE=apk\n')).toBe('apk');
    expect(lastInstallStage('noise HORUS_INSTALL_STAGE=shell_failed\n')).toBe('shell_failed');
  });

  test('reads the latest download or package progress in a chunk', () => {
    expect(lastDownloadProgress('HORUS_INSTALL_STAGE=shell\n')).toBeUndefined();
    expect(lastDownloadProgress('Downloading Linux rootfs… 12 MB\nDownloading Linux rootfs… 84 MB\n')).toBe('84 MB downloaded');
    expect(lastDownloadProgress('Downloading GitHub CLI… 7 MB\r\n')).toBe('7 MB downloaded');
    expect(lastDownloadProgress('Downloading Linux rootfs… 4% (6 of 148 MB)\nDownloading Linux rootfs… 37% (55 of 148 MB)\n')).toBe('37% downloaded');
    expect(lastDownloadProgress('Downloading Linux rootfs… 12 MB\nDownloading Linux rootfs… 100% (231 of 231 MB)\r\n')).toBe('100% downloaded');
    // System packages download, then install.
    expect(lastDownloadProgress('Downloading packages… 90%\nInstalling packages… 12%\n')).toBe('12% installed');
  });
});
