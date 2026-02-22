/**
 * E2E Test Event: Revoke Delegation
 * 
 * This event represents a principal revoking delegation authority from an agent.
 * After this event, the agent should no longer be able to perform operations
 * on behalf of the principal.
 * 
 * Test Validation:
 * - Delegation is marked as revoked
 * - Only principal can revoke their own delegation
 * - Revocation timestamp is recorded
 * - Subsequent operations by agent should fail
 */

import { OttochainMessage } from '../../../types';

export const revokeDelegationEvent: OttochainMessage = {
  type: 'REVOKE_DELEGATION',
  data: {
    delegationId: 'del_e2e_test_123456789abcdef',
    reason: 'E2E test completion - revoking delegation authority',
    revokedAt: 1640995380000, // 3 minutes after delegation grant
    finalUsageReport: {
      totalOperations: 2,
      totalGasUsed: 160000,
      operationsPerformed: [
        {
          ordinal: 1002,
          operation: 'CreateStateMachine',
          fiberId: 'fiber_delegated_e2e_test_789',
          gasUsed: 85000
        },
        {
          ordinal: 1003,
          operation: 'TransitionStateMachine',
          fiberId: 'fiber_delegated_e2e_test_789',
          gasUsed: 75000
        }
      ]
    }
  },
  signature: {
    signedBy: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal must sign revocation
    signature: '0x9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba98',
    publicKey: '0x04abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890'
  },
  ordinal: 1004,
  timestamp: 1640995380000, // 3 minutes after delegation grant
  expectedValidation: {
    result: 'success',
    stateTransition: {
      from: 'active',
      to: 'revoked',
      event: 'revoke'
    },
    stateChanges: {
      'isActive': false,
      'currentState': 'revoked',
      'revokedAt': 1004,
      'revokedBy': 'DAG123abc456def789ghi012jkl345mno678pqr901',
      'revocationReason': 'E2E test completion - revoking delegation authority',
      'finalUsageCount': 2,
      'finalGasUsed': 160000
    },
    sessionKeyUpdates: {
      // All session keys for this delegation should be deactivated
      'allSessionKeysDeactivated': true
    },
    events: [
      {
        type: 'DelegationRevoked',
        delegationId: 'del_e2e_test_123456789abcdef',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        revokedBy: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        reason: 'E2E test completion - revoking delegation authority',
        finalUsageReport: {
          totalOperations: 2,
          totalGasUsed: 160000
        }
      }
    ]
  }
};

export const testAssertions = {
  // Delegation should be marked as revoked
  delegationMarkedAsRevoked: (delegationId: string) => {
    return `delegation[${delegationId}].isActive should be false and currentState should be 'revoked'`;
  },

  // Only principal should be able to revoke
  onlyPrincipalCanRevoke: (delegationId: string, principalAddr: string) => {
    return `delegation[${delegationId}] can only be revoked by principal ${principalAddr}`;
  },

  // Revocation details should be recorded
  revocationDetailsRecorded: (delegationId: string, ordinal: number, reason: string) => {
    return `delegation[${delegationId}] should record revocation at ordinal ${ordinal} with reason: ${reason}`;
  },

  // Final usage statistics should be captured
  finalUsageStatsCaptured: (delegationId: string, totalOps: number, totalGas: number) => {
    return `delegation[${delegationId}] should record final stats: ${totalOps} operations, ${totalGas} gas`;
  },

  // All session keys should be deactivated
  sessionKeysDeactivated: (delegationId: string) => {
    return `all session keys for delegation[${delegationId}] should be deactivated`;
  },

  // Revocation event should be emitted
  revocationEventEmitted: (delegationId: string, principalAddr: string, delegateAddr: string) => {
    return `DelegationRevoked event should be emitted for delegation ${delegationId}`;
  },

  // Delegation should not be usable for new operations
  delegationNotUsableAfterRevocation: (delegationId: string) => {
    return `delegation[${delegationId}] should reject all new operation attempts`;
  }
};

export default revokeDelegationEvent;