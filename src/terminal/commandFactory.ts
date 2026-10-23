/** Fixed, non-secret guest commands used by launcher actions. */
const restoreEcho = 'stty echo 2>/dev/null || true';
const requireZsh = "if ! command -v zsh >/dev/null 2>&1; then printf '%s\\n' 'zsh is not available yet; close and reopen this terminal to retry' >&2; exit 127; fi";

export function buildZshCommand(command?: string): string {
  if (command === undefined) return `${restoreEcho}; ${requireZsh}; exec zsh -l`;
  const quoted = command.replace(/'/g, "'\\''");
  return `${restoreEcho}; ${requireZsh}; exec zsh -lic '${quoted}'`;
}


/** Runs a fixed guest script without loading interactive or login startup files. */
export function buildZshScriptCommand(command: string): string {
  const quoted = command.replace(/'/g, "'\\''");
  return `${restoreEcho}; ${requireZsh}; exec zsh -fc '${quoted}'`;
}

export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}
