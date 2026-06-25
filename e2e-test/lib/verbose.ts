/**
 * Verbose-only logging for the e2e runner.
 *
 * Per-step validation SUCCESS lines (`Event receipt verified`, `Fiber creation validated`, …) are
 * suppressed by default. They are redundant with the flow's own `OK`/`PASS` output, they print once
 * per ML0 node, and they `console.log` immediately — bypassing the buffered `FlowLogger` — so in
 * parallel mode they interleave with other flows and the keepalive heartbeat into unreadable noise.
 *
 * Failures are unaffected: every validator still THROWS on a real problem (with the full diagnostic),
 * which is what fails the step. Set `E2E_VERBOSE=1` to restore the per-validation success lines when
 * debugging a specific flow.
 */
export const E2E_VERBOSE = !!process.env.E2E_VERBOSE;

/** `console.log`, but only when `E2E_VERBOSE` is set. */
export function vlog(message: string): void {
  if (E2E_VERBOSE) console.log(message);
}
