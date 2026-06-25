// One `step` event = one Rule-110 generation. The transition computes
// next[i] = R110(tape[i-1], tape[i], tape[i+1]) in place over the stored index
// array and increments `gen`. No payload is needed — the substrate is public and
// permissionless, so the event carries nothing but its name.
export default () => ({
  eventName: "step",
  payload: {},
});
