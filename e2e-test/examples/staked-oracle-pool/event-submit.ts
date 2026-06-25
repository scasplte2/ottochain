// A joined participant appends a numeric datapoint {addr, value} to this epoch's submissions array
// (append via merge[submissions, [record]]). Guard: joined participant + signer-pinned + within window +
// not-yet-submitted-this-epoch (none-over-array dedup). COLLECTING -> COLLECTING.
//
// eventData picks the submitter wallet and the value. Defaults form a 3-clustered + 1-outlier dataset:
//   alice=100, bob=102, carol=98, dave=500  (outlierBound=10, center≈100 → dave evicted).
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;
  const keys = Object.keys(wallets);
  const submitter = (eventData?.wallet as string) || keys[1] || keys[0];
  const w = wallets[submitter] || wallets[keys[0]];
  return {
    eventName: "submit",
    payload: {
      agent: w.address,
      value: (eventData?.value as number) ?? 100,
    },
  };
};
