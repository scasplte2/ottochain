package xyz.kd5ujc.shared_data

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{KeyPair, PrivateKey, PublicKey}
import io.constellationnetwork.node.shared.domain.snapshot.Snapshot

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema._
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_data.validation.FiberRules
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser._
import weaver.SimpleIOSuite

import java.util.UUID
import scala.collection.immutable.SortedMap

/**
 * Delegation Relayer Pattern — TDD Test Suite
 *
 * Tests the delegation-aware ownership check and related functionality
 * as specified in docs/design/delegation-relayer-spec.md
 *
 * Coverage:
 *   - Group 1: Delegation-Aware Ownership Check (4 tests)
 *   - Group 2: JLVM Guard Enforcement with Delegation (2 tests)
 *   - Group 3: RevokeDelegation On-Chain (4 tests)
 *
 * These tests are designed to FAIL until the delegation relayer pattern
 * is fully implemented according to the specification.
 */
object DelegationRelayerSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  // ── Group 1: Delegation-Aware Ownership Check — ML0 (4 tests) ──

  test("Group 1.1: accepts relayer-signed tx when valid delegation exists") {
    securityProviderResource.use { implicit s =>
      for {
        // Setup: Create participants
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB")) 
        
        // Create fiber with owner=walletA
        fiberId   <- UUIDGen.randomUUID[IO]
        
        // Create DelegationCredential (delegator=walletA, relayer=walletB)
        delegationId <- UUIDGen.randomUUID[IO]
        
        mockContext <- IO(MockL0NodeContext[IO, CalculatedState, OnChain]())
        
        // Build initial state with delegation credential
        initialState = CalculatedState(
          stateMachines = SortedMap.empty,
          scripts = SortedMap.empty,
          delegations = SortedMap(
            delegationId -> DelegationCredential(
              delegationId = delegationId,
              delegatorAddr = ownerA.wallet.address.show,
              relayerAddr = relayerB.wallet.address.show,
              sessionKeyId = "session-key-1",
              scope = List("TRANSITION_STATE_MACHINE"),
              spendLimit = 1000000L,
              spendUsed = 0L,
              expiresAtOrdinal = 999999L,
              isRevoked = false
            )
          )
        )
        
        // Create state machine fiber owned by walletA
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = StateMachineDefinition(
            initialState = StateId("ACTIVE"),
            states = SortedMap("ACTIVE" -> State(stateId = StateId("ACTIVE"), transitions = List.empty)),
            transitions = SortedMap.empty
          ),
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        stateWithFiber = initialState.copy(
          stateMachines = SortedMap(fiberId -> fiber)
        )
        
        // Create TransitionStateMachine message signed by relayerB (session key)
        message = TransitionStateMachine(
          fiberId = fiberId,
          event = FiberTransition(
            eventName = "test_transition",
            payload = Map.empty
          )
        )
        
        signedMessage <- IO(message.signedWith(relayerB.keyPair))
        
        // Test the delegation-aware ownership check (THIS SHOULD FAIL - not implemented yet)
        ownershipResult = FiberRules.updateSignedByOwnerOrDelegate(
          fiber,
          Set(relayerB.wallet.address), // Signer is relayer, not owner
          stateWithFiber,
          1000L // current ordinal
        )
        
        // Assert: Should accept because delegation exists and is valid
        _ <- expect(ownershipResult == true).failFast
        
      } yield success
    }
  }

  test("Group 1.2: rejects relayer-signed tx when no delegation exists") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB")) 
        fiberId   <- UUIDGen.randomUUID[IO]
        
        // Create fiber with owner=walletA, but NO delegation for relayerB
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = StateMachineDefinition(
            initialState = StateId("ACTIVE"),
            states = SortedMap("ACTIVE" -> State(stateId = StateId("ACTIVE"), transitions = List.empty)),
            transitions = SortedMap.empty
          ),
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        state = CalculatedState(
          stateMachines = SortedMap(fiberId -> fiber),
          scripts = SortedMap.empty,
          delegations = SortedMap.empty // No delegations
        )
        
        // Test ownership check with relayerB signature but no delegation
        ownershipResult = FiberRules.updateSignedByOwnerOrDelegate(
          fiber,
          Set(relayerB.wallet.address),
          state,
          1000L
        )
        
        // Assert: Should reject because no delegation exists
        _ <- expect(ownershipResult == false).failFast
        
      } yield success
    }
  }

  test("Group 1.3: rejects relayer-signed tx when delegation is expired") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB"))
        fiberId   <- UUIDGen.randomUUID[IO]
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create expired delegation credential
        state = CalculatedState(
          stateMachines = SortedMap.empty,
          scripts = SortedMap.empty,
          delegations = SortedMap(
            delegationId -> DelegationCredential(
              delegationId = delegationId,
              delegatorAddr = ownerA.wallet.address.show,
              relayerAddr = relayerB.wallet.address.show,
              sessionKeyId = "session-key-1",
              scope = List("TRANSITION_STATE_MACHINE"),
              spendLimit = 1000000L,
              spendUsed = 0L,
              expiresAtOrdinal = 100L, // Expired at ordinal 100
              isRevoked = false
            )
          )
        )
        
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = StateMachineDefinition(
            initialState = StateId("ACTIVE"),
            states = SortedMap("ACTIVE" -> State(stateId = StateId("ACTIVE"), transitions = List.empty)),
            transitions = SortedMap.empty
          ),
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        // Test with current ordinal > expiry
        ownershipResult = FiberRules.updateSignedByOwnerOrDelegate(
          fiber,
          Set(relayerB.wallet.address),
          state,
          101L // Current ordinal > 100 (expired)
        )
        
        // Assert: Should reject because delegation is expired
        _ <- expect(ownershipResult == false).failFast
        
      } yield success
    }
  }

  test("Group 1.4: direct owner-signed tx still works (regression)") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA  <- IO(Participant("OwnerA"))
        fiberId <- UUIDGen.randomUUID[IO]
        
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = StateMachineDefinition(
            initialState = StateId("ACTIVE"),
            states = SortedMap("ACTIVE" -> State(stateId = StateId("ACTIVE"), transitions = List.empty)),
            transitions = SortedMap.empty
          ),
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        state = CalculatedState(
          stateMachines = SortedMap(fiberId -> fiber),
          scripts = SortedMap.empty,
          delegations = SortedMap.empty
        )
        
        // Test direct owner signature (standard path)
        ownershipResult = FiberRules.updateSignedByOwnerOrDelegate(
          fiber,
          Set(ownerA.wallet.address), // Owner signs directly
          state,
          1000L
        )
        
        // Assert: Should accept (standard ownership check unchanged)
        _ <- expect(ownershipResult == true).failFast
        
      } yield success
    }
  }

  // ── Group 2: JLVM Guard Enforcement with Delegation (2 tests) ──

  test("Group 2.1: JLVM scope guard blocks relayer from unauthorized operation") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB"))
        fiberId   <- UUIDGen.randomUUID[IO]
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create delegation with READ_ONLY scope restriction
        delegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = relayerB.wallet.address.show,
          sessionKeyId = "session-key-1",
          scope = List("READ_ONLY"), // Restricted scope
          spendLimit = 1000000L,
          spendUsed = 0L,
          expiresAtOrdinal = 999999L,
          isRevoked = false
        )
        
        // Create state machine with scope guard
        guardLogic = JsonLogicExpression(
          """{"==": [{"var": "delegation.scope[0]"}, "READ_ONLY"]}"""
        )
        
        transition = Transition(
          from = StateId("ACTIVE"),
          to = StateId("ACTIVE"),
          eventName = "UPDATE_PROFILE", // Not a read-only operation
          guards = List(guardLogic),
          effects = List.empty
        )
        
        definition = StateMachineDefinition(
          initialState = StateId("ACTIVE"),
          states = SortedMap(
            "ACTIVE" -> State(
              stateId = StateId("ACTIVE"),
              transitions = List(transition)
            )
          ),
          transitions = SortedMap(
            "UPDATE_PROFILE" -> transition
          )
        )
        
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = definition,
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        state = CalculatedState(
          stateMachines = SortedMap(fiberId -> fiber),
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> delegation)
        )
        
        mockContext = MockL0NodeContext[IO, CalculatedState, OnChain](
          dataApplicationService = null,
          state = DataState.Value(OnChain.empty, state)
        )
        
        // Create transition message
        message = TransitionStateMachine(
          fiberId = fiberId,
          event = FiberTransition(
            eventName = "UPDATE_PROFILE", // This should be blocked by scope guard
            payload = Map.empty
          )
        )
        
        signedMessage <- IO(message.signedWith(relayerB.keyPair))
        
        // Validate the message - should fail JLVM guard (not ownership)
        validationResult <- FiberRules.L1.validateTransitionStateMachine(
          signedMessage,
          state,
          mockContext,
          1000L
        ).attempt
        
        // Assert: Should fail with JLVM guard failure (scope mismatch)
        _ <- expect(validationResult.isLeft).failFast
        
      } yield success
    }
  }

  test("Group 2.2: JLVM spend limit respected") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB"))
        fiberId   <- UUIDGen.randomUUID[IO]
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create delegation with spend limit nearly exhausted
        delegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = relayerB.wallet.address.show,
          sessionKeyId = "session-key-1",
          scope = List("TRANSITION_STATE_MACHINE"),
          spendLimit = 1000L,
          spendUsed = 900L, // Nearly at limit
          expiresAtOrdinal = 999999L,
          isRevoked = false
        )
        
        // Create guard that checks spend limit
        guardLogic = JsonLogicExpression(
          """{">": [{"var": "delegation.spendLimit"}, {"+": [{"var": "delegation.spendUsed"}, 200]}]}"""
        )
        
        transition = Transition(
          from = StateId("ACTIVE"),
          to = StateId("ACTIVE"),
          eventName = "SPEND_TOKENS",
          guards = List(guardLogic), // Should block if spend would exceed limit
          effects = List.empty
        )
        
        definition = StateMachineDefinition(
          initialState = StateId("ACTIVE"),
          states = SortedMap(
            "ACTIVE" -> State(
              stateId = StateId("ACTIVE"),
              transitions = List(transition)
            )
          ),
          transitions = SortedMap(
            "SPEND_TOKENS" -> transition
          )
        )
        
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = definition,
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map("spendAmount" -> 200), // This would exceed limit
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        state = CalculatedState(
          stateMachines = SortedMap(fiberId -> fiber),
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> delegation)
        )
        
        mockContext = MockL0NodeContext[IO, CalculatedState, OnChain](
          dataApplicationService = null,
          state = DataState.Value(OnChain.empty, state)
        )
        
        message = TransitionStateMachine(
          fiberId = fiberId,
          event = FiberTransition(
            eventName = "SPEND_TOKENS",
            payload = Map("amount" -> 200) // Would exceed spend limit
          )
        )
        
        signedMessage <- IO(message.signedWith(relayerB.keyPair))
        
        // Should fail spend limit check
        validationResult <- FiberRules.L1.validateTransitionStateMachine(
          signedMessage,
          state,
          mockContext,
          1000L
        ).attempt
        
        // Assert: Should fail with spend limit exceeded
        _ <- expect(validationResult.isLeft).failFast
        
      } yield success
    }
  }

  // ── Group 3: RevokeDelegation On-Chain (4 tests) ──

  test("Group 3.1: RevokeDelegation message sets isRevoked=true in CalculatedState") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB"))
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create active delegation
        delegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = relayerB.wallet.address.show,
          sessionKeyId = "session-key-1",
          scope = List("TRANSITION_STATE_MACHINE"),
          spendLimit = 1000000L,
          spendUsed = 0L,
          expiresAtOrdinal = 999999L,
          isRevoked = false
        )
        
        initialState = CalculatedState(
          stateMachines = SortedMap.empty,
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> delegation)
        )
        
        // Create RevokeDelegation message
        revokeMessage = RevokeDelegation(
          delegationId = delegationId,
          reason = "Test revocation"
        )
        
        signedRevoke <- IO(revokeMessage.signedWith(ownerA.keyPair))
        
        // Apply the revocation via combiner (THIS SHOULD FAIL - RevokeDelegation not implemented)
        combiner = new Combiner[IO]()
        updatedState <- combiner.combine(
          OnChain.empty,
          initialState,
          List(signedRevoke)
        )
        
        // Assert: Delegation should now be revoked
        revokedDelegation = updatedState.delegations.get(delegationId)
        _ <- expect(revokedDelegation.exists(_.isRevoked)).failFast
        
      } yield success
    }
  }

  test("Group 3.2: relayer rejected after revocation") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        relayerB  <- IO(Participant("RelayerB"))
        fiberId   <- UUIDGen.randomUUID[IO]
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create revoked delegation
        revokedDelegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = relayerB.wallet.address.show,
          sessionKeyId = "session-key-1",
          scope = List("TRANSITION_STATE_MACHINE"),
          spendLimit = 1000000L,
          spendUsed = 0L,
          expiresAtOrdinal = 999999L,
          isRevoked = true // Already revoked
        )
        
        fiber = StateMachineFiberRecord(
          fiberId = fiberId,
          definition = StateMachineDefinition(
            initialState = StateId("ACTIVE"),
            states = SortedMap("ACTIVE" -> State(stateId = StateId("ACTIVE"), transitions = List.empty)),
            transitions = SortedMap.empty
          ),
          owners = Set(ownerA.wallet.address),
          currentState = StateId("ACTIVE"),
          stateData = Map.empty,
          accessControlPolicy = AccessControlPolicy.Public,
          sequenceNumber = 0L
        )
        
        state = CalculatedState(
          stateMachines = SortedMap(fiberId -> fiber),
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> revokedDelegation)
        )
        
        // Try to use revoked delegation
        ownershipResult = FiberRules.updateSignedByOwnerOrDelegate(
          fiber,
          Set(relayerB.wallet.address),
          state,
          1000L
        )
        
        // Assert: Should reject because delegation is revoked
        _ <- expect(ownershipResult == false).failFast
        
      } yield success
    }
  }

  test("Group 3.3: double revocation rejected") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA    <- IO(Participant("OwnerA"))
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create already-revoked delegation
        revokedDelegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = "relayer-addr",
          sessionKeyId = "session-key-1",
          scope = List("TRANSITION_STATE_MACHINE"),
          spendLimit = 1000000L,
          spendUsed = 0L,
          expiresAtOrdinal = 999999L,
          isRevoked = true // Already revoked
        )
        
        state = CalculatedState(
          stateMachines = SortedMap.empty,
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> revokedDelegation)
        )
        
        mockContext = MockL0NodeContext[IO, CalculatedState, OnChain](
          dataApplicationService = null,
          state = DataState.Value(OnChain.empty, state)
        )
        
        // Try to revoke again
        doubleRevoke = RevokeDelegation(
          delegationId = delegationId,
          reason = "Second revocation attempt"
        )
        
        signedDoubleRevoke <- IO(doubleRevoke.signedWith(ownerA.keyPair))
        
        // Validation should fail (THIS SHOULD FAIL - validation not implemented)
        validationResult <- FiberRules.L1.validateRevokeDelegation(
          signedDoubleRevoke,
          state,
          mockContext
        ).attempt
        
        // Assert: Should fail with DELEGATION_ALREADY_REVOKED
        _ <- expect(validationResult.isLeft).failFast
        
      } yield success
    }
  }

  test("Group 3.4: non-delegator cannot revoke") {
    securityProviderResource.use { implicit s =>
      for {
        ownerA     <- IO(Participant("OwnerA"))
        attackerC  <- IO(Participant("AttackerC"))
        delegationId <- UUIDGen.randomUUID[IO]
        
        // Create delegation owned by ownerA
        delegation = DelegationCredential(
          delegationId = delegationId,
          delegatorAddr = ownerA.wallet.address.show,
          relayerAddr = "relayer-addr",
          sessionKeyId = "session-key-1",
          scope = List("TRANSITION_STATE_MACHINE"),
          spendLimit = 1000000L,
          spendUsed = 0L,
          expiresAtOrdinal = 999999L,
          isRevoked = false
        )
        
        state = CalculatedState(
          stateMachines = SortedMap.empty,
          scripts = SortedMap.empty,
          delegations = SortedMap(delegationId -> delegation)
        )
        
        mockContext = MockL0NodeContext[IO, CalculatedState, OnChain](
          dataApplicationService = null,
          state = DataState.Value(OnChain.empty, state)
        )
        
        // Attempt revocation by non-delegator
        unauthorizedRevoke = RevokeDelegation(
          delegationId = delegationId,
          reason = "Unauthorized attempt"
        )
        
        signedUnauthorized <- IO(unauthorizedRevoke.signedWith(attackerC.keyPair))
        
        // Should fail authorization check (THIS SHOULD FAIL - validation not implemented)  
        validationResult <- FiberRules.L1.validateRevokeDelegation(
          signedUnauthorized,
          state,
          mockContext
        ).attempt
        
        // Assert: Should fail with UNAUTHORIZED_REVOCATION
        _ <- expect(validationResult.isLeft).failFast
        
      } yield success
    }
  }
}

// ── Missing Types (These need to be implemented) ──

/**
 * Delegation credential record stored in CalculatedState.
 * This type is referenced in the tests but doesn't exist yet.
 */
case class DelegationCredential(
  delegationId: UUID,
  delegatorAddr: String,
  relayerAddr: String,
  sessionKeyId: String,
  scope: List[String],
  spendLimit: Long,
  spendUsed: Long,
  expiresAtOrdinal: Long,
  isRevoked: Boolean
) {
  def isActive(currentOrdinal: Long): Boolean = {
    !isRevoked && currentOrdinal <= expiresAtOrdinal
  }
}

/**
 * RevokeDelegation message type.
 * This needs to be added to the OttochainMessage ADT in Updates.scala.
 */
case class RevokeDelegation(
  delegationId: UUID,
  reason: String
) extends OttochainMessage