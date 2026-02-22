/**
 * E2E Test Event: Attempt Operation After Delegation Revocation
 * 
 * This event represents an agent attempting to perform an operation after their
 * delegation has been revoked. This operation should FAIL with a specific error
 * indicating that the delegation is no longer valid.
 * 
 * Test Validation:
 * - Operation is rejected with DELEGATION_REVOKED error
 * - No state changes occur
 * - Error message includes delegation ID and revocation details
 * - Fiber remains unchanged
 */

import { OttochainMessage } from '../../../types';

export const attemptAfterRevocationEvent: OttochainMessage = {
  type: 'TransitionStateMachine',
  data: {
    fiberId: 'fiber_delegated_e2e_test_789', // Try to transition the same fiber
    eventName: 'complete',
    eventData: {
      completedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent (should fail)
      completedFor: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal
      timestamp: 1640995440000, // 4 minutes after delegation grant, 1 minute after revocation
      reason: 'E2E test - attempting completion after revocation (should fail)'
    },
    expectedFromState: 'active',
    expectedToState: 'completed'
  },
  signature: {
    signedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent attempts to sign
    signature: '0x1122334455667788991122334455667788991122334455667788991122334455667788991122334455667788991122334455667788991122334455667788991122',
    publicKey: '0x04789def012ghi345jkl678mno901pqr234stu567vwx890yzabcdef123456789012345678901234567890abcdef1234567890abcdef1234567890abcdef123456789'
  },
  delegationContext: {
    principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
    delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
    delegationId: 'del_e2e_test_123456789abcdef', // This delegation is now revoked
    isDelegate: true,
    scope: {
      allowedOperations: ['CreateStateMachine', 'TransitionStateMachine'],
      maxGasPerTx: 100000
    }
  },
  ordinal: 1005,
  timestamp: 1640995440000, // 1 minute after revocation
  gasConfig: {
    gasLimit: 80000, // Would be within limits if delegation were active
    gasPrice: 1000000000
  },
  expectedValidation: {
    result: 'error',
    error: {
      code: 'DELEGATION_REVOKED',
      message: 'Delegation del_e2e_test_123456789abcdef has been revoked and cannot be used',
      details: {
        delegationId: 'del_e2e_test_123456789abcdef',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        revokedAt: 1004,
        revokedBy: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        attemptedOperation: 'TransitionStateMachine',
        attemptedAt: 1005
      }
    },
    stateChanges: {}, // No state changes should occur
    delegationUpdates: {
      'del_e2e_test_123456789abcdef': {
        // Usage count should NOT be incremented
        'usageCount': 2, // Should remain 2
        'failedAttempts': 1, // Track failed attempt
        'lastFailedAttempt': 1005
      }
    },
    events: [
      {
        type: 'DelegationRejection',
        delegationId: 'del_e2e_test_123456789abcdef',
        reason: 'DELEGATION_REVOKED',
        attemptedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        attemptedOperation: 'TransitionStateMachine',
        fiberId: 'fiber_delegated_e2e_test_789',
        rejectedAt: 1005
      }
    ]
  }
};

export const testAssertions = {
  // Operation should be rejected with specific error
  operationRejectedWithRevokedError: (errorCode: string) => {
    return `operation should be rejected with error code: ${errorCode}`;
  },

  // Error should include delegation details
  errorIncludesDelegationDetails: (delegationId: string, revokedAt: number) => {
    return `error should include delegation ID ${delegationId} and revocation ordinal ${revokedAt}`;
  },

  // Fiber state should remain unchanged
  fiberStateUnchanged: (fiberId: string, expectedState: string) => {
    return `fiber[${fiberId}] state should remain ${expectedState}`;
  },

  // Usage count should not be incremented
  usageCountNotIncremented: (delegationId: string, expectedCount: number) => {
    return `delegation[${delegationId}].usageCount should remain ${expectedCount}`;
  },

  // Failed attempt should be tracked
  failedAttemptTracked: (delegationId: string) => {
    return `delegation[${delegationId}].failedAttempts should be incremented`;
  },

  // No gas should be consumed for failed operation
  noGasConsumedForFailedOperation: (delegationId: string, expectedGas: number) => {
    return `delegation[${delegationId}].totalGasUsed should remain ${expectedGas}`;
  },

  // Delegation rejection event should be emitted
  delegationRejectionEventEmitted: (delegationId: string, reason: string) => {
    return `DelegationRejection event should be emitted with reason: ${reason}`;
  },

  // Transaction should be marked as rejected
  transactionMarkedAsRejected: (ordinal: number) => {
    return `transaction at ordinal ${ordinal} should be marked as rejected`;
  }
};

export default attemptAfterRevocationEvent;