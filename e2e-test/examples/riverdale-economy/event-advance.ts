/**
 * `advance` event for the FLOW-2 negative-test fiber — a ONE-WAY transition s0 → s1. Used by the
 * replay / seq-regression test: the first `advance` succeeds (s0 → s1); re-submitting `advance` afterward
 * finds the fiber in s1 with no `advance` transition there → NoTransitionForEvent, rejected at ML0
 * (transitionExists runs in the L0Validator), so the fiber's sequence number never advances past the first.
 */
export default (): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'advance',
  payload: {},
});
