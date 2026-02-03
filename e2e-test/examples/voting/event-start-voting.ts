export default (_context: Record<string, unknown>) => ({
  eventName: 'startVoting',
  payload: {
    candidates: ['Alice', 'Bob'],
  },
});
