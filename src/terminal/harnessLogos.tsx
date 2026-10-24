import React from 'react';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {BrandMark} from './BrandMark';

/**
 * Agent logos removed for offline / low-bandwidth use.
 * Only the bare shell and GitHub CLI remain.
 */
export const HARNESS_LOGO_SOURCES = {} as const;

export type HarnessLogo = never;

export function HarnessMark({
  toolchain: _toolchain,
  size,
}: Readonly<{toolchain: TerminalToolchainTarget; size: number}>): React.JSX.Element {
  return <BrandMark accessible={false} size={size} />;
}
