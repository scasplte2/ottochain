import crypto from 'crypto';

/**
 * Tic-Tac-Toe State Machine Definition
 *
 * Orchestrates the game lifecycle: setup -> playing -> finished/cancelled
 * Coordinates with the script via _scriptCall effects.
 *
 * The script fiberId is injected dynamically from context.session.scriptFiberId
 * so each test run uses a fresh script.
 */
export default (context: Record<string, unknown>) => {
  const session = context?.session as { scriptFiberId?: string } | undefined;
  const scriptFiberId = session?.scriptFiberId || crypto.randomUUID();

  return {
    states: {
      setup: {
        id: 'setup',
        isFinal: false,
        metadata: null,
      },
      playing: {
        id: 'playing',
        isFinal: false,
        metadata: null,
      },
      finished: {
        id: 'finished',
        isFinal: true,
        metadata: null,
      },
      cancelled: {
        id: 'cancelled',
        isFinal: true,
        metadata: null,
      },
    },
    initialState: 'setup',
    transitions: [
      // setup -> playing (start_game)
      {
        from: 'setup',
        to: 'playing',
        eventName: 'start_game',
        guard: {
          and: [
            { '!!': [{ var: 'event.playerX' }] },
            { '!!': [{ var: 'event.playerO' }] },
            { '!!': [{ var: 'event.gameId' }] },
          ],
        },
        effect: {
          _scriptCall: {
            fiberId: { var: 'state.scriptFiberId' },
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
        from: 'playing',
        to: 'playing',
        eventName: 'make_move',
        guard: {
          '===': [{ var: `scripts.${scriptFiberId}.state.status` }, 'InProgress'],
        },
        effect: {
          _scriptCall: {
            fiberId: { var: 'state.scriptFiberId' },
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
        dependencies: [scriptFiberId],
      },

      // playing -> finished (make_move, game ends with win/draw)
      {
        from: 'playing',
        to: 'finished',
        eventName: 'make_move',
        guard: {
          or: [
            { '===': [{ var: `scripts.${scriptFiberId}.state.status` }, 'Won'] },
            { '===': [{ var: `scripts.${scriptFiberId}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _scriptCall: {
            fiberId: { var: 'state.scriptFiberId' },
            method: 'makeMove',
            args: {
              player: { var: 'event.player' },
              cell: { var: 'event.cell' },
            },
          },
          finalStatus: { var: `scripts.${scriptFiberId}.state.status` },
          winner: { var: `scripts.${scriptFiberId}.state.winner` },
          finalBoard: { var: `scripts.${scriptFiberId}.state.board` },
          _emit: [
            {
              name: 'game_completed',
              data: {
                gameId: { var: 'state.gameId' },
                winner: { var: `scripts.${scriptFiberId}.state.winner` },
                status: { var: `scripts.${scriptFiberId}.state.status` },
                moveCount: { var: `scripts.${scriptFiberId}.state.moveCount` },
              },
            },
          ],
        },
        dependencies: [scriptFiberId],
      },

      // playing -> playing (reset_board, start new round)
      {
        from: 'playing',
        to: 'playing',
        eventName: 'reset_board',
        guard: {
          or: [
            { '===': [{ var: `scripts.${scriptFiberId}.state.status` }, 'Won'] },
            { '===': [{ var: `scripts.${scriptFiberId}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _scriptCall: {
            fiberId: { var: 'state.scriptFiberId' },
            method: 'resetGame',
            args: {},
          },
          roundCount: { '+': [{ var: 'state.roundCount' }, 1] },
        },
        dependencies: [scriptFiberId],
      },

      // playing -> cancelled (cancel_game)
      {
        from: 'playing',
        to: 'cancelled',
        eventName: 'cancel_game',
        guard: { '==': [1, 1] },
        effect: {
          _scriptCall: {
            fiberId: { var: 'state.scriptFiberId' },
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
        from: 'setup',
        to: 'cancelled',
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
