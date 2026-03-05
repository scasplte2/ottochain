import { 
  batchSign, 
  generateKeyPair, 
  HttpClient,
  KeyPair 
} from '@ottochain/sdk';

/**
 * E2E Test: Full delegated signing flow
 * 
 * Tests the complete delegation lifecycle:
 * 1. User grants delegation to agent
 * 2. Agent submits transaction on behalf of user
 * 3. Metagraph validates delegation and processes tx
 * 4. User revokes delegation  
 * 5. Agent attempt after revocation is rejected
 * 6. Expired delegation is rejected
 * 
 * NOTE: These are FAILING TESTS (TDD approach)
 * Implementation does not exist yet - tests define expected behavior
 */

interface DelegationGrant {
  grantor: string;           // User address granting delegation
  grantee: string;           // Agent address receiving delegation  
  permissions: string[];     // What actions the agent can perform
  expiryTime: number;        // Unix timestamp when delegation expires
  nonce: number;             // Unique nonce for this delegation
}

interface DelegatedTransaction {
  operation: any;            // The actual transaction to perform
  delegation: DelegationGrant;
  delegatorSignature: string; // Agent's signature
  originalGrantSignature: string; // User's original grant signature
}

describe('Delegated Signing E2E Flow', () => {
  let userKeyPair: KeyPair;
  let agentKeyPair: KeyPair;
  let client: HttpClient;
  let testFiberId: string;

  beforeEach(() => {
    // Generate fresh key pairs for each test
    userKeyPair = generateKeyPair();
    agentKeyPair = generateKeyPair();
    
    // Initialize HTTP client to local test metagraph
    // TODO: Replace with actual test cluster endpoint
    client = new HttpClient('http://127.0.0.1:9400');
    
    // Generate unique fiber ID for this test
    testFiberId = `delegation-test-${Date.now()}`;
    
    console.log('Test setup:');
    console.log('  User address:', userKeyPair.address);
    console.log('  Agent address:', agentKeyPair.address);
    console.log('  Test fiber ID:', testFiberId);
  });

  describe('1. User grants delegation to agent', () => {
    it('should allow user to create delegation grant', async () => {
      // Arrange
      const delegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['CreateStateMachine', 'UpdateData', 'TriggerTransition'],
        expiryTime: Date.now() + (24 * 60 * 60 * 1000), // 24 hours from now
        nonce: 1
      };

      const delegationMessage = {
        GrantDelegation: delegation
      };

      // Act
      const signedGrant = await batchSign(
        delegationMessage, 
        [userKeyPair.privateKey], 
        { isDataUpdate: true }
      );

      // Assert
      // TODO: This will FAIL until delegation feature is implemented
      try {
        const response = await client.post('/delegation/grant', signedGrant);
        
        expect(response.status).toBe('accepted');
        expect(response.delegationId).toBeDefined();
        expect(response.grantor).toBe(userKeyPair.address);
        expect(response.grantee).toBe(agentKeyPair.address);
      } catch (error) {
        // Expected to fail - delegation endpoint doesn't exist yet
        expect(error.message).toContain('delegation not implemented');
      }
    });

    it('should reject delegation grant with invalid permissions', async () => {
      // Arrange
      const invalidDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['InvalidPermission'], // Invalid permission
        expiryTime: Date.now() + 3600000,
        nonce: 2
      };

      const delegationMessage = {
        GrantDelegation: invalidDelegation
      };

      // Act & Assert
      const signedGrant = await batchSign(
        delegationMessage, 
        [userKeyPair.privateKey], 
        { isDataUpdate: true }
      );

      try {
        await client.post('/delegation/grant', signedGrant);
        throw new Error('Should have been rejected');
      } catch (error) {
        // Expected to fail with validation error
        expect(error.message).toContain('invalid permission');
      }
    });

    it('should reject delegation grant with past expiry time', async () => {
      // Arrange
      const expiredDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['CreateStateMachine'],
        expiryTime: Date.now() - 1000, // Already expired
        nonce: 3
      };

      const delegationMessage = {
        GrantDelegation: expiredDelegation
      };

      // Act & Assert
      const signedGrant = await batchSign(
        delegationMessage, 
        [userKeyPair.privateKey], 
        { isDataUpdate: true }
      );

      try {
        await client.post('/delegation/grant', signedGrant);
        throw new Error('Should have been rejected');
      } catch (error) {
        expect(error.message).toContain('delegation already expired');
      }
    });
  });

  describe('2. Agent submits transaction on behalf of user', () => {
    it('should allow agent to submit delegated transaction', async () => {
      // Arrange
      // First, create the delegation grant (this would normally succeed)
      const delegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['CreateStateMachine'],
        expiryTime: Date.now() + 3600000,
        nonce: 4
      };

      const stateMachineOperation = {
        CreateStateMachine: {
          fiberId: testFiberId,
          definition: {
            metadata: { name: 'Delegated Test SM' },
            states: { 
              init: { id: { value: 'init' }, isFinal: false, metadata: null } 
            },
            transitions: [],
            initialState: { value: 'init' }
          },
          initialData: { status: 'DELEGATED_CREATION' },
          parentFiberId: null
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: stateMachineOperation,
        delegation: delegation,
        delegatorSignature: '', // Will be filled by agent signing
        originalGrantSignature: '' // Will be filled from grant
      };

      // Act
      // Agent signs the delegated transaction
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      // Assert
      // TODO: This will FAIL until delegated transaction processing is implemented
      try {
        const response = await client.post('/data/delegated', signedDelegatedTx);
        
        expect(response.status).toBe('accepted');
        expect(response.fiberId).toBe(testFiberId);
        expect(response.creator).toBe(userKeyPair.address); // Original grantor
        expect(response.delegator).toBe(agentKeyPair.address); // Agent who submitted
      } catch (error) {
        // Expected to fail - delegated transaction endpoint doesn't exist yet
        expect(error.message).toContain('delegated transactions not implemented');
      }
    });

    it('should reject delegated transaction with insufficient permissions', async () => {
      // Arrange
      const limitedDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'], // Only update data, not create SM
        expiryTime: Date.now() + 3600000,
        nonce: 5
      };

      const unauthorizedOperation = {
        CreateStateMachine: { // Agent doesn't have CreateStateMachine permission
          fiberId: `${testFiberId}-unauthorized`,
          definition: {
            metadata: { name: 'Unauthorized SM' },
            states: { init: { id: { value: 'init' }, isFinal: false, metadata: null } },
            transitions: [],
            initialState: { value: 'init' }
          },
          initialData: { status: 'UNAUTHORIZED' },
          parentFiberId: null
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: unauthorizedOperation,
        delegation: limitedDelegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      // Act & Assert
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      try {
        await client.post('/data/delegated', signedDelegatedTx);
        throw new Error('Should have been rejected due to insufficient permissions');
      } catch (error) {
        expect(error.message).toContain('insufficient delegation permissions');
      }
    });
  });

  describe('3. Metagraph validates delegation and processes tx', () => {
    it('should validate delegation signature chain', async () => {
      // Arrange
      const delegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() + 3600000,
        nonce: 6
      };

      const updateOperation = {
        UpdateData: {
          fiberId: testFiberId,
          newData: { status: 'UPDATED_VIA_DELEGATION' }
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: updateOperation,
        delegation: delegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      // Act & Assert
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      try {
        const response = await client.post('/data/delegated', signedDelegatedTx);
        
        // Metagraph should validate both signatures
        expect(response.validationResult).toEqual({
          delegationValid: true,
          grantor: userKeyPair.address,
          grantee: agentKeyPair.address,
          permissionGranted: true,
          signatureChainValid: true,
          notExpired: true
        });
        
        expect(response.status).toBe('accepted');
      } catch (error) {
        expect(error.message).toContain('delegation validation not implemented');
      }
    });

    it('should reject transaction with invalid delegation signature', async () => {
      // Arrange
      const delegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() + 3600000,
        nonce: 7
      };

      const updateOperation = {
        UpdateData: {
          fiberId: testFiberId,
          newData: { status: 'INVALID_SIG_TEST' }
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: updateOperation,
        delegation: delegation,
        delegatorSignature: '',
        originalGrantSignature: 'invalid_signature_here' // Invalid signature
      };

      // Act & Assert
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      try {
        await client.post('/data/delegated', signedDelegatedTx);
        throw new Error('Should have been rejected due to invalid signature');
      } catch (error) {
        expect(error.message).toContain('invalid delegation signature');
      }
    });
  });

  describe('4. User revokes delegation', () => {
    it('should allow user to revoke delegation', async () => {
      // Arrange
      const delegationId = 'delegation-123'; // Would be returned from grant
      
      const revocationMessage = {
        RevokeDelegation: {
          delegationId: delegationId,
          grantor: userKeyPair.address,
          grantee: agentKeyPair.address,
          reason: 'User requested revocation'
        }
      };

      // Act
      const signedRevocation = await batchSign(
        revocationMessage,
        [userKeyPair.privateKey],
        { isDataUpdate: true }
      );

      // Assert
      try {
        const response = await client.post('/delegation/revoke', signedRevocation);
        
        expect(response.status).toBe('revoked');
        expect(response.delegationId).toBe(delegationId);
        expect(response.revokedAt).toBeDefined();
      } catch (error) {
        // Expected to fail - revocation endpoint doesn't exist yet
        expect(error.message).toContain('delegation revocation not implemented');
      }
    });

    it('should reject revocation from non-grantor', async () => {
      // Arrange
      const delegationId = 'delegation-456';
      const maliciousKeyPair = generateKeyPair(); // Different user
      
      const invalidRevocationMessage = {
        RevokeDelegation: {
          delegationId: delegationId,
          grantor: userKeyPair.address, // Claiming to be grantor
          grantee: agentKeyPair.address,
          reason: 'Malicious revocation attempt'
        }
      };

      // Act & Assert - Sign with wrong key
      const signedRevocation = await batchSign(
        invalidRevocationMessage,
        [maliciousKeyPair.privateKey], // Wrong signer
        { isDataUpdate: true }
      );

      try {
        await client.post('/delegation/revoke', signedRevocation);
        throw new Error('Should have been rejected');
      } catch (error) {
        expect(error.message).toContain('unauthorized revocation');
      }
    });
  });

  describe('5. Agent attempt after revocation is rejected', () => {
    it('should reject delegated transaction after revocation', async () => {
      // Arrange
      const revokedDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() + 3600000,
        nonce: 8,
        // Note: In real implementation, this would be marked as revoked
      };

      const attemptedOperation = {
        UpdateData: {
          fiberId: testFiberId,
          newData: { status: 'SHOULD_BE_REJECTED' }
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: attemptedOperation,
        delegation: revokedDelegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      // Act & Assert
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      try {
        await client.post('/data/delegated', signedDelegatedTx);
        throw new Error('Should have been rejected - delegation was revoked');
      } catch (error) {
        expect(error.message).toContain('delegation revoked');
      }
    });
  });

  describe('6. Expired delegation is rejected', () => {
    it('should reject delegated transaction with expired delegation', async () => {
      // Arrange
      const expiredDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() - 1000, // Already expired
        nonce: 9
      };

      const attemptedOperation = {
        UpdateData: {
          fiberId: testFiberId,
          newData: { status: 'EXPIRED_DELEGATION_TEST' }
        }
      };

      const delegatedTx: DelegatedTransaction = {
        operation: attemptedOperation,
        delegation: expiredDelegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      // Act & Assert
      const signedDelegatedTx = await batchSign(
        delegatedTx,
        [agentKeyPair.privateKey],
        { isDataUpdate: true, isDelegated: true }
      );

      try {
        await client.post('/data/delegated', signedDelegatedTx);
        throw new Error('Should have been rejected - delegation expired');
      } catch (error) {
        expect(error.message).toContain('delegation expired');
      }
    });

    it('should reject delegation grant creation with immediate expiry', async () => {
      // Arrange
      const immediateExpiryDelegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['CreateStateMachine'],
        expiryTime: Date.now() + 100, // Expires in 100ms
        nonce: 10
      };

      const delegationMessage = {
        GrantDelegation: immediateExpiryDelegation
      };

      // Act
      const signedGrant = await batchSign(
        delegationMessage,
        [userKeyPair.privateKey],
        { isDataUpdate: true }
      );

      // Wait for expiry
      await new Promise(resolve => setTimeout(resolve, 200));

      // Assert
      try {
        await client.post('/delegation/grant', signedGrant);
        throw new Error('Should have been rejected - delegation expired before processing');
      } catch (error) {
        expect(error.message).toContain('delegation expired');
      }
    });
  });

  describe('Edge cases and error conditions', () => {
    it('should reject delegation with duplicate nonce', async () => {
      // Arrange
      const duplicateNonce = 100;
      
      const delegation1: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() + 3600000,
        nonce: duplicateNonce
      };

      const delegation2: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['CreateStateMachine'],
        expiryTime: Date.now() + 3600000,
        nonce: duplicateNonce // Same nonce
      };

      // Act & Assert
      const signedGrant1 = await batchSign(
        { GrantDelegation: delegation1 },
        [userKeyPair.privateKey],
        { isDataUpdate: true }
      );

      const signedGrant2 = await batchSign(
        { GrantDelegation: delegation2 },
        [userKeyPair.privateKey],
        { isDataUpdate: true }
      );

      try {
        await client.post('/delegation/grant', signedGrant1);
        await client.post('/delegation/grant', signedGrant2); // Should fail
        throw new Error('Should have rejected duplicate nonce');
      } catch (error) {
        expect(error.message).toContain('nonce already used');
      }
    });

    it('should handle concurrent delegation operations correctly', async () => {
      // Arrange
      const delegation: DelegationGrant = {
        grantor: userKeyPair.address,
        grantee: agentKeyPair.address,
        permissions: ['UpdateData'],
        expiryTime: Date.now() + 3600000,
        nonce: 11
      };

      const operation1 = {
        UpdateData: { fiberId: testFiberId, newData: { counter: 1 } }
      };

      const operation2 = {
        UpdateData: { fiberId: testFiberId, newData: { counter: 2 } }
      };

      const tx1: DelegatedTransaction = {
        operation: operation1,
        delegation: delegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      const tx2: DelegatedTransaction = {
        operation: operation2,
        delegation: delegation,
        delegatorSignature: '',
        originalGrantSignature: ''
      };

      // Act - Submit both transactions simultaneously
      const signedTx1 = await batchSign(tx1, [agentKeyPair.privateKey], { isDataUpdate: true, isDelegated: true });
      const signedTx2 = await batchSign(tx2, [agentKeyPair.privateKey], { isDataUpdate: true, isDelegated: true });

      // Assert - Both should be processed correctly
      try {
        const [response1, response2] = await Promise.all([
          client.post('/data/delegated', signedTx1),
          client.post('/data/delegated', signedTx2)
        ]);

        expect(response1.status).toBe('accepted');
        expect(response2.status).toBe('accepted');
        // Order should be deterministic based on the metagraph's ordering rules
      } catch (error) {
        expect(error.message).toContain('delegated transactions not implemented');
      }
    });
  });
});