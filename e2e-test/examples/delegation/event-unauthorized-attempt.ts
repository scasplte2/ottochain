/**
 * E2E Test Event: Unauthorized Delegation Attempt
 * 
 * This event represents an unauthorized actor attempting to perform an operation
 * using delegation they don't have. This should FAIL with a specific error
 * indicating that no valid delegation exists for the signer.
 * 
 * Test Validation:
 * - Operation is rejected with DELEGATION_NOT_FOUND error
 * - No state changes occur
 * - Error message indicates missing delegation
 * - Fiber remains unchanged
 */

import { OttochainMessage } from '../../../types';

export const unauthorizedAttemptEvent: OttochainMessage = {
  type: 'TransitionStateMachine',
  data: {
    fiberId: 'fiber_delegated_e2e_test_789', // Try to transition the same fiber
    eventName: 'complete',
    eventData: {
      completedBy: 'DAG999unauthorized1234567890abcdef1234567890ab', // Unauthorized actor
      completedFor: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal (but no delegation exists)
      timestamp: 1640995500000, // 5 minutes after delegation grant
      reason: 'E2E test - unauthorized attempt (should fail)'
    },
    expectedFromState: 'active',
    expectedToState: 'completed'
  },
  signature: {
    signedBy: 'DAG999unauthorized1234567890abcdef1234567890ab', // Unauthorized actor attempts to sign
    signature: '0xunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorized',
    publicKey: '0x04unauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorizedunauthorized'
  },
  delegationContext: {
    principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
    delegateAddress: 'DAG999unauthorized1234567890abcdef1234567890ab', // No delegation exists for this address
    delegationId: 'del_nonexistent_unauthorized', // This delegation doesn't exist
    isDelegate: true, // Claims to be delegate but has no valid delegation
    scope: {
      allowedOperations: ['CreateStateMachine', 'TransitionStateMachine'],
      maxGasPerTx: 100000
    }
  },
  ordinal: 1006,
  timestamp: 1640995500000, // 5 minutes after delegation grant
  gasConfig: {
    gasLimit: 75000,
    gasPrice: 1000000000
  },
  expectedValidation: {
    result: 'error',
    error: {
      code: 'DELEGATION_NOT_FOUND',
      message: 'No valid delegation found for delegate DAG999unauthorized1234567890abcdef1234567890ab acting on behalf of principal DAG123abc456def789ghi012jkl345mno678pqr901',
      details: {
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        attemptedDelegateAddress: 'DAG999unauthorized1234567890abcdef1234567890ab',
        attemptedDelegationId: 'del_nonexistent_unauthorized',
        attemptedOperation: 'TransitionStateMachine',
        attemptedAt: 1006,
        availableDelegations: [
          {
            delegationId: 'del_e2e_test_123456789abcdef',
            delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Different delegate
            isActive: true
          }
        ]
      }
    },
    stateChanges: {}, // No state changes should occur
    delegationUpdates: {}, // No delegation updates should occur
    events: [
      {
        type: 'UnauthorizedDelegationAttempt',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        attemptedBy: 'DAG999unauthorized1234567890abcdef1234567890ab',
        attemptedOperation: 'TransitionStateMachine',
        fiberId: 'fiber_delegated_e2e_test_789',
        rejectedAt: 1006,
        reason: 'DELEGATION_NOT_FOUND'
      }
    ]
  }
};

export const testAssertions = {
  // Operation should be rejected with not found error
  operationRejectedWithNotFoundError: (errorCode: string) => {
    return `operation should be rejected with error code: ${errorCode}`;
  },

  // Error should identify the unauthorized actor
  errorIdentifiesUnauthorizedActor: (unauthorizedAddr: string, principalAddr: string) => {
    return `error should identify unauthorized delegate ${unauthorizedAddr} attempting to act for principal ${principalAddr}`;
  },

  // Fiber state should remain unchanged
  fiberStateUnchanged: (fiberId: string, expectedState: string) => {
    return `fiber[${fiberId}] state should remain ${expectedState}`;
  },

  // No delegation updates should occur
  noDelegationUpdates: () => {
    return `no delegation usage counts should be incremented for unauthorized attempts`;
  },

  // No gas should be consumed
  noGasConsumedForUnauthorizedAttempt: () => {
    return `no gas should be consumed for unauthorized delegation attempts`;
  },

  // Unauthorized attempt event should be emitted
  unauthorizedAttemptEventEmitted: (attemptedBy: string, reason: string) => {
    return `UnauthorizedDelegationAttempt event should be emitted for ${attemptedBy} with reason: ${reason}`;
  },

  // Transaction should be marked as rejected
  transactionMarkedAsRejected: (ordinal: number) => {
    return `transaction at ordinal ${ordinal} should be marked as rejected`;
  },

  // Available delegations should be listed in error (for debugging)
  availableDelegationsListedInError: (principalAddr: string) => {
    return `error should list available delegations for principal ${principalAddr}`;
  },

  // Security audit trail should be created
  securityAuditTrailCreated: (unauthorizedAddr: string, principalAddr: string) => {
    return `security audit trail should record unauthorized attempt by ${unauthorizedAddr} for principal ${principalAddr}`;
  },

  // Valid delegations should remain unaffected
  validDelegationsUnaffected: (validDelegationId: string) => {
    return `valid delegation ${validDelegationId} should remain unaffected by unauthorized attempt`;
  }
};

export default unauthorizedAttemptEvent;