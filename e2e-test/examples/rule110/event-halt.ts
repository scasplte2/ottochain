// `halt` is gated by `gen >= maxGen`: it is REJECTED while the CA is still
// running and only succeeds once the run-length bound is exhausted, moving the
// fiber RUNNING -> HALTED (terminal).
export default () => ({
  eventName: "halt",
  payload: {},
});
