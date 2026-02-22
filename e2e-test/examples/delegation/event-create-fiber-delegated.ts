/**
 * E2E Test Event: Create Fiber Using Delegation
 * 
 * This event represents an agent creating a state machine on behalf of a principal
 * using delegation authority. The transaction should be signed by the agent but
 * executed with the principal's permissions.
 * 
 * Test Validation:
 * - Transaction is signed by the delegate (agent)
 * - Fiber is created with principal as owner
 * - Delegation context is properly attached
 * - Usage count is incremented
 */

import { OttochainMessage } from '../../../types';

export const createFiberDelegatedEvent: OttochainMessage = {
  type: 'CreateStateMachine',
  data: {
    fiberId: 'fiber_delegated_e2e_test_789',
    definition: {
      name: 'DelegatedTestFiber',
      initialState: 'created',
      states: {
        'created': { isFinal: false, allowedTransitions: ['active', 'cancelled'] },
        'active': { isFinal: false, allowedTransitions: ['completed', 'cancelled'] },
        'completed': { isFinal: true, allowedTransitions: [] },
        'cancelled': { isFinal: true, allowedTransitions: [] }
      },
      transitions: [
        {
          eventName: 'activate',
          from: { value: 'created' },
          to: { value: 'active' },
          guard: { '===': [1, 1] } // Always allow for test
        },
        {
          eventName: 'complete',
          from: { value: 'active' },
          to: { value: 'completed' },
          guard: { '===': [1, 1] } // Always allow for test
        },
        {
          eventName: 'cancel',
          from: { anyOf: ['created', 'active'] },
          to: { value: 'cancelled' },
          guard: { '===': [1, 1] } // Always allow for test
        }
      ]
    },
    initialData: {
      owner: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal
      createdBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent (delegated)
      purpose: 'E2E delegation testing',
      metadata: {
        testCase: 'delegated-creation',
        delegationId: 'del_e2e_test_123456789abcdef'
      }
    }
  },
  signature: {
    signedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent signs the transaction
    signature: '0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab',
    publicKey: '0x04789def012ghi345jkl678mno901pqr234stu567vwx890yzabcdef123456789012345678901234567890abcdef1234567890abcdef1234567890abcdef123456789'
  },
  delegationContext: {
    principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
    delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
    delegationId: 'del_e2e_test_123456789abcdef',
    isDelegate: true,
    scope: {
      allowedOperations: ['CreateStateMachine', 'TransitionStateMachine'],
      maxGasPerTx: 100000
    }
  },
  ordinal: 1002,
  timestamp: 1640995260000, // 1 minute after delegation grant
  gasConfig: {
    gasLimit: 85000, // Within delegation limit
    gasPrice: 1000000000
  },
  expectedValidation: {
    result: 'success',
    newState: 'created',
    stateChanges: {
      'fiberId': 'fiber_delegated_e2e_test_789',
      'currentState': 'created',
      'owners': ['DAG123abc456def789ghi012jkl345mno678pqr901'] // Principal is owner
    },
    delegationUpdates: {
      'del_e2e_test_123456789abcdef': {
        'usageCount': 1,
        'lastUsed': 1002
      }
    },
    events: [
      {
        type: 'StateMachineCreated',
        fiberId: 'fiber_delegated_e2e_test_789',
        owner: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        createdBy: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        delegationUsed: 'del_e2e_test_123456789abcdef'
      }
    ]
  }
};

export const testAssertions = {
  // Fiber should be created with principal as owner
  fiberCreatedWithPrincipalOwner: (fiberId: string, principalAddr: string) => {
    return `fiber[${fiberId}].owners should include ${principalAddr}`;
  },

  // Transaction should be signed by delegate
  transactionSignedByDelegate: (delegateAddr: string) => {
    return `transaction should be signed by delegate ${delegateAddr}`;
  },

  // Delegation context should be attached
  delegationContextAttached: (delegationId: string, principalAddr: string) => {
    return `transaction.delegationContext should reference delegation ${delegationId} and principal ${principalAddr}`;
  },

  // Usage count should be incremented
  usageCountIncremented: (delegationId: string, expectedCount: number) => {
    return `delegation[${delegationId}].usageCount should be ${expectedCount}`;
  },

  // Gas usage should be within delegation limits
  gasWithinLimits: (gasUsed: number, gasLimit: number) => {
    return `gas used (${gasUsed}) should be <= delegation limit (${gasLimit})`;
  },

  // Delegation should still be active after use
  delegationStillActive: (delegationId: string) => {
    return `delegation[${delegationId}].isActive should remain true`;
  }
};

export default createFiberDelegatedEvent;