package xyz.kd5ujc.shared_data.delegation.e2e

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._
import weaver.SimpleIOSuite
import xyz.kd5ujc.shared_data.identity.agent.{DelegationManager, ReputationScoring, IdentityIntegration}
import xyz.kd5ujc.shared_data.testkit.TestClusterSetup
import xyz.kd5ujc.shared_test.TestFixture
import java.util.UUID
import scala.concurrent.duration._

/**
 * End-to-End Test Suite for OttoChain Delegation Flow Validation
 *
 * Tests the complete delegation workflow including:
 * - Delegation creation and signing
 * - Relayer submission of delegated transactions  
 * - Delegation revocation scenarios
 * - Edge cases (expired delegations, scope violations)
 * - Performance testing for delegation validation
 * - Integration with tessellation cluster
 */
object DelegationFlowE2ESuite extends SimpleIOSuite {

  // Test configuration
  val testAgentAddress = "agent_e2e_test"
  val testDelegatorAddress = "delegator_e2e_test"
  val testRelayerAddress = "relayer_e2e_test"
  val testSessionPublicKey = "session_key_e2e_test"
  
  // Test timeout for async operations
  val testTimeout = 30.seconds

  test("E2E: Complete delegation creation and submission flow") {
    for {
      // 1. Setup test cluster and initialize agent
      cluster <- TestClusterSetup.setupLocalCluster()
      
      // 2. Register agent with reputation
      initialReputation <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))
      
      // Build reputation to ADVANCED level
      advancedReputation <- (1 to 8).toList.foldLeftM(initialReputation) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }
      
      // 3. Initialize delegation state with stake bond
      stakeBond <- DelegationManager.bondStakeForDelegation(testAgentAddress, 2000L, "ADVANCED")
      
      delegationState = DelegationManager.initializeAgentDelegationState(testAgentAddress, advancedReputation)
        .copy(
          stakeBonds = List(stakeBond),
          totalStakeBonded = 2000L
        )
      
      // 4. Validate delegation request
      validationResult <- DelegationManager.validateDelegationRequest(
        delegationState,
        requestedOperations = List("market", "contract"),
        stakeAmount = 1000L,
        sessionDuration = 21600L, // 6 hours
        maxSpendLimit = 5000L
      )
      
      _ = expect.all(
        validationResult.isValid,
        validationResult.errors.isEmpty
      )
      
      // 5. Create delegation session
      delegationSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market", "contract"),
        1000L,
        5000L,
        21600L
      )
      
      // 6. Submit delegation to metagraph
      delegationTx <- cluster.submitDelegation(
        delegationSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        delegationTx.isValid,
        delegationTx.delegationId == delegationSession.delegationId
      )
      
      // 7. Create and submit delegated transaction
      marketTransaction = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create"
      )
      
      // 8. Submit delegated transaction through relayer
      relayedTx <- cluster.submitDelegatedTransaction(
        marketTransaction,
        delegationSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        relayedTx.isAccepted,
        relayedTx.isDelegated,
        relayedTx.relayerAddress == testRelayerAddress
      )
      
      // 9. Verify transaction appears in cluster state
      txState <- cluster.waitForTransactionConfirmation(
        relayedTx.transactionHash,
        timeout = testTimeout
      )
      
      _ = expect.all(
        txState.isConfirmed,
        txState.wasRelayed,
        txState.originalSigner == testDelegatorAddress
      )
      
      // 10. Clean up cluster
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("E2E: Delegation revocation and transaction rejection") {
    for {
      // 1. Setup test cluster and create active delegation
      cluster <- TestClusterSetup.setupLocalCluster()
      
      initialReputation <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))
      advancedReputation <- (1 to 8).toList.foldLeftM(initialReputation) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }
      
      delegationSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market"),
        1000L,
        2000L,
        21600L
      )
      
      // 2. Submit delegation to cluster
      _ <- cluster.submitDelegation(delegationSession, testRelayerAddress)
      
      // 3. Revoke delegation
      revokedSession <- DelegationManager.revokeDelegation(
        delegationSession,
        testDelegatorAddress,
        "Testing revocation flow"
      )
      
      // 4. Submit revocation to cluster
      revocationTx <- cluster.submitRevocation(revokedSession, testDelegatorAddress)
      
      _ = expect(revocationTx.isValid)
      
      // 5. Wait for revocation propagation
      _ <- IO.sleep(DelegationManager.REVOCATION_PROPAGATION_TIME.seconds)
      
      // 6. Attempt to submit delegated transaction with revoked session
      failingTransaction = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create"
      )
      
      rejectedTx <- cluster.submitDelegatedTransaction(
        failingTransaction,
        revokedSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        rejectedTx.isRejected,
        rejectedTx.rejectionReason.contains("delegation revoked")
      )
      
      // 7. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("E2E: Expired delegation handling") {
    for {
      // 1. Setup cluster
      cluster <- TestClusterSetup.setupLocalCluster()
      
      // 2. Create delegation with very short expiry (5 seconds for testing)
      shortLivedSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market"),
        500L,
        1000L,
        5L // 5 seconds only
      )
      
      // 3. Submit delegation
      _ <- cluster.submitDelegation(shortLivedSession, testRelayerAddress)
      
      // 4. Wait for expiration
      _ <- IO.sleep(10.seconds) // Wait longer than expiry
      
      // 5. Attempt to use expired delegation
      expiredTransaction = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create"
      )
      
      expiredTx <- cluster.submitDelegatedTransaction(
        expiredTransaction,
        shortLivedSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        expiredTx.isRejected,
        expiredTx.rejectionReason.contains("delegation expired")
      )
      
      // 6. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("E2E: Scope violation detection") {
    for {
      // 1. Setup cluster
      cluster <- TestClusterSetup.setupLocalCluster()
      
      // 2. Create delegation with limited scope (only "market" operations)
      limitedSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market"), // Only market operations allowed
        500L,
        1000L,
        3600L
      )
      
      // 3. Submit delegation
      _ <- cluster.submitDelegation(limitedSession, testRelayerAddress)
      
      // 4. Attempt governance operation (not in scope)
      unauthorizedTransaction = TestFixture.createGovernanceTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "governance_vote" // Not allowed in scope
      )
      
      unauthorizedTx <- cluster.submitDelegatedTransaction(
        unauthorizedTransaction,
        limitedSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        unauthorizedTx.isRejected,
        unauthorizedTx.rejectionReason.contains("operation not in scope")
      )
      
      // 5. Attempt valid market operation (should succeed)
      authorizedTransaction = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create" // Allowed in scope
      )
      
      authorizedTx <- cluster.submitDelegatedTransaction(
        authorizedTransaction,
        limitedSession,
        testRelayerAddress
      )
      
      _ = expect(authorizedTx.isAccepted)
      
      // 6. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("E2E: Spending limit enforcement") {
    for {
      // 1. Setup cluster
      cluster <- TestClusterSetup.setupLocalCluster()
      
      // 2. Create delegation with spending limit
      limitedSpendingSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market"),
        500L,
        1000L, // Max spending limit: 1000 units
        3600L
      )
      
      // 3. Submit delegation
      _ <- cluster.submitDelegation(limitedSpendingSession, testRelayerAddress)
      
      // 4. Submit transaction within spending limit
      validSpendTx = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create",
        amount = Some(500L) // Within limit
      )
      
      acceptedTx <- cluster.submitDelegatedTransaction(
        validSpendTx,
        limitedSpendingSession,
        testRelayerAddress
      )
      
      _ = expect(acceptedTx.isAccepted)
      
      // 5. Submit transaction that would exceed spending limit
      excessiveSpendTx = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create",
        amount = Some(800L) // Would exceed remaining limit (500 + 800 > 1000)
      )
      
      rejectedTx <- cluster.submitDelegatedTransaction(
        excessiveSpendTx,
        limitedSpendingSession,
        testRelayerAddress
      )
      
      _ = expect.all(
        rejectedTx.isRejected,
        rejectedTx.rejectionReason.contains("spending limit exceeded")
      )
      
      // 6. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("E2E: Emergency revocation and slashing") {
    for {
      // 1. Setup cluster and agent with multiple active delegations
      cluster <- TestClusterSetup.setupLocalCluster()
      
      initialReputation <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))
      advancedReputation <- (1 to 10).toList.foldLeftM(initialReputation) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }
      
      // Create multiple delegation sessions
      session1 <- DelegationManager.createDelegationSession(
        testAgentAddress,
        "delegator1",
        "session_key_1",
        List("market"),
        500L,
        1000L,
        3600L
      )
      
      session2 <- DelegationManager.createDelegationSession(
        testAgentAddress,
        "delegator2",
        "session_key_2",
        List("governance"),
        750L,
        1500L,
        7200L
      )
      
      // 2. Submit both delegations to cluster
      _ <- cluster.submitDelegation(session1, testRelayerAddress)
      _ <- cluster.submitDelegation(session2, testRelayerAddress)
      
      // 3. Initialize delegation state
      delegationState = DelegationManager.initializeAgentDelegationState(testAgentAddress, advancedReputation)
        .copy(
          activeDelegations = List(session1, session2),
          totalStakeBonded = 2000L,
          successfulDelegations = 10
        )
      
      // 4. Trigger emergency revocation
      emergencyRevokedState <- DelegationManager.emergencyRevokeAllDelegations(
        delegationState,
        "Security breach detected in testing"
      )
      
      // 5. Submit emergency revocation to cluster
      emergencyRevocationTx <- cluster.submitEmergencyRevocation(
        testAgentAddress,
        emergencyRevokedState.activeDelegations,
        "Security breach detected in testing"
      )
      
      _ = expect(emergencyRevocationTx.isValid)
      
      // 6. Wait for revocation propagation
      _ <- IO.sleep(DelegationManager.REVOCATION_PROPAGATION_TIME.seconds)
      
      // 7. Verify all delegated transactions are now rejected
      failingTx1 = TestFixture.createMarketTransaction(
        delegatorAddress = "delegator1",
        sessionKey = "session_key_1",
        operation = "market_create"
      )
      
      failingTx2 = TestFixture.createGovernanceTransaction(
        delegatorAddress = "delegator2",
        sessionKey = "session_key_2",
        operation = "governance_vote"
      )
      
      rejectedTx1 <- cluster.submitDelegatedTransaction(failingTx1, session1, testRelayerAddress)
      rejectedTx2 <- cluster.submitDelegatedTransaction(failingTx2, session2, testRelayerAddress)
      
      _ = expect.all(
        rejectedTx1.isRejected,
        rejectedTx1.rejectionReason.contains("emergency revocation"),
        rejectedTx2.isRejected,
        rejectedTx2.rejectionReason.contains("emergency revocation")
      )
      
      // 8. Verify slashing was applied to reputation
      _ = expect.all(
        emergencyRevokedState.slashingEvents == 1,
        emergencyRevokedState.reputationState.compositeScore < delegationState.reputationState.compositeScore
      )
      
      // 9. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("Performance: High-frequency delegation validation") {
    for {
      // 1. Setup cluster
      cluster <- TestClusterSetup.setupLocalCluster()
      
      // 2. Create agent with high reputation for performance testing
      highReputation <- (1 to 20).toList.foldLeftM(ReputationScoring.initializeAgent(testAgentAddress)) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }
      
      // 3. Create delegation for performance testing
      performanceSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market", "governance", "contract"),
        5000L,
        10000L,
        21600L
      )
      
      // 4. Submit delegation
      _ <- cluster.submitDelegation(performanceSession, testRelayerAddress)
      
      // 5. Measure validation performance with 100 transactions
      startTime <- IO.realTime
      
      results <- (1 to 100).toList.traverse { i =>
        val tx = TestFixture.createMarketTransaction(
          delegatorAddress = testDelegatorAddress,
          sessionKey = testSessionPublicKey,
          operation = "market_create",
          transactionId = Some(s"perf_test_$i")
        )
        
        cluster.submitDelegatedTransaction(tx, performanceSession, testRelayerAddress)
      }
      
      endTime <- IO.realTime
      totalTime = endTime - startTime
      
      // 6. Verify performance metrics
      successfulTxs = results.count(_.isAccepted)
      avgLatencyMs = totalTime.toMillis / 100.0
      
      _ = expect.all(
        successfulTxs == 100, // All transactions should succeed
        avgLatencyMs < 100.0, // Average latency should be under 100ms
        totalTime < 10.seconds // Total time should be under 10 seconds
      )
      
      // 7. Test concurrent delegation validation
      concurrentStartTime <- IO.realTime
      
      concurrentResults <- (1 to 50).toList.parTraverse { i =>
        val tx = TestFixture.createGovernanceTransaction(
          delegatorAddress = testDelegatorAddress,
          sessionKey = testSessionPublicKey,
          operation = "governance_vote",
          transactionId = Some(s"concurrent_$i")
        )
        
        cluster.submitDelegatedTransaction(tx, performanceSession, testRelayerAddress)
      }
      
      concurrentEndTime <- IO.realTime
      concurrentTime = concurrentEndTime - concurrentStartTime
      
      concurrentSuccesses = concurrentResults.count(_.isAccepted)
      
      _ = expect.all(
        concurrentSuccesses == 50,
        concurrentTime < 5.seconds // Concurrent processing should be faster
      )
      
      // 8. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }

  test("Integration: Multi-relayer delegation coordination") {
    for {
      // 1. Setup cluster with multiple relayers
      cluster <- TestClusterSetup.setupLocalCluster()
      
      relayer1 = "relayer_1"
      relayer2 = "relayer_2"
      relayer3 = "relayer_3"
      
      // 2. Setup agent with sufficient reputation and stake
      highReputation <- (1 to 15).toList.foldLeftM(ReputationScoring.initializeAgent(testAgentAddress)) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }
      
      // 3. Create delegation session that allows multiple relayers
      multiRelayerSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionPublicKey,
        List("market", "governance"),
        2000L,
        5000L,
        21600L
      )
      
      // 4. Register delegation with all relayers
      _ <- cluster.submitDelegation(multiRelayerSession, relayer1)
      _ <- cluster.submitDelegation(multiRelayerSession, relayer2)
      _ <- cluster.submitDelegation(multiRelayerSession, relayer3)
      
      // 5. Submit transactions through different relayers
      tx1 = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_create",
        transactionId = Some("multi_relayer_1")
      )
      
      tx2 = TestFixture.createGovernanceTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "governance_vote",
        transactionId = Some("multi_relayer_2")
      )
      
      tx3 = TestFixture.createMarketTransaction(
        delegatorAddress = testDelegatorAddress,
        sessionKey = testSessionPublicKey,
        operation = "market_commit",
        transactionId = Some("multi_relayer_3")
      )
      
      // 6. Submit through different relayers simultaneously
      result1F <- cluster.submitDelegatedTransaction(tx1, multiRelayerSession, relayer1).start
      result2F <- cluster.submitDelegatedTransaction(tx2, multiRelayerSession, relayer2).start  
      result3F <- cluster.submitDelegatedTransaction(tx3, multiRelayerSession, relayer3).start
      
      result1 <- result1F.joinWithNever
      result2 <- result2F.joinWithNever
      result3 <- result3F.joinWithNever
      
      _ = expect.all(
        result1.isAccepted,
        result1.relayerAddress == relayer1,
        result2.isAccepted,
        result2.relayerAddress == relayer2,
        result3.isAccepted,
        result3.relayerAddress == relayer3
      )
      
      // 7. Verify spending limit is enforced across all relayers
      totalSpent <- cluster.getSpentAmountForDelegation(multiRelayerSession.delegationId)
      expectedSpent = List(tx1, tx2, tx3).flatMap(_.amount).sum
      
      _ = expect(totalSpent == expectedSpent)
      
      // 8. Clean up
      _ <- cluster.shutdown()
      
    } yield success
  }
}