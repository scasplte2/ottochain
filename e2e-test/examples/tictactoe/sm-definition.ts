import crypto from 'crypto';

/**
 * Tic-Tac-Toe State Machine Definition
 *
 * Orchestrates the game lifecycle: setup -> playing -> finished/cancelled
 * Coordinates with the oracle via _oracleCall effects.
 *
 * The oracle fiberId is injected dynamically from context.session.oracleFiberId
 * so each test run uses a fresh oracle.
 */
export default (context: Record<string, unknown>) => {
  const session = context?.session as { oracleFiberId?: string } | undefined;
  const oracleFiberId = session?.oracleFiberId || crypto.randomUUID();

  return {
    states: {
      setup: {
        id: { value: 'setup' },
        isFinal: false,
        metadata: null,
      },
      playing: {
        id: { value: 'playing' },
        isFinal: false,
        metadata: null,
      },
      finished: {
        id: { value: 'finished' },
        isFinal: true,
        metadata: null,
      },
      cancelled: {
        id: { value: 'cancelled' },
        isFinal: true,
        metadata: null,
      },
    },
    initialState: { value: 'setup' },
    transitions: [
      // setup -> playing (start_game)
      {
        from: { value: 'setup' },
        to: { value: 'playing' },
        eventName: 'start_game',
        guard: {
          and: [
            { '!!': [{ var: 'event.playerX' }] },
            { '!!': [{ var: 'event.playerO' }] },
            { '!!': [{ var: 'event.gameId' }] },
          ],
        },
        effect: {
          _oracleCall: {
            fiberId: { var: 'state.oracleFiberId' },
            method: 'initialize',
            args: {
              playerX: { var: 'event.playerX' },
              playerO: { var: 'event.playerO' },
              gameId: { var: 'event.gameId' },
            },
          },
          gameId: { var: 'event.gameId' },
          playerX: { var: 'event.playerX' },
          playerO: { var: 'event.playerO' },
          status: 'initialized',
        },
        dependencies: [],
      },

      // playing -> playing (make_move, game continues)
      {
        from: { value: 'playing' },
        to: { value: 'playing' },
        eventName: 'make_move',
        guard: {
          '===': [{ var: `scripts.${oracleFiberId}.state.status` }, 'InProgress'],
        },
        effect: {
          _oracleCall: {
            fiberId: { var: 'state.oracleFiberId' },
            method: 'makeMove',
            args: {
              player: { var: 'event.player' },
              cell: { var: 'event.cell' },
            },
          },
          lastMove: {
            player: { var: 'event.player' },
            cell: { var: 'event.cell' },
          },
        },
        dependencies: [oracleFiberId],
      },

      // playing -> finished (make_move, game ends with win/draw)
      {
        from: { value: 'playing' },
        to: { value: 'finished' },
        eventName: 'make_move',
        guard: {
          or: [
            { '===': [{ var: `scripts.${oracleFiberId}.state.status` }, 'Won'] },
            { '===': [{ var: `scripts.${oracleFiberId}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _oracleCall: {
            fiberId: { var: 'state.oracleFiberId' },
            method: 'makeMove',
            args: {
              player: { var: 'event.player' },
              cell: { var: 'event.cell' },
            },
          },
          finalStatus: { var: `scripts.${oracleFiberId}.state.status` },
          winner: { var: `scripts.${oracleFiberId}.state.winner` },
          finalBoard: { var: `scripts.${oracleFiberId}.state.board` },
          _emit: [
            {
              name: 'game_completed',
              data: {
                gameId: { var: 'state.gameId' },
                winner: { var: `scripts.${oracleFiberId}.state.winner` },
                status: { var: `scripts.${oracleFiberId}.state.status` },
                moveCount: { var: `scripts.${oracleFiberId}.state.moveCount` },
              },
            },
          ],
        },
        dependencies: [oracleFiberId],
      },

      // playing -> playing (reset_board, start new round)
      {
        from: { value: 'playing' },
        to: { value: 'playing' },
        eventName: 'reset_board',
        guard: {
          or: [
            { '===': [{ var: `scripts.${oracleFiberId}.state.status` }, 'Won'] },
            { '===': [{ var: `scripts.${oracleFiberId}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _oracleCall: {
            fiberId: { var: 'state.oracleFiberId' },
            method: 'resetGame',
            args: {},
          },
          roundCount: { '+': [{ var: 'state.roundCount' }, 1] },
        },
        dependencies: [oracleFiberId],
      },

      // playing -> cancelled (cancel_game)
      {
        from: { value: 'playing' },
        to: { value: 'cancelled' },
        eventName: 'cancel_game',
        guard: { '==': [1, 1] },
        effect: {
          _oracleCall: {
            fiberId: { var: 'state.oracleFiberId' },
            method: 'cancelGame',
            args: {
              requestedBy: { var: 'event.requestedBy' },
              reason: { var: 'event.reason' },
            },
          },
          cancelledBy: { var: 'event.requestedBy' },
          cancelReason: { var: 'event.reason' },
        },
        dependencies: [],
      },

      // setup -> cancelled (cancel_game before start)
      {
        from: { value: 'setup' },
        to: { value: 'cancelled' },
        eventName: 'cancel_game',
        guard: { '==': [1, 1] },
        effect: {
          cancelledBy: { var: 'event.requestedBy' },
          cancelReason: { var: 'event.reason' },
        },
        dependencies: [],
      },
    ],
    metadata: {
      name: 'TicTacToeLifecycle',
      description: 'Game lifecycle orchestrator for tic-tac-toe',
    },
  };
};
