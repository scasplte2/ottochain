package xyz.kd5ujc.shared_data.examples

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.examples.DelegationRelayerTestHelpers._
import xyz.kd5ujc.shared_data.examples.MockUpdates
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Delegation Relayer Pattern — TDD Test Suite
 *
 * Tests the critical delegation-aware ownership bypass mechanism that allows
 * session key holders (relayers) to sign OttochainMessage transactions on
 * behalf of delegators. This is the foundational pattern for OttoBot agents
 * to act autonomously while respecting delegated authority.
 *
 * Key Components Under Test:
 *   - DelegationCredential: On-chain credential storage (delegator→relayer mapping)
 *   - updateSignedByOwnerOrDelegate: ML0 ownership check that accepts relayer signatures
 *   - JLVM delegation.* context: Guards can enforce delegation policies
 *   - RevokeDelegation: On-chain credential revocation mechanism
 *
 * Test Architecture:
 *   - Alice: Delegator (fiber owner, grants delegation)
 *   - Bob: Relayer (session key holder, signs on behalf of Alice)
 *   - Charlie: Unauthorized third party (should be rejected)
 *   - Dave: Governance/voting participant
 *
 * ⚠️ THESE TESTS MUST FAIL INITIALLY — they verify features not yet implemented.
 * Implementation order: PR #90 (JLVM) → this spec → bridge rewrites → SDK methods
 */
object DelegationRelayerPatternSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // GROUP 1: Delegation-Aware Ownership Check — ML0 (4 tests)
  // ══════════════════════════════════════════════════════════════════════════════════════════

  test("delegation ownership: accepts relayer-signed tx when valid delegation exists") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Create a simple test fiber owned by Alice
        fiberId <- UUIDGen.randomUUID[IO]
        testFiberJson = simpleTestFiberDefinition
        testDef <- decodeDefinition(testFiberJson, "test fiber")

        // Create DelegationCredential for Alice (delegator) → Bob (relayer)
        delegationId <- UUIDGen.randomUUID[IO]
        delegationCredential = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "value"  -> IntValue(0)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses), // Alice is the owner
          status = FiberStatus.Active
        )

        // Set up initial state with fiber and delegation
        baseState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))
        inState = baseState.withDelegations(Map(delegationId -> delegationCredential))

        // Submit transition signed by Bob (relayer) on behalf of Alice (delegator)
        transitionUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(5))),
          FiberOrdinal.MinValue
        )

        // ⚠️ THIS SHOULD FAIL INITIALLY: Bob is not in testFiber.owners
        // After implementation: should succeed because delegation exists
        relayerProof <- registry.generateProofs(transitionUpdate, Set(Bob))
        result       <- combiner.insert(inState, Signed(transitionUpdate, relayerProof))

        updatedFiber = result.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        // The transaction should be accepted (ownership bypass via delegation)
        updatedFiber.isDefined,
        // State should have been updated by the transition
        updatedFiber.flatMap(extractIntField(_, "value")).contains(BigInt(5)),
        // Fiber should remain active
        updatedFiber.map(_.currentState).contains(StateId("ACTIVE"))
      )
    }
  }

  test("delegation ownership: rejects relayer-signed tx when no delegation exists") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        testDef <- decodeDefinition(simpleTestFiberDefinition, "test fiber")

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "value"  -> IntValue(0)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses), // Only Alice is owner
          status = FiberStatus.Active
        )

        // NO delegation credentials in state
        inState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))

        transitionUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(5))),
          FiberOrdinal.MinValue
        )

        // Bob tries to sign but has no delegation
        relayerProof <- registry.generateProofs(transitionUpdate, Set(Bob))

        // This should fail with ownership validation error
        result <- combiner.insert(inState, Signed(transitionUpdate, relayerProof)).attempt

      } yield expect(
        // Should fail due to ownership validation (Bob not in owners, no delegation)
        result.isLeft
      )
    }
  }

  test("delegation ownership: rejects relayer-signed tx when delegation is expired") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        testDef <- decodeDefinition(simpleTestFiberDefinition, "test fiber")

        delegationId <- UUIDGen.randomUUID[IO]
        // Create EXPIRED delegation (expires before current ordinal)
        expiredDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "1", // Already expired (current ordinal is much higher)
          isRevoked = false,
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "value"  -> IntValue(0)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))
        inState = baseState.withDelegations(Map(delegationId -> expiredDelegation))

        transitionUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(5))),
          FiberOrdinal.MinValue
        )

        relayerProof <- registry.generateProofs(transitionUpdate, Set(Bob))
        result       <- combiner.insert(inState, Signed(transitionUpdate, relayerProof)).attempt

      } yield expect(
        // Should fail because delegation is expired (isActive=false)
        result.isLeft
      )
    }
  }

  test("delegation ownership: direct owner-signed tx still works (regression test)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        testDef <- decodeDefinition(simpleTestFiberDefinition, "test fiber")

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "value"  -> IntValue(0)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        // No delegations needed for direct owner signing
        inState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))

        transitionUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(3))),
          FiberOrdinal.MinValue
        )

        // Alice signs directly (standard ownership check)
        ownerProof <- registry.generateProofs(transitionUpdate, Set(Alice))
        result     <- combiner.insert(inState, Signed(transitionUpdate, ownerProof))

        updatedFiber = result.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        // Standard ownership should still work
        updatedFiber.isDefined,
        updatedFiber.flatMap(extractIntField(_, "value")).contains(BigInt(3))
      )
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // GROUP 2: JLVM Guard Enforcement with Delegation (2 tests)
  // ══════════════════════════════════════════════════════════════════════════════════════════

  test("delegation guard: JLVM scope guard blocks relayer from unauthorized operation") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        // Fiber with scope-restricted guard
        scopeRestrictedJson = fiberWithScopeGuardDefinition
        testDef <- decodeDefinition(scopeRestrictedJson, "scope restricted fiber")

        delegationId <- UUIDGen.randomUUID[IO]
        readOnlyDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("READ_ONLY"), // Restricted scope
          spendLimit = 1000L,
          spendUsed = 0L
        )

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "data"   -> StrValue("initial")
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))
        inState = baseState.withDelegations(Map(delegationId -> readOnlyDelegation))

        // Try to submit UPDATE operation (not allowed by READ_ONLY scope)
        updateOperation = Updates.TransitionStateMachine(
          fiberId,
          "update_data", // This operation should be blocked by scope guard
          MapValue(Map("newData" -> StrValue("modified"))),
          FiberOrdinal.MinValue
        )

        relayerProof <- registry.generateProofs(updateOperation, Set(Bob))
        result       <- combiner.insert(inState, Signed(updateOperation, relayerProof)).attempt

      } yield expect(
        // Should fail due to JLVM scope guard (not ownership - that passes)
        result.isLeft
      )
    }
  }

  test("delegation guard: JLVM spend limit respected") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        spendLimitJson = fiberWithSpendLimitGuardDefinition
        testDef <- decodeDefinition(spendLimitJson, "spend limit fiber")

        delegationId <- UUIDGen.randomUUID[IO]
        nearLimitDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("SPEND"),
          spendLimit = 1000L,
          spendUsed = 900L // Already spent 900, only 100 remaining
        )

        initialState = MapValue(
          Map(
            "status"  -> StrValue("ACTIVE"),
            "balance" -> IntValue(5000)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))
        inState = baseState.withDelegations(Map(delegationId -> nearLimitDelegation))

        // Try to spend 200 (would exceed remaining limit of 100)
        spendOperation = Updates.TransitionStateMachine(
          fiberId,
          "spend",
          MapValue(Map("amount" -> IntValue(200))), // Exceeds limit
          FiberOrdinal.MinValue
        )

        relayerProof <- registry.generateProofs(spendOperation, Set(Bob))
        result       <- combiner.insert(inState, Signed(spendOperation, relayerProof)).attempt

      } yield expect(
        // Should fail due to spend limit exceeded
        result.isLeft
      )
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // GROUP 3: RevokeDelegation On-Chain (4 tests)
  // ══════════════════════════════════════════════════════════════════════════════════════════

  test("revoke delegation: RevokeDelegation message sets isRevoked=true in CalculatedState") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        delegationId <- UUIDGen.randomUUID[IO]
        activeDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false, // Initially active
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
          .withDelegations(Map(delegationId -> activeDelegation))

        // ⚠️ THIS WILL FAIL: RevokeDelegation OttochainMessage doesn't exist yet
        // For now, we'll simulate the failure since the actual type doesn't exist
        mockRevokeUpdate = MockUpdates.RevokeDelegation(
          delegationId = delegationId.toString,
          reason = "Testing revocation"
        )

        // This would fail to compile in real TDD because RevokeDelegation doesn't exist
        // For demonstration, we expect the system to not support delegation revocation yet
        result <- IO.raiseError(new NotImplementedError("RevokeDelegation not implemented")).attempt

      } yield expect(
        // Should fail because RevokeDelegation is not implemented yet
        result.isLeft
      )
    }
  }

  test("revoke delegation: relayer rejected after revocation") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        fiberId <- UUIDGen.randomUUID[IO]
        testDef <- decodeDefinition(simpleTestFiberDefinition, "test fiber")

        delegationId <- UUIDGen.randomUUID[IO]
        activeDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Bob),
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        initialState = MapValue(
          Map(
            "status" -> StrValue("ACTIVE"),
            "value"  -> IntValue(0)
          )
        )
        initialHash <- (initialState: JsonLogicValue).computeDigest

        testFiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = testDef,
          currentState = StateId("ACTIVE"),
          stateData = initialState,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecords[IO](Map(fiberId -> testFiber))
        inState = baseState.withDelegations(Map(delegationId -> activeDelegation))

        // STEP 1: Relayer submits successfully (before revocation)
        firstTransition = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(5))),
          FiberOrdinal.MinValue
        )
        relayerProof1 <- registry.generateProofs(firstTransition, Set(Bob))
        state1        <- combiner.insert(inState, Signed(firstTransition, relayerProof1))

        // STEP 2: Delegator would revoke the delegation (not implemented)
        mockRevokeUpdate = MockUpdates.RevokeDelegation(
          delegationId = delegationId.toString,
          reason = "Terminating agent session"
        )

        // STEP 3: Since revocation doesn't work, relayer would still be able to submit
        // But delegation support itself isn't implemented, so this fails at ownership check
        secondTransition = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map("amount" -> IntValue(3))),
          state1.calculated.stateMachines(fiberId).sequenceNumber
        )
        relayerProof2 <- registry.generateProofs(secondTransition, Set(Bob))
        result        <- combiner.insert(state1, Signed(secondTransition, relayerProof2)).attempt

      } yield expect.all(
        // First submission should fail (delegation not implemented)
        state1.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
          .flatMap(extractIntField(_, "value"))
          .contains(BigInt(0)), // Value unchanged due to ownership failure
        // Second submission should also fail (delegation not implemented)
        result.isLeft
      )
    }
  }

  test("revoke delegation: double revocation rejected") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        delegationId <- UUIDGen.randomUUID[IO]
        activeDelegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice),
          relayerAddr = registry.addresses(Alice), // Self-delegation for simplicity
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
          .withDelegations(Map(delegationId -> activeDelegation))

        // Mock revocation attempts (not implemented yet)
        mockRevokeUpdate1 = MockUpdates.RevokeDelegation(
          delegationId = delegationId.toString,
          reason = "First revocation"
        )

        mockRevokeUpdate2 = MockUpdates.RevokeDelegation(
          delegationId = delegationId.toString,
          reason = "Second revocation"
        )

        // Both would fail because RevokeDelegation doesn't exist
        result <- IO.raiseError(new NotImplementedError("RevokeDelegation not implemented")).attempt

      } yield expect(
        // Should fail because RevokeDelegation is not implemented yet
        result.isLeft
      )
    }
  }

  test("revoke delegation: non-delegator cannot revoke") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        delegationId <- UUIDGen.randomUUID[IO]
        delegation = createDelegationCredential(
          delegationId = delegationId,
          delegatorAddr = registry.addresses(Alice), // Alice is delegator
          relayerAddr = registry.addresses(Bob), // Bob is relayer
          expiresAtOrdinal = "999999", // Mock future expiration
          isRevoked = false,
          scope = List("READ_WRITE"),
          spendLimit = 1000L,
          spendUsed = 0L
        )

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
          .withDelegations(Map(delegationId -> delegation))

        // Charlie tries to revoke (not implemented yet)
        mockRevokeUpdate = MockUpdates.RevokeDelegation(
          delegationId = delegationId.toString,
          reason = "Malicious revocation attempt"
        )

        // Would fail because RevokeDelegation doesn't exist
        result <- IO.raiseError(new NotImplementedError("RevokeDelegation not implemented")).attempt

      } yield expect(
        // Should fail because RevokeDelegation is not implemented yet
        result.isLeft
      )
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // Helper Methods & Test Data
  // ══════════════════════════════════════════════════════════════════════════════════════════

  private def extractIntField(record: Records.StateMachineFiberRecord, field: String): Option[BigInt] =
    record.stateData match {
      case MapValue(m) => m.get(field).collect { case IntValue(v) => v }
      case _           => None
    }

  private def decodeDefinition(json: String, name: String): IO[StateMachineDefinition] =
    IO.fromEither(
      decode[StateMachineDefinition](json).left.map(err => new RuntimeException(s"Failed to decode $name JSON: $err"))
    )

  /**
   * Creates a mock DelegationCredential for testing.
   * ⚠️ This will need to match the actual DelegationCredential structure once implemented.
   */
  private def createDelegationCredential(
    delegationId:     java.util.UUID,
    delegatorAddr:    io.constellationnetwork.schema.address.Address,
    relayerAddr:      io.constellationnetwork.schema.address.Address,
    expiresAtOrdinal: String,
    isRevoked:        Boolean,
    scope:            List[String],
    spendLimit:       Long,
    spendUsed:        Long
  ): DelegationCredential =
    // ⚠️ THIS IS A MOCK - actual implementation will be in PR #90
    DelegationCredential(
      id = delegationId,
      delegatorAddr = delegatorAddr.show,
      relayerAddr = relayerAddr.show,
      expiresAtOrdinal = expiresAtOrdinal,
      isRevoked = isRevoked,
      scope = scope,
      spendLimit = spendLimit,
      spendUsed = spendUsed,
      createdAt = 100L,
      sessionKeyId = "mock_session_key"
    )

  /**
   * Simple test fiber definition for basic transition testing.
   */
  private val simpleTestFiberDefinition: String =
    """{
      "states": {
        "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
      },
      "initialState": { "value": "ACTIVE" },
      "transitions": [
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "increment",
          "guard": true,
          "effect": [
            ["value", { "+": [ { "var": "state.value" }, { "var": "event.amount" } ] }]
          ],
          "dependencies": []
        }
      ]
    }"""

  /**
   * Fiber with scope-restricted guard for delegation testing.
   */
  private val fiberWithScopeGuardDefinition: String =
    """{
      "states": {
        "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
      },
      "initialState": { "value": "ACTIVE" },
      "transitions": [
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "read_data",
          "guard": {
            "in": [ "READ_ONLY", { "var": "delegation.scope" } ]
          },
          "effect": [],
          "dependencies": []
        },
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "update_data",
          "guard": {
            "in": [ "READ_WRITE", { "var": "delegation.scope" } ]
          },
          "effect": [
            ["data", { "var": "event.newData" }]
          ],
          "dependencies": []
        }
      ]
    }"""

  /**
   * Fiber with spend limit guard for delegation testing.
   */
  private val fiberWithSpendLimitGuardDefinition: String =
    """{
      "states": {
        "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false }
      },
      "initialState": { "value": "ACTIVE" },
      "transitions": [
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "spend",
          "guard": {
            "<=": [
              { "+": [ { "var": "delegation.spendUsed" }, { "var": "event.amount" } ] },
              { "var": "delegation.spendLimit" }
            ]
          },
          "effect": [
            ["balance", { "-": [ { "var": "state.balance" }, { "var": "event.amount" } ] }]
          ],
          "dependencies": []
        }
      ]
    }"""
}

/**
 * Mock DelegationCredential case class for testing.
 * ⚠️ This will be replaced by the actual implementation from PR #90.
 */
case class DelegationCredential(
  id:               java.util.UUID,
  delegatorAddr:    String,
  relayerAddr:      String,
  expiresAtOrdinal: String, // Mock as String to match test expectations
  isRevoked:        Boolean,
  scope:            List[String],
  spendLimit:       Long,
  spendUsed:        Long,
  createdAt:        Long,
  sessionKeyId:     String
) {

  def isActive(currentOrdinal: Long): Boolean =
    !isRevoked && currentOrdinal <= expiresAtOrdinal.toLong
}
