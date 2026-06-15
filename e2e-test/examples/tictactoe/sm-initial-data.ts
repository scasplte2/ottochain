import crypto from 'crypto';

export default (context: Record<string, unknown>) => {
  const session = context?.session as { scriptFiberId?: string } | undefined;
  const scriptFiberId = session?.scriptFiberId || crypto.randomUUID();

  return {
    scriptFiberId,
    roundCount: 0,
  };
};
