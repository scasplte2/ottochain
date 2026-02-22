/**
 * E2E Test Event: Grant Delegation
 * 
 * This event represents a principal granting delegation authority to an agent.
 * It should create an active delegation that can be used for subsequent operations.
 * 
 * Test Validation:
 * - Delegation is created with correct principal and delegate addresses
 * - Delegation is marked as active
 * - Scope restrictions are properly set
 * - Expiration time is properly configured
 */

import { OttochainMessage } from '../../../types';

export const grantDelegationEvent: OttochainMessage = {
  type: 'CREATE_DELEGATION',
  data: {
    delegationId: 'del_e2e_test_123456789abcdef',
    principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
    delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
    scope: {
      allowedOperations: [
        'CreateStateMachine',
        'TransitionStateMachine'
      ],
      maxGasPerTx: 100000,
      maxTotalGas: 500000,
      fiberTypes: ['*'],
      timeWindow: {
        startOrdinal: 1000,
        endOrdinal: 2500
      }
    },
    approach: 'session_key',
    expiresAt: 2500,
    sessionKeyConfig: {
      expiresIn: 1800, // 30 minutes in seconds
      permissions: {
        maxTransactionsPerHour: 10,
        maxGasPerTransaction: 50000
      }
    }
  },
  signature: {
    signedBy: 'DAG123abc456def789ghi012jkl345mno678pqr901', // Principal signs
    signature: '0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef12',
    publicKey: '0x04abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890'
  },
  ordinal: 1001,
  timestamp: 1640995200000,
  expectedValidation: {
    result: 'success',
    newState: 'active',
    stateChanges: {
      'isActive': true,
      'createdAt': 1001,
      'usageCount': 0
    },
    events: [
      {
        type: 'DelegationGranted',
        principalAddress: 'DAG123abc456def789ghi012jkl345mno678pqr901',
        delegateAddress: 'DAG789def012ghi345jkl678mno901pqr234stu567',
        delegationId: 'del_e2e_test_123456789abcdef'
      }
    ]
  }
};

export const testAssertions = {
  // Delegation should be created and active
  delegationExists: (delegationId: string) => {
    // This would be implemented by the test runner
    // to check that the delegation exists in the metagraph state
    return `delegation[${delegationId}] should exist and be active`;
  },

  // Principal should be able to grant delegation
  principalCanGrant: (principalAddr: string, delegateAddr: string) => {
    return `${principalAddr} should be able to grant delegation to ${delegateAddr}`;
  },

  // Scope should be properly validated and stored
  scopeValidated: (scope: any) => {
    return `delegation scope should include operations: ${scope.allowedOperations.join(', ')}`;
  },

  // Session key should be generated if approach is session_key
  sessionKeyGenerated: (delegationId: string) => {
    return `session key should be generated for delegation ${delegationId}`;
  }
};

export default grantDelegationEvent;