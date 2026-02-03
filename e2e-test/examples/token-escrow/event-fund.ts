export default (_context: Record<string, unknown>) => ({
  eventName: 'fund',
  payload: {
    depositor: 'alice',
    amount: 100,
  },
});
