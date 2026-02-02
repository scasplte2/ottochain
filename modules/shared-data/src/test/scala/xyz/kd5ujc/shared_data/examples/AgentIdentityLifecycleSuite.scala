package xyz.kd5ujc.shared_data.examples

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

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Agent Identity Lifecycle — OttoChain Example
 *
 * Demonstrates a decentralized agent reputation protocol using OttoChain state machines.
 * AI agents register on-chain, accumulate attestations from platforms, build reputation,
 * and can be challenged/suspended for bad behavior.
 *
 * Fiber Architecture:
 *   - AgentIdentity: Core identity + reputation state machine per agent
 *   - PlatformRegistry: Tracks registered attestation source platforms
 *   - ReputationOracle: Script oracle that computes composite reputation scores
 *
 * State Lifecycle:
 *   Registered → Active → Challenged → Suspended → Probation → Active (recovery)
 *                       ↘ Active (challenge dismissed)
 *   Active → Withdrawn (voluntary exit, stake returned, history preserved)
 */
object AgentIdentityLifecycleSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  // ── Test 1: Happy path — registration, attestations, reputation growth ──
  test("agent identity: registration and reputation building through attestations") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie, Dave))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        agentfiberId    <- UUIDGen.randomUUID[IO]
        platformfiberId <- UUIDGen.randomUUID[IO]

        // ── AgentIdentity State Machine ──
        // Tracks an agent's lifecycle: registration, attestation accumulation,
        // challenge/dispute resolution, suspension, probation, and withdrawal.
        agentIdentityJson =
          s"""{
          "states": {
            "registered": { "id": { "value": "registered" }, "isFinal": false },
            "active": { "id": { "value": "active" }, "isFinal": false },
            "challenged": { "id": { "value": "challenged" }, "isFinal": false },
            "suspended": { "id": { "value": "suspended" }, "isFinal": false },
            "probation": { "id": { "value": "probation" }, "isFinal": false },
            "withdrawn": { "id": { "value": "withdrawn" }, "isFinal": true }
          },
          "initialState": { "value": "registered" },
          "transitions": [
            {
              "from": { "value": "registered" },
              "to": { "value": "active" },
              "eventName": "first_attestation",
              "guard": {
                "and": [
                  { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
                  { ">": [ { "var": "state.stakeAmount" }, 0 ] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["activatedAt", { "var": "event.timestamp" }],
                ["attestationCount", 1],
                ["completionCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 1, 0 ] }],
                ["violationCount", 0],
                ["behavioralCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 1, 0 ] }],
                ["vouchCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 1, 0 ] }],
                ["lastAttestationAt", { "var": "event.timestamp" }],
                ["lastAttestationPlatform", { "var": "event.platformId" }],
                ["reputationScore", 10]
              ],
              "dependencies": ["${platformfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "active" },
              "eventName": "submit_attestation",
              "guard": {
                "and": [
                  { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
                  { "in": [ { "var": "event.attestationType" }, ["BEHAVIORAL", "COMPLETION", "VOUCH"] ] }
                ]
              },
              "effect": [
                ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
                ["completionCount", { "+": [ { "var": "state.completionCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 1, 0 ] } ] }],
                ["behavioralCount", { "+": [ { "var": "state.behavioralCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 1, 0 ] } ] }],
                ["vouchCount", { "+": [ { "var": "state.vouchCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 1, 0 ] } ] }],
                ["lastAttestationAt", { "var": "event.timestamp" }],
                ["lastAttestationPlatform", { "var": "event.platformId" }],
                ["reputationScore", { "+": [
                  { "var": "state.reputationScore" },
                  { "if": [
                    { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 5,
                    { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 3,
                    { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 2,
                    1
                  ]}
                ]}]
              ],
              "dependencies": ["${platformfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "active" },
              "eventName": "submit_violation",
              "guard": {
                "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ]
              },
              "effect": [
                ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
                ["violationCount", { "+": [ { "var": "state.violationCount" }, 1 ] }],
                ["lastViolationAt", { "var": "event.timestamp" }],
                ["lastViolationReason", { "var": "event.reason" }],
                ["lastAttestationAt", { "var": "event.timestamp" }],
                ["lastAttestationPlatform", { "var": "event.platformId" }],
                ["reputationScore", { "max": [ 0, { "-": [ { "var": "state.reputationScore" }, 10 ] } ] }]
              ],
              "dependencies": ["${platformfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "challenged" },
              "eventName": "file_challenge",
              "guard": {
                "and": [
                  { ">": [ { "var": "event.challengerStake" }, 0 ] },
                  { "!==": [ { "var": "event.challengerId" }, { "var": "state.operatorId" } ] }
                ]
              },
              "effect": [
                ["status", "challenged"],
                ["challengedAt", { "var": "event.timestamp" }],
                ["challengerId", { "var": "event.challengerId" }],
                ["challengerStake", { "var": "event.challengerStake" }],
                ["challengeReason", { "var": "event.reason" }],
                ["challengeEvidenceHash", { "var": "event.evidenceHash" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "challenged" },
              "to": { "value": "active" },
              "eventName": "dismiss_challenge",
              "guard": {
                ">=": [ { "var": "event.dismissalVotes" }, 3 ]
              },
              "effect": [
                ["status", "active"],
                ["challengeResolution", "dismissed"],
                ["challengeResolvedAt", { "var": "event.timestamp" }],
                ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 5 ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "challenged" },
              "to": { "value": "suspended" },
              "eventName": "uphold_challenge",
              "guard": {
                ">=": [ { "var": "event.upholdVotes" }, 3 ]
              },
              "effect": [
                ["status", "suspended"],
                ["suspendedAt", { "var": "event.timestamp" }],
                ["challengeResolution", "upheld"],
                ["challengeResolvedAt", { "var": "event.timestamp" }],
                ["stakeSlashed", { "/": [ { "var": "state.stakeAmount" }, 2 ] }],
                ["stakeAmount", { "/": [ { "var": "state.stakeAmount" }, 2 ] }],
                ["reputationScore", { "max": [ 0, { "-": [ { "var": "state.reputationScore" }, 25 ] } ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "probation" },
              "eventName": "begin_probation",
              "guard": {
                "and": [
                  { ">": [ { "var": "event.reinstateStake" }, 0 ] },
                  { ">=": [
                    { "-": [ { "var": "event.timestamp" }, { "var": "state.suspendedAt" } ] },
                    { "var": "state.suspensionPeriod" }
                  ]}
                ]
              },
              "effect": [
                ["status", "probation"],
                ["probationStartedAt", { "var": "event.timestamp" }],
                ["stakeAmount", { "+": [ { "var": "state.stakeAmount" }, { "var": "event.reinstateStake" } ] }],
                ["probationAttestationsRequired", 10]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "probation" },
              "to": { "value": "probation" },
              "eventName": "probation_attestation",
              "guard": {
                "and": [
                  { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
                  { ">": [ { "var": "state.probationAttestationsRequired" }, 0 ] }
                ]
              },
              "effect": [
                ["probationAttestationsRequired", { "-": [ { "var": "state.probationAttestationsRequired" }, 1 ] }],
                ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
                ["lastAttestationAt", { "var": "event.timestamp" }],
                ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 1 ] }]
              ],
              "dependencies": ["${platformfiberId}"]
            },
            {
              "from": { "value": "probation" },
              "to": { "value": "active" },
              "eventName": "complete_probation",
              "guard": {
                "<=": [ { "var": "state.probationAttestationsRequired" }, 0 ]
              },
              "effect": [
                ["status", "active"],
                ["probationCompletedAt", { "var": "event.timestamp" }],
                ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 10 ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "active" },
              "to": { "value": "withdrawn" },
              "eventName": "withdraw",
              "guard": true,
              "effect": [
                ["status", "withdrawn"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["stakeReturned", { "var": "state.stakeAmount" }],
                ["finalReputationScore", { "var": "state.reputationScore" }],
                ["finalAttestationCount", { "var": "state.attestationCount" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // ── PlatformRegistry State Machine ──
        // Tracks platform verification. Only verified platforms can submit attestations.
        platformRegistryJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "verified": { "id": { "value": "verified" }, "isFinal": false },
            "suspended": { "id": { "value": "suspended" }, "isFinal": false },
            "revoked": { "id": { "value": "revoked" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "verified" },
              "eventName": "verify_platform",
              "guard": {
                "and": [
                  { ">": [ { "var": "event.verificationScore" }, 0 ] },
                  { ">": [ { "var": "event.platformStake" }, 0 ] }
                ]
              },
              "effect": [
                ["status", "verified"],
                ["verifiedAt", { "var": "event.timestamp" }],
                ["platformStake", { "var": "event.platformStake" }],
                ["verificationScore", { "var": "event.verificationScore" }],
                ["attestationsSubmitted", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "verified" },
              "to": { "value": "verified" },
              "eventName": "record_attestation_submitted",
              "guard": true,
              "effect": [
                ["attestationsSubmitted", { "+": [ { "var": "state.attestationsSubmitted" }, 1 ] }],
                ["lastAttestationAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "verified" },
              "to": { "value": "suspended" },
              "eventName": "suspend_platform",
              "guard": true,
              "effect": [
                ["status", "suspended"],
                ["suspendedAt", { "var": "event.timestamp" }],
                ["suspensionReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "verified" },
              "eventName": "reinstate_platform",
              "guard": true,
              "effect": [
                ["status", "verified"],
                ["reinstatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "revoked" },
              "eventName": "revoke_platform",
              "guard": true,
              "effect": [
                ["status", "revoked"],
                ["revokedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        agentDef <- IO.fromEither(
          decode[StateMachineDefinition](agentIdentityJson).left.map(err =>
            new RuntimeException(s"Failed to decode agent identity JSON: $err")
          )
        )

        platformDef <- IO.fromEither(
          decode[StateMachineDefinition](platformRegistryJson).left.map(err =>
            new RuntimeException(s"Failed to decode platform registry JSON: $err")
          )
        )

        // Initial agent state — freshly registered with stake
        agentData = MapValue(
          Map(
            "agentName"   -> StrValue("OttoBot"),
            "operatorId"  -> StrValue(registry.addresses(Alice).toString),
            "stakeAmount" -> IntValue(1000),
            "capabilities" -> ArrayValue(
              List(
                StrValue("code_review"),
                StrValue("content_moderation"),
                StrValue("social_engagement")
              )
            ),
            "registeredAt" -> IntValue(100),
            "status"       -> StrValue("registered"),
            "isGenesis"    -> BoolValue(true)
          )
        )
        agentHash <- (agentData: JsonLogicValue).computeDigest

        // Initial platform state — pending verification
        platformData = MapValue(
          Map(
            "platformName" -> StrValue("GitHub"),
            "platformType" -> StrValue("code_hosting"),
            "operatorId"   -> StrValue(registry.addresses(Bob).toString),
            "status"       -> StrValue("pending")
          )
        )
        platformHash <- (platformData: JsonLogicValue).computeDigest

        agentFiber = Records.StateMachineFiberRecord(
          fiberId = agentfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = agentDef,
          currentState = StateId("registered"),
          stateData = agentData,
          stateDataHash = agentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        platformFiber = Records.StateMachineFiberRecord(
          fiberId = platformfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = platformDef,
          currentState = StateId("pending"),
          stateData = platformData,
          stateDataHash = platformHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            agentfiberId    -> agentFiber,
            platformfiberId -> platformFiber
          )
        )

        // ── STEP 1: Verify the platform ──
        verifyPlatformUpdate = Updates.TransitionStateMachine(
          platformfiberId,
          "verify_platform",
          MapValue(
            Map(
              "timestamp"         -> IntValue(200),
              "verificationScore" -> IntValue(95),
              "platformStake"     -> IntValue(500)
            )
          ),
          FiberOrdinal.MinValue
        )
        verifyPlatformProof <- registry.generateProofs(verifyPlatformUpdate, Set(Bob))
        state1              <- combiner.insert(inState, Signed(verifyPlatformUpdate, verifyPlatformProof))

        // Verify platform is now verified
        platformAfterVerify = state1.calculated.stateMachines
          .get(platformfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect(platformAfterVerify.map(_.currentState).contains(StateId("verified")))

        // ── STEP 2: First attestation — activates the agent ──
        firstAttestationUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "first_attestation",
          MapValue(
            Map(
              "timestamp"       -> IntValue(300),
              "attestationType" -> StrValue("COMPLETION"),
              "platformId"      -> StrValue("github"),
              "evidenceHash"    -> StrValue("abc123")
            )
          ),
          FiberOrdinal.MinValue
        )
        firstAttestationProof <- registry.generateProofs(firstAttestationUpdate, Set(Alice))
        state2                <- combiner.insert(state1, Signed(firstAttestationUpdate, firstAttestationProof))

        agentAfterActivation = state2.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect.all(
          agentAfterActivation.map(_.currentState).contains(StateId("active")),
          agentAfterActivation.flatMap(extractStrField(_, "status")).contains("active")
        )

        // ── STEP 3: Submit several attestations to build reputation ──
        attest1SeqNum = state2.calculated.stateMachines(agentfiberId).sequenceNumber
        attestBehavioralUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "submit_attestation",
          MapValue(
            Map(
              "timestamp"       -> IntValue(400),
              "attestationType" -> StrValue("BEHAVIORAL"),
              "platformId"      -> StrValue("github")
            )
          ),
          attest1SeqNum
        )
        attestBehavioralProof <- registry.generateProofs(attestBehavioralUpdate, Set(Alice))
        state3                <- combiner.insert(state2, Signed(attestBehavioralUpdate, attestBehavioralProof))

        attest2SeqNum = state3.calculated.stateMachines(agentfiberId).sequenceNumber
        attestCompletionUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "submit_attestation",
          MapValue(
            Map(
              "timestamp"       -> IntValue(500),
              "attestationType" -> StrValue("COMPLETION"),
              "platformId"      -> StrValue("github")
            )
          ),
          attest2SeqNum
        )
        attestCompletionProof <- registry.generateProofs(attestCompletionUpdate, Set(Alice))
        state4                <- combiner.insert(state3, Signed(attestCompletionUpdate, attestCompletionProof))

        attest3SeqNum = state4.calculated.stateMachines(agentfiberId).sequenceNumber
        attestVouchUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "submit_attestation",
          MapValue(
            Map(
              "timestamp"       -> IntValue(600),
              "attestationType" -> StrValue("VOUCH"),
              "platformId"      -> StrValue("github")
            )
          ),
          attest3SeqNum
        )
        attestVouchProof <- registry.generateProofs(attestVouchUpdate, Set(Alice))
        state5           <- combiner.insert(state4, Signed(attestVouchUpdate, attestVouchProof))

        // ── Verify reputation grew correctly ──
        agentAfterAttestations = state5.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        attestationCount = agentAfterAttestations.flatMap(extractIntField(_, "attestationCount"))
        completionCount = agentAfterAttestations.flatMap(extractIntField(_, "completionCount"))
        behavioralCount = agentAfterAttestations.flatMap(extractIntField(_, "behavioralCount"))
        vouchCount = agentAfterAttestations.flatMap(extractIntField(_, "vouchCount"))
        reputationScore = agentAfterAttestations.flatMap(extractIntField(_, "reputationScore"))

      } yield expect.all(
        agentAfterAttestations.map(_.currentState).contains(StateId("active")),
        attestationCount.contains(BigInt(4)), // 1 first + 3 more
        completionCount.contains(BigInt(2)), // first_attestation(COMPLETION) + 1 more
        behavioralCount.contains(BigInt(1)),
        vouchCount.contains(BigInt(1)),
        // Reputation: 10 (first) + 3 (behavioral) + 5 (completion) + 2 (vouch) = 20
        reputationScore.contains(BigInt(20))
      )
    }
  }

  // ── Test 2: Challenge lifecycle — challenge filed, upheld, suspension, probation, recovery ──
  test("agent identity: challenge, suspension, probation, and recovery") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie, Dave))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        agentfiberId    <- UUIDGen.randomUUID[IO]
        platformfiberId <- UUIDGen.randomUUID[IO]

        agentIdentityJson = agentIdentityDefinitionJson(platformfiberId)
        platformRegistryJson = platformRegistryDefinitionJson

        agentDef    <- decodeDefinition(agentIdentityJson, "agent identity")
        platformDef <- decodeDefinition(platformRegistryJson, "platform registry")

        agentData = MapValue(
          Map(
            "agentName"        -> StrValue("TestAgent"),
            "operatorId"       -> StrValue(registry.addresses(Alice).toString),
            "stakeAmount"      -> IntValue(1000),
            "capabilities"     -> ArrayValue(List(StrValue("testing"))),
            "registeredAt"     -> IntValue(100),
            "status"           -> StrValue("registered"),
            "isGenesis"        -> BoolValue(false),
            "suspensionPeriod" -> IntValue(1000)
          )
        )
        agentHash <- (agentData: JsonLogicValue).computeDigest

        platformData = MapValue(
          Map(
            "platformName" -> StrValue("TestPlatform"),
            "platformType" -> StrValue("social"),
            "operatorId"   -> StrValue(registry.addresses(Bob).toString),
            "status"       -> StrValue("pending")
          )
        )
        platformHash <- (platformData: JsonLogicValue).computeDigest

        agentFiber = makeStateMachineFiber(
          agentfiberId,
          ordinal,
          agentDef,
          "registered",
          agentData,
          agentHash,
          Set(Alice).map(registry.addresses)
        )
        platformFiber = makeStateMachineFiber(
          platformfiberId,
          ordinal,
          platformDef,
          "pending",
          platformData,
          platformHash,
          Set(Bob).map(registry.addresses)
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(agentfiberId -> agentFiber, platformfiberId -> platformFiber)
        )

        // Setup: verify platform + activate agent
        state1 <- verifyPlatform(combiner, registry, inState, platformfiberId, Bob)
        state2 <- activateAgent(combiner, registry, state1, agentfiberId, Alice)

        // Build some reputation first (3 successful completions)
        state3 <- submitAttestation(combiner, registry, state2, agentfiberId, Alice, "COMPLETION", 400)
        state4 <- submitAttestation(combiner, registry, state3, agentfiberId, Alice, "COMPLETION", 500)
        state5 <- submitAttestation(combiner, registry, state4, agentfiberId, Alice, "BEHAVIORAL", 600)

        // Reputation should be: 10 + 5 + 5 + 3 = 23
        agentBeforeChallenge = state5.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        repBeforeChallenge = agentBeforeChallenge.flatMap(extractIntField(_, "reputationScore"))
        _ = expect(repBeforeChallenge.contains(BigInt(23)))

        // ── STEP: File a challenge ──
        challengeSeqNum = state5.calculated.stateMachines(agentfiberId).sequenceNumber
        challengeUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "file_challenge",
          MapValue(
            Map(
              "timestamp"       -> IntValue(700),
              "challengerId"    -> StrValue(registry.addresses(Charlie).toString),
              "challengerStake" -> IntValue(200),
              "reason"          -> StrValue("Spam behavior on TestPlatform"),
              "evidenceHash"    -> StrValue("evidence_hash_xyz")
            )
          ),
          challengeSeqNum
        )
        challengeProof <- registry.generateProofs(challengeUpdate, Set(Charlie))
        state6         <- combiner.insert(state5, Signed(challengeUpdate, challengeProof))

        agentAfterChallenge = state6.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect(agentAfterChallenge.map(_.currentState).contains(StateId("challenged")))

        // ── STEP: Uphold the challenge ──
        upholdSeqNum = state6.calculated.stateMachines(agentfiberId).sequenceNumber
        upholdUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "uphold_challenge",
          MapValue(
            Map(
              "timestamp"   -> IntValue(800),
              "upholdVotes" -> IntValue(5)
            )
          ),
          upholdSeqNum
        )
        upholdProof <- registry.generateProofs(upholdUpdate, Set(Dave))
        state7      <- combiner.insert(state6, Signed(upholdUpdate, upholdProof))

        agentAfterSuspension = state7.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect.all(
          agentAfterSuspension.map(_.currentState).contains(StateId("suspended")),
          // Stake should be slashed by half: 1000 / 2 = 500
          agentAfterSuspension.flatMap(extractIntField(_, "stakeAmount")).contains(BigInt(500)),
          // Reputation: 23 - 25 = 0 (clamped to 0 by max)
          agentAfterSuspension.flatMap(extractIntField(_, "reputationScore")).contains(BigInt(0))
        )

        // ── STEP: Begin probation (after suspension period) ──
        probationSeqNum = state7.calculated.stateMachines(agentfiberId).sequenceNumber
        probationUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "begin_probation",
          MapValue(
            Map(
              "timestamp"      -> IntValue(2000), // 2000 - 800 = 1200 > suspensionPeriod(1000)
              "reinstateStake" -> IntValue(500)
            )
          ),
          probationSeqNum
        )
        probationProof <- registry.generateProofs(probationUpdate, Set(Alice))
        state8         <- combiner.insert(state7, Signed(probationUpdate, probationProof))

        agentAfterProbation = state8.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect.all(
          agentAfterProbation.map(_.currentState).contains(StateId("probation")),
          agentAfterProbation.flatMap(extractIntField(_, "stakeAmount")).contains(BigInt(1000)), // 500 + 500
          agentAfterProbation.flatMap(extractIntField(_, "probationAttestationsRequired")).contains(BigInt(10))
        )

        // ── STEP: Complete probation attestations ──
        // Submit 10 probation attestations
        stateAfterProbationAttestations <- (1 to 10).toList.foldLeftM(state8) { case (currentState, i) =>
          val seqNum = currentState.calculated.stateMachines(agentfiberId).sequenceNumber
          val update = Updates.TransitionStateMachine(
            agentfiberId,
            "probation_attestation",
            MapValue(
              Map(
                "timestamp"       -> IntValue(2100 + i * 100),
                "attestationType" -> StrValue("BEHAVIORAL"),
                "platformId"      -> StrValue("test_platform")
              )
            ),
            seqNum
          )
          for {
            proof     <- registry.generateProofs(update, Set(Alice))
            nextState <- combiner.insert(currentState, Signed(update, proof))
          } yield nextState
        }

        agentAfterProbationAttestations = stateAfterProbationAttestations.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
        _ = expect(
          agentAfterProbationAttestations
            .flatMap(extractIntField(_, "probationAttestationsRequired"))
            .contains(BigInt(0))
        )

        // ── STEP: Complete probation → back to active ──
        completeProbationSeqNum = stateAfterProbationAttestations.calculated.stateMachines(agentfiberId).sequenceNumber
        completeProbationUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "complete_probation",
          MapValue(Map("timestamp" -> IntValue(4000))),
          completeProbationSeqNum
        )
        completeProbationProof <- registry.generateProofs(completeProbationUpdate, Set(Alice))
        finalState <- combiner.insert(
          stateAfterProbationAttestations,
          Signed(completeProbationUpdate, completeProbationProof)
        )

        finalAgent = finalState.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalReputation = finalAgent.flatMap(extractIntField(_, "reputationScore"))
        finalStake = finalAgent.flatMap(extractIntField(_, "stakeAmount"))

      } yield expect.all(
        // Agent is back to active
        finalAgent.map(_.currentState).contains(StateId("active")),
        finalAgent.flatMap(extractStrField(_, "status")).contains("active"),
        // Reputation: 0 (after suspension) + 10 (probation attestations × 1 each) + 10 (completion bonus) = 20
        finalReputation.contains(BigInt(20)),
        // Stake restored to 1000
        finalStake.contains(BigInt(1000))
      )
    }
  }

  // ── Test 3: Challenge dismissed — agent vindicated ──
  test("agent identity: challenge dismissed, reputation bonus") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        agentfiberId    <- UUIDGen.randomUUID[IO]
        platformfiberId <- UUIDGen.randomUUID[IO]

        agentDef    <- decodeDefinition(agentIdentityDefinitionJson(platformfiberId), "agent identity")
        platformDef <- decodeDefinition(platformRegistryDefinitionJson, "platform registry")

        agentData = MapValue(
          Map(
            "agentName"        -> StrValue("GoodAgent"),
            "operatorId"       -> StrValue(registry.addresses(Alice).toString),
            "stakeAmount"      -> IntValue(1000),
            "capabilities"     -> ArrayValue(List(StrValue("helpful"))),
            "registeredAt"     -> IntValue(100),
            "status"           -> StrValue("registered"),
            "isGenesis"        -> BoolValue(true),
            "suspensionPeriod" -> IntValue(1000)
          )
        )
        agentHash <- (agentData: JsonLogicValue).computeDigest

        platformData = MapValue(
          Map(
            "platformName" -> StrValue("Moltbook"),
            "platformType" -> StrValue("social"),
            "operatorId"   -> StrValue(registry.addresses(Bob).toString),
            "status"       -> StrValue("pending")
          )
        )
        platformHash <- (platformData: JsonLogicValue).computeDigest

        agentFiber = makeStateMachineFiber(
          agentfiberId,
          ordinal,
          agentDef,
          "registered",
          agentData,
          agentHash,
          Set(Alice).map(registry.addresses)
        )
        platformFiber = makeStateMachineFiber(
          platformfiberId,
          ordinal,
          platformDef,
          "pending",
          platformData,
          platformHash,
          Set(Bob).map(registry.addresses)
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(agentfiberId -> agentFiber, platformfiberId -> platformFiber)
        )

        // Setup + build reputation
        state1 <- verifyPlatform(combiner, registry, inState, platformfiberId, Bob)
        state2 <- activateAgent(combiner, registry, state1, agentfiberId, Alice)
        state3 <- submitAttestation(combiner, registry, state2, agentfiberId, Alice, "COMPLETION", 400)

        // Reputation: 10 + 5 = 15
        reputationBefore = state3.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
          .flatMap(extractIntField(_, "reputationScore"))
        _ = expect(reputationBefore.contains(BigInt(15)))

        // File a bogus challenge
        challengeSeqNum = state3.calculated.stateMachines(agentfiberId).sequenceNumber
        challengeUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "file_challenge",
          MapValue(
            Map(
              "timestamp"       -> IntValue(500),
              "challengerId"    -> StrValue(registry.addresses(Charlie).toString),
              "challengerStake" -> IntValue(100),
              "reason"          -> StrValue("False accusation"),
              "evidenceHash"    -> StrValue("weak_evidence")
            )
          ),
          challengeSeqNum
        )
        challengeProof <- registry.generateProofs(challengeUpdate, Set(Charlie))
        state4         <- combiner.insert(state3, Signed(challengeUpdate, challengeProof))

        _ = expect(
          state4.calculated.stateMachines
            .get(agentfiberId)
            .collect { case r: Records.StateMachineFiberRecord => r }
            .map(_.currentState)
            .contains(StateId("challenged"))
        )

        // Dismiss the challenge (community votes to dismiss)
        dismissSeqNum = state4.calculated.stateMachines(agentfiberId).sequenceNumber
        dismissUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "dismiss_challenge",
          MapValue(
            Map(
              "timestamp"      -> IntValue(600),
              "dismissalVotes" -> IntValue(5)
            )
          ),
          dismissSeqNum
        )
        dismissProof <- registry.generateProofs(dismissUpdate, Set(Alice))
        finalState   <- combiner.insert(state4, Signed(dismissUpdate, dismissProof))

        finalAgent = finalState.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalReputation = finalAgent.flatMap(extractIntField(_, "reputationScore"))
        challengeResolution = finalAgent.flatMap(extractStrField(_, "challengeResolution"))

      } yield expect.all(
        // Agent back to active with reputation bonus
        finalAgent.map(_.currentState).contains(StateId("active")),
        // Reputation: 15 + 5 (vindication bonus) = 20
        finalReputation.contains(BigInt(20)),
        challengeResolution.contains("dismissed")
      )
    }
  }

  // ── Test 4: Violation handling — reputation damage without challenge ──
  test("agent identity: violation attestation reduces reputation") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        agentfiberId    <- UUIDGen.randomUUID[IO]
        platformfiberId <- UUIDGen.randomUUID[IO]

        agentDef    <- decodeDefinition(agentIdentityDefinitionJson(platformfiberId), "agent identity")
        platformDef <- decodeDefinition(platformRegistryDefinitionJson, "platform registry")

        agentData = MapValue(
          Map(
            "agentName"        -> StrValue("RiskyAgent"),
            "operatorId"       -> StrValue(registry.addresses(Alice).toString),
            "stakeAmount"      -> IntValue(1000),
            "capabilities"     -> ArrayValue(List(StrValue("social"))),
            "registeredAt"     -> IntValue(100),
            "status"           -> StrValue("registered"),
            "isGenesis"        -> BoolValue(false),
            "suspensionPeriod" -> IntValue(1000)
          )
        )
        agentHash <- (agentData: JsonLogicValue).computeDigest

        platformData = MapValue(
          Map(
            "platformName" -> StrValue("Discord"),
            "platformType" -> StrValue("chat"),
            "operatorId"   -> StrValue(registry.addresses(Bob).toString),
            "status"       -> StrValue("pending")
          )
        )
        platformHash <- (platformData: JsonLogicValue).computeDigest

        agentFiber = makeStateMachineFiber(
          agentfiberId,
          ordinal,
          agentDef,
          "registered",
          agentData,
          agentHash,
          Set(Alice).map(registry.addresses)
        )
        platformFiber = makeStateMachineFiber(
          platformfiberId,
          ordinal,
          platformDef,
          "pending",
          platformData,
          platformHash,
          Set(Bob).map(registry.addresses)
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(agentfiberId -> agentFiber, platformfiberId -> platformFiber)
        )

        // Setup + build some reputation
        state1 <- verifyPlatform(combiner, registry, inState, platformfiberId, Bob)
        state2 <- activateAgent(combiner, registry, state1, agentfiberId, Alice)
        state3 <- submitAttestation(combiner, registry, state2, agentfiberId, Alice, "COMPLETION", 400)
        state4 <- submitAttestation(combiner, registry, state3, agentfiberId, Alice, "COMPLETION", 500)

        // Reputation: 10 + 5 + 5 = 20
        repBefore = state4.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
          .flatMap(extractIntField(_, "reputationScore"))
        _ = expect(repBefore.contains(BigInt(20)))

        // Submit violation
        violationSeqNum = state4.calculated.stateMachines(agentfiberId).sequenceNumber
        violationUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "submit_violation",
          MapValue(
            Map(
              "timestamp"  -> IntValue(600),
              "platformId" -> StrValue("discord"),
              "reason"     -> StrValue("Sent unsolicited spam messages")
            )
          ),
          violationSeqNum
        )
        violationProof <- registry.generateProofs(violationUpdate, Set(Alice))
        state5         <- combiner.insert(state4, Signed(violationUpdate, violationProof))

        agentAfterViolation = state5.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        repAfterViolation = agentAfterViolation.flatMap(extractIntField(_, "reputationScore"))
        violationCount = agentAfterViolation.flatMap(extractIntField(_, "violationCount"))
        lastViolationReason = agentAfterViolation.flatMap(extractStrField(_, "lastViolationReason"))

        // Submit another violation to test floor clamping at 0
        violation2SeqNum = state5.calculated.stateMachines(agentfiberId).sequenceNumber
        violation2Update = Updates.TransitionStateMachine(
          agentfiberId,
          "submit_violation",
          MapValue(
            Map(
              "timestamp"  -> IntValue(700),
              "platformId" -> StrValue("discord"),
              "reason"     -> StrValue("Repeated spam")
            )
          ),
          violation2SeqNum
        )
        violation2Proof <- registry.generateProofs(violation2Update, Set(Alice))
        finalState      <- combiner.insert(state5, Signed(violation2Update, violation2Proof))

        finalAgent = finalState.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalRep = finalAgent.flatMap(extractIntField(_, "reputationScore"))
        finalViolations = finalAgent.flatMap(extractIntField(_, "violationCount"))

      } yield expect.all(
        // Still active (violations don't auto-suspend)
        agentAfterViolation.map(_.currentState).contains(StateId("active")),
        // Reputation: 20 - 10 = 10
        repAfterViolation.contains(BigInt(10)),
        violationCount.contains(BigInt(1)),
        lastViolationReason.contains("Sent unsolicited spam messages"),
        // Second violation: 10 - 10 = 0 (clamped)
        finalRep.contains(BigInt(0)),
        finalViolations.contains(BigInt(2))
      )
    }
  }

  // ── Test 5: Voluntary withdrawal ──
  test("agent identity: voluntary withdrawal preserves history") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        agentfiberId    <- UUIDGen.randomUUID[IO]
        platformfiberId <- UUIDGen.randomUUID[IO]

        agentDef    <- decodeDefinition(agentIdentityDefinitionJson(platformfiberId), "agent identity")
        platformDef <- decodeDefinition(platformRegistryDefinitionJson, "platform registry")

        agentData = MapValue(
          Map(
            "agentName"        -> StrValue("RetiringAgent"),
            "operatorId"       -> StrValue(registry.addresses(Alice).toString),
            "stakeAmount"      -> IntValue(1000),
            "capabilities"     -> ArrayValue(List(StrValue("data_analysis"))),
            "registeredAt"     -> IntValue(100),
            "status"           -> StrValue("registered"),
            "isGenesis"        -> BoolValue(true),
            "suspensionPeriod" -> IntValue(1000)
          )
        )
        agentHash <- (agentData: JsonLogicValue).computeDigest

        platformData = MapValue(
          Map(
            "platformName" -> StrValue("OpenClaw"),
            "platformType" -> StrValue("agent_framework"),
            "operatorId"   -> StrValue(registry.addresses(Bob).toString),
            "status"       -> StrValue("pending")
          )
        )
        platformHash <- (platformData: JsonLogicValue).computeDigest

        agentFiber = makeStateMachineFiber(
          agentfiberId,
          ordinal,
          agentDef,
          "registered",
          agentData,
          agentHash,
          Set(Alice).map(registry.addresses)
        )
        platformFiber = makeStateMachineFiber(
          platformfiberId,
          ordinal,
          platformDef,
          "pending",
          platformData,
          platformHash,
          Set(Bob).map(registry.addresses)
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(agentfiberId -> agentFiber, platformfiberId -> platformFiber)
        )

        state1 <- verifyPlatform(combiner, registry, inState, platformfiberId, Bob)
        state2 <- activateAgent(combiner, registry, state1, agentfiberId, Alice)
        state3 <- submitAttestation(combiner, registry, state2, agentfiberId, Alice, "COMPLETION", 400)
        state4 <- submitAttestation(combiner, registry, state3, agentfiberId, Alice, "COMPLETION", 500)

        // Reputation: 10 + 5 + 5 = 20

        // Withdraw
        withdrawSeqNum = state4.calculated.stateMachines(agentfiberId).sequenceNumber
        withdrawUpdate = Updates.TransitionStateMachine(
          agentfiberId,
          "withdraw",
          MapValue(Map("timestamp" -> IntValue(600))),
          withdrawSeqNum
        )
        withdrawProof <- registry.generateProofs(withdrawUpdate, Set(Alice))
        finalState    <- combiner.insert(state4, Signed(withdrawUpdate, withdrawProof))

        finalAgent = finalState.calculated.stateMachines
          .get(agentfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalReputation = finalAgent.flatMap(extractIntField(_, "finalReputationScore"))
        finalAttestations = finalAgent.flatMap(extractIntField(_, "finalAttestationCount"))
        stakeReturned = finalAgent.flatMap(extractIntField(_, "stakeReturned"))

      } yield expect.all(
        // Final state is withdrawn (terminal)
        finalAgent.map(_.currentState).contains(StateId("withdrawn")),
        finalAgent.flatMap(extractStrField(_, "status")).contains("withdrawn"),
        // History preserved in final state
        finalReputation.contains(BigInt(20)),
        finalAttestations.contains(BigInt(3)), // first_attestation + 2 more
        stakeReturned.contains(BigInt(1000))
      )
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // Helper Methods
  // ═══════════════════════════════════════════════════════════════════

  private def extractIntField(record: Records.StateMachineFiberRecord, field: String): Option[BigInt] =
    record.stateData match {
      case MapValue(m) => m.get(field).collect { case IntValue(v) => v }
      case _           => None
    }

  private def extractStrField(record: Records.StateMachineFiberRecord, field: String): Option[String] =
    record.stateData match {
      case MapValue(m) => m.get(field).collect { case StrValue(v) => v }
      case _           => None
    }

  private def decodeDefinition(json: String, name: String): IO[StateMachineDefinition] =
    IO.fromEither(
      decode[StateMachineDefinition](json).left.map(err => new RuntimeException(s"Failed to decode $name JSON: $err"))
    )

  private def makeStateMachineFiber(
    fiberId:      java.util.UUID,
    ordinal:      io.constellationnetwork.schema.SnapshotOrdinal,
    definition:   StateMachineDefinition,
    initialState: String,
    stateData:    JsonLogicValue,
    stateHash:    io.constellationnetwork.security.hash.Hash,
    owners:       Set[io.constellationnetwork.schema.address.Address]
  ): Records.StateMachineFiberRecord =
    Records.StateMachineFiberRecord(
      fiberId = fiberId,
      creationOrdinal = ordinal,
      previousUpdateOrdinal = ordinal,
      latestUpdateOrdinal = ordinal,
      definition = definition,
      currentState = StateId(initialState),
      stateData = stateData,
      stateDataHash = stateHash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = owners,
      status = FiberStatus.Active
    )

  private def verifyPlatform(
    combiner:        CombinerService[IO, OttochainMessage, OnChain, CalculatedState],
    registry:        ParticipantRegistry[IO],
    state:           DataState[OnChain, CalculatedState],
    platformfiberId: java.util.UUID,
    signer:          Participant
  )(implicit s: SecurityProvider[IO], l0ctx: L0NodeContext[IO]): IO[DataState[OnChain, CalculatedState]] = {
    val update = Updates.TransitionStateMachine(
      platformfiberId,
      "verify_platform",
      MapValue(
        Map(
          "timestamp"         -> IntValue(200),
          "verificationScore" -> IntValue(95),
          "platformStake"     -> IntValue(500)
        )
      ),
      FiberOrdinal.MinValue
    )
    for {
      proof    <- registry.generateProofs(update, Set(signer))
      newState <- combiner.insert(state, Signed(update, proof))
    } yield newState
  }

  private def activateAgent(
    combiner:     CombinerService[IO, OttochainMessage, OnChain, CalculatedState],
    registry:     ParticipantRegistry[IO],
    state:        DataState[OnChain, CalculatedState],
    agentfiberId: java.util.UUID,
    signer:       Participant
  )(implicit s: SecurityProvider[IO], l0ctx: L0NodeContext[IO]): IO[DataState[OnChain, CalculatedState]] = {
    val update = Updates.TransitionStateMachine(
      agentfiberId,
      "first_attestation",
      MapValue(
        Map(
          "timestamp"       -> IntValue(300),
          "attestationType" -> StrValue("COMPLETION"),
          "platformId"      -> StrValue("test_platform"),
          "evidenceHash"    -> StrValue("activation_evidence")
        )
      ),
      FiberOrdinal.MinValue
    )
    for {
      proof    <- registry.generateProofs(update, Set(signer))
      newState <- combiner.insert(state, Signed(update, proof))
    } yield newState
  }

  private def submitAttestation(
    combiner:        CombinerService[IO, OttochainMessage, OnChain, CalculatedState],
    registry:        ParticipantRegistry[IO],
    state:           DataState[OnChain, CalculatedState],
    agentfiberId:    java.util.UUID,
    signer:          Participant,
    attestationType: String,
    timestamp:       Int
  )(implicit s: SecurityProvider[IO], l0ctx: L0NodeContext[IO]): IO[DataState[OnChain, CalculatedState]] = {
    val seqNum = state.calculated.stateMachines(agentfiberId).sequenceNumber
    val update = Updates.TransitionStateMachine(
      agentfiberId,
      "submit_attestation",
      MapValue(
        Map(
          "timestamp"       -> IntValue(timestamp),
          "attestationType" -> StrValue(attestationType),
          "platformId"      -> StrValue("test_platform")
        )
      ),
      seqNum
    )
    for {
      proof    <- registry.generateProofs(update, Set(signer))
      newState <- combiner.insert(state, Signed(update, proof))
    } yield newState
  }

  // ═══════════════════════════════════════════════════════════════════
  // Shared JSON Definitions (reused across tests)
  // ═══════════════════════════════════════════════════════════════════

  private def agentIdentityDefinitionJson(platformfiberId: java.util.UUID): String =
    s"""{
      "states": {
        "registered": { "id": { "value": "registered" }, "isFinal": false },
        "active": { "id": { "value": "active" }, "isFinal": false },
        "challenged": { "id": { "value": "challenged" }, "isFinal": false },
        "suspended": { "id": { "value": "suspended" }, "isFinal": false },
        "probation": { "id": { "value": "probation" }, "isFinal": false },
        "withdrawn": { "id": { "value": "withdrawn" }, "isFinal": true }
      },
      "initialState": { "value": "registered" },
      "transitions": [
        {
          "from": { "value": "registered" },
          "to": { "value": "active" },
          "eventName": "first_attestation",
          "guard": {
            "and": [
              { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
              { ">": [ { "var": "state.stakeAmount" }, 0 ] }
            ]
          },
          "effect": [
            ["status", "active"],
            ["activatedAt", { "var": "event.timestamp" }],
            ["attestationCount", 1],
            ["completionCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 1, 0 ] }],
            ["violationCount", 0],
            ["behavioralCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 1, 0 ] }],
            ["vouchCount", { "if": [ { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 1, 0 ] }],
            ["lastAttestationAt", { "var": "event.timestamp" }],
            ["lastAttestationPlatform", { "var": "event.platformId" }],
            ["reputationScore", 10]
          ],
          "dependencies": ["${platformfiberId}"]
        },
        {
          "from": { "value": "active" },
          "to": { "value": "active" },
          "eventName": "submit_attestation",
          "guard": {
            "and": [
              { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
              { "in": [ { "var": "event.attestationType" }, ["BEHAVIORAL", "COMPLETION", "VOUCH"] ] }
            ]
          },
          "effect": [
            ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
            ["completionCount", { "+": [ { "var": "state.completionCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 1, 0 ] } ] }],
            ["behavioralCount", { "+": [ { "var": "state.behavioralCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 1, 0 ] } ] }],
            ["vouchCount", { "+": [ { "var": "state.vouchCount" }, { "if": [ { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 1, 0 ] } ] }],
            ["lastAttestationAt", { "var": "event.timestamp" }],
            ["lastAttestationPlatform", { "var": "event.platformId" }],
            ["reputationScore", { "+": [
              { "var": "state.reputationScore" },
              { "if": [
                { "===": [ { "var": "event.attestationType" }, "COMPLETION" ] }, 5,
                { "===": [ { "var": "event.attestationType" }, "BEHAVIORAL" ] }, 3,
                { "===": [ { "var": "event.attestationType" }, "VOUCH" ] }, 2,
                1
              ]}
            ]}]
          ],
          "dependencies": ["${platformfiberId}"]
        },
        {
          "from": { "value": "active" },
          "to": { "value": "active" },
          "eventName": "submit_violation",
          "guard": {
            "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ]
          },
          "effect": [
            ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
            ["violationCount", { "+": [ { "var": "state.violationCount" }, 1 ] }],
            ["lastViolationAt", { "var": "event.timestamp" }],
            ["lastViolationReason", { "var": "event.reason" }],
            ["lastAttestationAt", { "var": "event.timestamp" }],
            ["lastAttestationPlatform", { "var": "event.platformId" }],
            ["reputationScore", { "max": [ 0, { "-": [ { "var": "state.reputationScore" }, 10 ] } ] }]
          ],
          "dependencies": ["${platformfiberId}"]
        },
        {
          "from": { "value": "active" },
          "to": { "value": "challenged" },
          "eventName": "file_challenge",
          "guard": {
            "and": [
              { ">": [ { "var": "event.challengerStake" }, 0 ] },
              { "!==": [ { "var": "event.challengerId" }, { "var": "state.operatorId" } ] }
            ]
          },
          "effect": [
            ["status", "challenged"],
            ["challengedAt", { "var": "event.timestamp" }],
            ["challengerId", { "var": "event.challengerId" }],
            ["challengerStake", { "var": "event.challengerStake" }],
            ["challengeReason", { "var": "event.reason" }],
            ["challengeEvidenceHash", { "var": "event.evidenceHash" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "challenged" },
          "to": { "value": "active" },
          "eventName": "dismiss_challenge",
          "guard": {
            ">=": [ { "var": "event.dismissalVotes" }, 3 ]
          },
          "effect": [
            ["status", "active"],
            ["challengeResolution", "dismissed"],
            ["challengeResolvedAt", { "var": "event.timestamp" }],
            ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 5 ] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "challenged" },
          "to": { "value": "suspended" },
          "eventName": "uphold_challenge",
          "guard": {
            ">=": [ { "var": "event.upholdVotes" }, 3 ]
          },
          "effect": [
            ["status", "suspended"],
            ["suspendedAt", { "var": "event.timestamp" }],
            ["challengeResolution", "upheld"],
            ["challengeResolvedAt", { "var": "event.timestamp" }],
            ["stakeSlashed", { "/": [ { "var": "state.stakeAmount" }, 2 ] }],
            ["stakeAmount", { "/": [ { "var": "state.stakeAmount" }, 2 ] }],
            ["reputationScore", { "max": [ 0, { "-": [ { "var": "state.reputationScore" }, 25 ] } ] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "suspended" },
          "to": { "value": "probation" },
          "eventName": "begin_probation",
          "guard": {
            "and": [
              { ">": [ { "var": "event.reinstateStake" }, 0 ] },
              { ">=": [
                { "-": [ { "var": "event.timestamp" }, { "var": "state.suspendedAt" } ] },
                { "var": "state.suspensionPeriod" }
              ]}
            ]
          },
          "effect": [
            ["status", "probation"],
            ["probationStartedAt", { "var": "event.timestamp" }],
            ["stakeAmount", { "+": [ { "var": "state.stakeAmount" }, { "var": "event.reinstateStake" } ] }],
            ["probationAttestationsRequired", 10]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "probation" },
          "to": { "value": "probation" },
          "eventName": "probation_attestation",
          "guard": {
            "and": [
              { "===": [ { "var": "machines.${platformfiberId}.state.status" }, "verified" ] },
              { ">": [ { "var": "state.probationAttestationsRequired" }, 0 ] }
            ]
          },
          "effect": [
            ["probationAttestationsRequired", { "-": [ { "var": "state.probationAttestationsRequired" }, 1 ] }],
            ["attestationCount", { "+": [ { "var": "state.attestationCount" }, 1 ] }],
            ["lastAttestationAt", { "var": "event.timestamp" }],
            ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 1 ] }]
          ],
          "dependencies": ["${platformfiberId}"]
        },
        {
          "from": { "value": "probation" },
          "to": { "value": "active" },
          "eventName": "complete_probation",
          "guard": {
            "<=": [ { "var": "state.probationAttestationsRequired" }, 0 ]
          },
          "effect": [
            ["status", "active"],
            ["probationCompletedAt", { "var": "event.timestamp" }],
            ["reputationScore", { "+": [ { "var": "state.reputationScore" }, 10 ] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "active" },
          "to": { "value": "withdrawn" },
          "eventName": "withdraw",
          "guard": true,
          "effect": [
            ["status", "withdrawn"],
            ["withdrawnAt", { "var": "event.timestamp" }],
            ["stakeReturned", { "var": "state.stakeAmount" }],
            ["finalReputationScore", { "var": "state.reputationScore" }],
            ["finalAttestationCount", { "var": "state.attestationCount" }]
          ],
          "dependencies": []
        }
      ]
    }"""

  private val platformRegistryDefinitionJson: String =
    """{
      "states": {
        "pending": { "id": { "value": "pending" }, "isFinal": false },
        "verified": { "id": { "value": "verified" }, "isFinal": false },
        "suspended": { "id": { "value": "suspended" }, "isFinal": false },
        "revoked": { "id": { "value": "revoked" }, "isFinal": true }
      },
      "initialState": { "value": "pending" },
      "transitions": [
        {
          "from": { "value": "pending" },
          "to": { "value": "verified" },
          "eventName": "verify_platform",
          "guard": {
            "and": [
              { ">": [ { "var": "event.verificationScore" }, 0 ] },
              { ">": [ { "var": "event.platformStake" }, 0 ] }
            ]
          },
          "effect": [
            ["status", "verified"],
            ["verifiedAt", { "var": "event.timestamp" }],
            ["platformStake", { "var": "event.platformStake" }],
            ["verificationScore", { "var": "event.verificationScore" }],
            ["attestationsSubmitted", 0]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "verified" },
          "to": { "value": "verified" },
          "eventName": "record_attestation_submitted",
          "guard": true,
          "effect": [
            ["attestationsSubmitted", { "+": [ { "var": "state.attestationsSubmitted" }, 1 ] }],
            ["lastAttestationAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "verified" },
          "to": { "value": "suspended" },
          "eventName": "suspend_platform",
          "guard": true,
          "effect": [
            ["status", "suspended"],
            ["suspendedAt", { "var": "event.timestamp" }],
            ["suspensionReason", { "var": "event.reason" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "suspended" },
          "to": { "value": "verified" },
          "eventName": "reinstate_platform",
          "guard": true,
          "effect": [
            ["status", "verified"],
            ["reinstatedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "suspended" },
          "to": { "value": "revoked" },
          "eventName": "revoke_platform",
          "guard": true,
          "effect": [
            ["status", "revoked"],
            ["revokedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        }
      ]
    }"""
}
