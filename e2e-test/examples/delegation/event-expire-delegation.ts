/**
 * E2E Test Event: Delegation Expiration
 * 
 * This event simulates the natural expiration of a delegation based on its
 * configured expiration time. This tests the time-based enforcement of delegation
 * validity and automatic cleanup.
 * 
 * Test Validation:
 * - Delegation automatically transitions to expired state
 * - Expiration is detected at the correct ordinal
 * - Session keys are automatically deactivated
 * - Cleanup processes are triggered
 */

import { OttochainMessage } from '../../../types';

export const expireDelegationEvent: OttochainMessage = {
  type: 'SYSTEM_TICK', // System event to check for expired delegations
  data: {
    currentOrdinal: 2500, // At the expiration ordinal configured in initial data
    currentTimestamp: 1641081600000, // 24 hours later
    systemChecks: {
      expiredDelegations: ['del_e2e_test_123456789abcdef'], // This delegation should expire
      sessionKeyCleanup: true,
      automaticStateTransitions: true
    },
    expirationDetails: {
      delegationId: 'del_e2e_test_123456789abcdef',
      expiresAt: 2500, // Original expiration ordinal
      finalState: {
        usageCount: 2,
        totalGasUsed: 160000,
        lastUsed: 1003,
        operationsPerformed: [
          'CreateStateMachine',
          'TransitionStateMachine'
        ]
      }
    }
  },
  signature: {
    signedBy: 'SYSTEM', // System-generated expiration event
    signature: '0xsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystem',
    publicKey: '0x04systemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystemsystem'
  },
  ordinal: 2500, // At expiration ordinal
  timestamp: 1641081600000,
  expectedValidation: {
    result: 'success',
    stateTransition: {
      from: 'active',
      to: 'expired',
      event: 'expire'
    },
    stateChanges: {
      'isActive': false,
      'currentState': 'expired',
      'expiredAt': 2500,
      'expiredBy': 'SYSTEM',
      'expirationType': 'ordinal_based',
      'finalUsageCount': 2,
      'finalGasUsed': 160000
    },
    sessionKeyUpdates: {
      // All session keys should be automatically deactivated
      'allSessionKeysExpired': true,
      'cleanupPerformed': true
    },
    events: [
      {
        type: 'DelegationExpired',
        delegationId: 'del_e2e_test_123456789abcdef',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        expiredAt: 2500,
        expirationType: 'ordinal_based',
        finalUsageStats: {
          totalOperations: 2,
          totalGasUsed: 160000,
          operationsPerformed: ['CreateStateMachine', 'TransitionStateMachine']
        }
      },
      {
        type: 'SessionKeysExpired',
        delegationId: 'del_e2e_test_123456789abcdef',
        sessionKeysAffected: 1, // Number of session keys deactivated
        expiredAt: 2500
      }
    ]
  }
};

export const testAssertions = {
  // Delegation should automatically expire at configured ordinal
  delegationExpiresAtConfiguredOrdinal: (delegationId: string, ordinal: number) => {
    return `delegation[${delegationId}] should automatically expire at ordinal ${ordinal}`;
  },

  // State should transition from active to expired
  stateTransitionsToExpired: (delegationId: string) => {
    return `delegation[${delegationId}].currentState should transition to 'expired'`;
  },

  // Expiration should be system-triggered
  expirationSystemTriggered: (delegationId: string) => {
    return `delegation[${delegationId}].expiredBy should be 'SYSTEM'`;
  },

  // Final usage statistics should be preserved
  finalUsageStatsPreserved: (delegationId: string, totalOps: number, totalGas: number) => {
    return `delegation[${delegationId}] should preserve final stats: ${totalOps} operations, ${totalGas} gas`;
  },

  // All session keys should be deactivated
  allSessionKeysDeactivated: (delegationId: string) => {
    return `all session keys for delegation[${delegationId}] should be automatically deactivated`;
  },

  // Expiration event should be emitted
  expirationEventEmitted: (delegationId: string, ordinal: number) => {
    return `DelegationExpired event should be emitted for delegation ${delegationId} at ordinal ${ordinal}`;
  },

  // Delegation should not be usable after expiration
  delegationNotUsableAfterExpiration: (delegationId: string) => {
    return `delegation[${delegationId}] should reject all new operation attempts after expiration`;
  },

  // Cleanup should be performed
  cleanupPerformed: (delegationId: string) => {
    return `cleanup processes should be triggered for expired delegation ${delegationId}`;
  },

  // isActive flag should be set to false
  isActiveFlagSetToFalse: (delegationId: string) => {
    return `delegation[${delegationId}].isActive should be set to false`;
  }
};

export default expireDelegationEvent;