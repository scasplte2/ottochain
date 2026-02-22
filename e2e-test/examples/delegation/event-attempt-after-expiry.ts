/**
 * E2E Test Event: Attempt Operation After Delegation Expiration
 * 
 * This event represents an agent attempting to perform an operation after their
 * delegation has naturally expired. This operation should FAIL with a specific error
 * indicating that the delegation has expired.
 * 
 * Test Validation:
 * - Operation is rejected with DELEGATION_EXPIRED error
 * - No state changes occur
 * - Error message includes expiration details
 * - Fiber remains unchanged
 */

import { OttochainMessage } from '../../../types';

export const attemptAfterExpiryEvent: OttochainMessage = {
  type: 'TransitionStateMachine',
  data: {
    fiberId: 'fiber_delegated_e2e_test_789', // Try to transition the same fiber
    eventName: 'complete',
    eventData: {
      completedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent (should fail)
      completedFor: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal
      timestamp: 1641081660000, // 1 minute after expiration
      reason: 'E2E test - attempting completion after expiration (should fail)'
    },
    expectedFromState: 'active',
    expectedToState: 'completed'
  },
  signature: {
    signedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567', // Agent attempts to sign
    signature: '0xaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd',
    publicKey: '0x04789def012ghi345jkl678mno901pqr234stu567vwx890yzabcdef123456789012345678901234567890abcdef1234567890abcdef1234567890abcdef123456789'
  },
  delegationContext: {
    principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
    delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
    delegationId: 'del_e2e_test_123456789abcdef', // This delegation is now expired
    isDelegate: true,
    scope: {
      allowedOperations: ['CreateStateMachine', 'TransitionStateMachine'],
      maxGasPerTx: 100000
    }
  },
  ordinal: 2501, // After expiration ordinal (2500)
  timestamp: 1641081660000, // 1 minute after expiration
  gasConfig: {
    gasLimit: 70000, // Would be within limits if delegation were active
    gasPrice: 1000000000
  },
  expectedValidation: {
    result: 'error',
    error: {
      code: 'DELEGATION_EXPIRED',
      message: 'Delegation del_e2e_test_123456789abcdef has expired and cannot be used',
      details: {
        delegationId: 'del_e2e_test_123456789abcdef',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        expiredAt: 2500,
        expirationOrdinal: 2500,
        currentOrdinal: 2501,
        expirationType: 'ordinal_based',
        attemptedOperation: 'TransitionStateMachine',
        attemptedAt: 2501
      }
    },
    stateChanges: {}, // No state changes should occur
    delegationUpdates: {
      'del_e2e_test_123456789abcdef': {
        // Usage count should NOT be incremented
        'usageCount': 2, // Should remain 2
        'failedAttempts': 1, // Track failed attempt (may be 2 if revocation test ran first)
        'lastFailedAttempt': 2501
      }
    },
    events: [
      {
        type: 'DelegationRejection',
        delegationId: 'del_e2e_test_123456789abcdef',
        reason: 'DELEGATION_EXPIRED',
        attemptedBy: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        attemptedOperation: 'TransitionStateMachine',
        fiberId: 'fiber_delegated_e2e_test_789',
        rejectedAt: 2501,
        expirationDetails: {
          expiredAt: 2500,
          currentOrdinal: 2501,
          expirationType: 'ordinal_based'
        }
      }
    ]
  }
};

export const testAssertions = {
  // Operation should be rejected with expired error
  operationRejectedWithExpiredError: (errorCode: string) => {
    return `operation should be rejected with error code: ${errorCode}`;
  },

  // Error should include expiration details
  errorIncludesExpirationDetails: (delegationId: string, expiredAt: number, currentOrdinal: number) => {
    return `error should show delegation ${delegationId} expired at ordinal ${expiredAt}, current ordinal ${currentOrdinal}`;
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
  },

  // Current ordinal should be after expiration ordinal
  currentOrdinalAfterExpiration: (currentOrdinal: number, expirationOrdinal: number) => {
    return `current ordinal ${currentOrdinal} should be greater than expiration ordinal ${expirationOrdinal}`;
  },

  // Delegation should be in expired state
  delegationInExpiredState: (delegationId: string) => {
    return `delegation[${delegationId}].currentState should be 'expired'`;
  },

  // Session keys should also be expired
  sessionKeysAlsoExpired: (delegationId: string) => {
    return `all session keys for delegation[${delegationId}] should also be expired`;
  }
};

export default attemptAfterExpiryEvent;