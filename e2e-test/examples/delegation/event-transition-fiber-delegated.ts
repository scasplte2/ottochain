/**
 * E2E Test Event: Transition Fiber Using Delegation
 * 
 * This event represents an agent transitioning a state machine on behalf of a principal
 * using delegation authority. This tests that delegation works for both creation and
 * ongoing management of state machines.
 * 
 * Test Validation:
 * - Transaction is signed by the delegate (agent)
 * - State transition occurs successfully
 * - Delegation usage count is incremented again
 * - Principal remains owner of the fiber
 */

import { OttochainMessage } from '../../../types';

export const transitionFiberDelegatedEvent: OttochainMessage = {
  type: 'TransitionStateMachine',
  data: {
    fiberId: 'fiber_delegated_e2e_test_789', // Same fiber as created in previous step
    eventName: 'activate',
    eventData: {
      activatedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent
      activatedFor: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal
      timestamp: 1640995320000, // 2 minutes after creation
      reason: 'E2E delegation test - activating via delegation'
    },
    expectedFromState: 'created',
    expectedToState: 'active'
  },
  signature: {
    signedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent signs the transaction
    signature: '0xfedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321fe',
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
  ordinal: 1003,
  timestamp: 1640995320000, // 2 minutes after delegation grant
  gasConfig: {
    gasLimit: 75000, // Within delegation limit
    gasPrice: 1000000000
  },
  expectedValidation: {
    result: 'success',
    stateTransition: {
      from: 'created',
      to: 'active',
      event: 'activate'
    },
    stateChanges: {
      'currentState': 'active',
      'lastTransition': {
        'event': 'activate',
        'ordinal': 1003,
        'performedBy': 'DAG789def012ghi345jkl678mno901pqr234stu567',
        'onBehalfOf': 'DAG123abc456def789ghi012jkl345mno678pqr901'
      }
    },
    delegationUpdates: {
      'del_e2e_test_123456789abcdef': {
        'usageCount': 2, // Incremented from 1 to 2
        'lastUsed': 1003,
        'totalGasUsed': 160000 // 85000 + 75000
      }
    },
    events: [
      {
        type: 'StateMachineTransitioned',
        fiberId: 'fiber_delegated_e2e_test_789',
        fromState: 'created',
        toState: 'active',
        event: 'activate',
        performer: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        onBehalfOf: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegationUsed: 'del_e2e_test_123456789abcdef'
      }
    ]
  }
};

export const testAssertions = {
  // State transition should succeed
  stateTransitionSuccessful: (fiberId: string, fromState: string, toState: string) => {
    return `fiber[${fiberId}] should transition from ${fromState} to ${toState}`;
  },

  // Transaction should be signed by delegate
  transactionSignedByDelegate: (delegateAddr: string) => {
    return `transaction should be signed by delegate ${delegateAddr}`;
  },

  // Principal should remain owner despite delegate performing action
  principalRemainsOwner: (fiberId: string, principalAddr: string) => {
    return `fiber[${fiberId}].owners should still include ${principalAddr}`;
  },

  // Usage count should be incremented to 2
  usageCountIncrementedToTwo: (delegationId: string) => {
    return `delegation[${delegationId}].usageCount should be 2`;
  },

  // Total gas used should be tracked
  totalGasTracked: (delegationId: string, expectedGas: number) => {
    return `delegation[${delegationId}].totalGasUsed should be ${expectedGas}`;
  },

  // Delegation should still be within scope limits
  stillWithinScopeLimits: (delegationId: string) => {
    return `delegation[${delegationId}] should still be within all scope limits`;
  },

  // Transition should be recorded with delegation context
  transitionRecordedWithDelegation: (fiberId: string, delegationId: string) => {
    return `fiber[${fiberId}].lastTransition should reference delegation ${delegationId}`;
  },

  // Agent should be recorded as performer
  agentRecordedAsPerformer: (fiberId: string, agentAddr: string) => {
    return `fiber[${fiberId}].lastTransition.performedBy should be ${agentAddr}`;
  }
};

export default transitionFiberDelegatedEvent;