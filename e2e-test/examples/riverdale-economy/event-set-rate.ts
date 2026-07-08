/**
 * `set_rate` event for the FED (signed by erin). The fed's `set_rate` effect `_triggers` the bank's
 * `rate_adjustment` (targetMachineId resolves from `event.bankId`), propagating the new base rate
 * cross-fiber. The bank fiberId is resolved dynamically from `context.session.fibers.bank`.
 */
export default (context: {
  session: { fibers: Record<string, string> };
}): { eventName: string; payload: Record<string, unknown> } => ({
  eventName: 'set_rate',
  payload: {
    bankId: context.session.fibers.bank,
    newBaseRate: 0.045,
  },
});
