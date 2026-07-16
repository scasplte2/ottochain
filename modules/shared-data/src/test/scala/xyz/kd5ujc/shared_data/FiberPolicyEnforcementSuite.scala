package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Engine-level enforcement of the opt-in FiberPolicy dials. Each dial: the violating transition ABORTS with a
 * `PolicyViolation` (or the relevant FailureReason), and the permitted path COMMITS. Drives `FiberEngine`
 * directly with a hand-built `CalculatedState` so a typed `policy` can be attached to the definition.
 */
object FiberPolicyEnforcementSuite extends SimpleIOSuite {

  private def fiber(
    id:     UUID,
    fdef:   StateMachineDefinition,
    data:   MapValue,
    hash:   io.constellationnetwork.security.hash.Hash,
    ord:    io.constellationnetwork.schema.SnapshotOrdinal,
    state:  StateId = StateId("init"),
    owners: Set[io.constellationnetwork.schema.address.Address] = Set.empty,
    parent: Option[UUID] = None
  ): Records.StateMachineFiberRecord =
    Records.StateMachineFiberRecord(
      fiberId = id,
      creationOrdinal = ord,
      previousUpdateOrdinal = ord,
      latestUpdateOrdinal = ord,
      definition = fdef,
      currentState = state,
      stateData = data,
      stateDataHash = hash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = owners,
      status = FiberStatus.Active,
      parentFiberId = parent
    )

  private def parseDef(json: String): IO[StateMachineDefinition] =
    IO.fromEither(decode[StateMachineDefinition](json))

  // ── allowedEffects ───────────────────────────────────────────────────────────────────────────────

  test("allowedEffects: a transition emitting a forbidden family ABORTS; a permitted family COMMITS") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid <- UUIDGen.randomUUID[IO]
        // Effect emits an _emit event; policy allows only TRIGGER.
        json = """{
          "states": { "init": { "id": "init", "isFinal": false }, "done": { "id": "done", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "done", "eventName": "go", "guard": true,
              "effect": { "_emit": [ { "name": "ping", "data": {} } ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        forbid = base.copy(policy = FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Trigger))))
        permit = base.copy(policy = FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit))))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        input = FiberInput.Transition("go", MapValue(Map.empty))

        forbidState = CalculatedState(SortedMap(fid -> fiber(fid, forbid, data, h, fixture.ordinal)), SortedMap.empty)
        permitState = CalculatedState(SortedMap(fid -> fiber(fid, permit, data, h, fixture.ordinal)), SortedMap.empty)

        forbidRes <- FiberEngine.make[IO](forbidState, fixture.ordinal).process(fid, input, List.empty)
        permitRes <- FiberEngine.make[IO](permitState, fixture.ordinal).process(fid, input, List.empty)
      } yield expect(isPolicyViolation(forbidRes, "allowedEffects")) and expect(isCommitted(permitRes))
    }
  }

  // ── sealedStates ─────────────────────────────────────────────────────────────────────────────────

  test("sealedStates: a transition from a sealed state ABORTS before the guard runs") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid <- UUIDGen.randomUUID[IO]
        json = """{
          "states": { "init": { "id": "init", "isFinal": false }, "done": { "id": "done", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "done", "eventName": "go", "guard": true, "effect": { "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        fdef = base.copy(policy = FiberPolicy.constrained(sealedStates = Some(Set(StateId("init")))))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        st = CalculatedState(SortedMap(fid -> fiber(fid, fdef, data, h, fixture.ordinal)), SortedMap.empty)
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(fid, FiberInput.Transition("go", MapValue(Map.empty)), List.empty)
      } yield expect(isPolicyViolation(res, "sealedStates"))
    }
  }

  // ── acceptedCallers (reads the Wave-1 $caller) ───────────────────────────────────────────────────

  test("acceptedCallers: a CASCADE from a non-allowlisted caller ABORTS the whole tx; an allowlisted one COMMITS") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        sourceId <- UUIDGen.randomUUID[IO]
        targetId <- UUIDGen.randomUUID[IO]
        // The SOURCE triggers the TARGET on event "poke". The target's acceptedCallers controls whether the
        // engine-stamped $caller (= sourceId) is permitted.
        sourceJson = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "fired": { "id": "fired", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "fired", "eventName": "start", "guard": true,
              "effect": { "_triggers": [ { "targetMachineId": "$targetId", "eventName": "poke", "payload": {} } ], "x": 1 },
              "dependencies": [] }
          ]
        }"""
        targetJson = """{
          "states": { "init": { "id": "init", "isFinal": false }, "poked": { "id": "poked", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "poked", "eventName": "poke", "guard": true, "effect": { "y": 1 }, "dependencies": [] }
          ]
        }"""
        srcDef  <- parseDef(sourceJson)
        tgtBase <- parseDef(targetJson)
        srcData = MapValue(Map("x" -> IntValue(0)))
        tgtData = MapValue(Map("y" -> IntValue(0)))
        sh <- (srcData: JsonLogicValue).computeDigest
        th <- (tgtData: JsonLogicValue).computeDigest

        denyDef = tgtBase.copy(policy = FiberPolicy.constrained(acceptedCallers = Some(Set(UUID.randomUUID()))))
        allowDef = tgtBase.copy(policy = FiberPolicy.constrained(acceptedCallers = Some(Set(sourceId))))

        denyState = CalculatedState(
          SortedMap(
            sourceId -> fiber(sourceId, srcDef, srcData, sh, fixture.ordinal),
            targetId -> fiber(targetId, denyDef, tgtData, th, fixture.ordinal)
          ),
          SortedMap.empty
        )
        allowState = CalculatedState(
          SortedMap(
            sourceId -> fiber(sourceId, srcDef, srcData, sh, fixture.ordinal),
            targetId -> fiber(targetId, allowDef, tgtData, th, fixture.ordinal)
          ),
          SortedMap.empty
        )

        input = FiberInput.Transition("start", MapValue(Map.empty))
        denyRes  <- FiberEngine.make[IO](denyState, fixture.ordinal).process(sourceId, input, List.empty)
        allowRes <- FiberEngine.make[IO](allowState, fixture.ordinal).process(sourceId, input, List.empty)
      } yield expect(isPolicyViolation(denyRes, "acceptedCallers")) and expect(isCommitted(allowRes))
    }
  }

  // ── selfReproducing ──────────────────────────────────────────────────────────────────────────────

  test("selfReproducing: a _spawn of a NON-identical definition ABORTS (the code-preservation invariant)") {
    // NOTE on the positive direction: a TRUE byte-identical inline self-copy is structurally impossible to
    // express in finite JSON (a definition whose `_spawn` embeds a byte-equal copy of itself would have to
    // contain its own bytes). Self-reproduction therefore guards against DRIFT — any embedded child that is
    // not digest-equal to the running parent aborts. The negative path below is the meaningful engine proof;
    // the digest-equality comparison the validator performs is covered structurally (the codec canonicalizes
    // Map key order: reordered `states` PASS, reordered `transitions` FAIL — see JsonBinaryCodec). The OPT-OUT
    // default (non-self-reproducing parent unaffected) is exercised by every other spawn test in the suite.
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]

        // Parent (self-reproducing) spawns a child whose embedded definition is a simple, DIFFERENT machine —
        // not a copy of the parent — so the digest check fails closed.
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "divide", "guard": true,
              "effect": { "_spawn": [ {
                "childId": "$childId",
                "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
                "initialData": { "k": 1 } } ], "x": 1 },
              "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        parentDef = base.copy(policy = FiberPolicy.constrained(selfReproducing = Some(true)))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        input = FiberInput.Transition("divide", MapValue(Map.empty))
        st = CalculatedState(
          SortedMap(parentId -> fiber(parentId, parentDef, data, h, fixture.ordinal)),
          SortedMap.empty
        )
        res <- FiberEngine.make[IO](st, fixture.ordinal).process(parentId, input, List.empty)
      } yield expect(isPolicyViolation(res, "selfReproducing"))
    }
  }

  test("selfReproducing OPT-OUT: a non-self-reproducing parent spawning ANY definition COMMITS (untouched)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "divide", "guard": true,
              "effect": { "_spawn": [ {
                "childId": "$childId",
                "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
                "initialData": { "k": 1 } } ], "x": 1 },
              "dependencies": [] }
          ]
        }"""
        base <- parseDef(json) // policy = FiberPolicy.Unconstrained ⇒ opt-out
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        st = CalculatedState(SortedMap(parentId -> fiber(parentId, base, data, h, fixture.ordinal)), SortedMap.empty)
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(parentId, FiberInput.Transition("divide", MapValue(Map.empty)), List.empty)
      } yield expect(isCommitted(res))
    }
  }

  // ── maxSpawnFanout ───────────────────────────────────────────────────────────────────────────────

  test("maxSpawnFanout: N+1 spawns in one transition ABORTS with a PolicyViolation") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        parentId <- UUIDGen.randomUUID[IO]
        c1       <- UUIDGen.randomUUID[IO]
        c2       <- UUIDGen.randomUUID[IO]
        childTemplate = (cid: UUID) => s"""{
          "childId": "$cid",
          "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
          "initialData": { "i": 0 } }"""
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "go", "guard": true,
              "effect": { "_spawn": [ ${childTemplate(c1)}, ${childTemplate(c2)} ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        fdef = base.copy(policy = FiberPolicy.constrained(maxSpawnFanout = Some(1)))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        st = CalculatedState(SortedMap(parentId -> fiber(parentId, fdef, data, h, fixture.ordinal)), SortedMap.empty)
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(parentId, FiberInput.Transition("go", MapValue(Map.empty)), List.empty)
      } yield expect(isPolicyViolation(res, "maxSpawnFanout"))
    }
  }

  // ── spawnOwnerPolicy ─────────────────────────────────────────────────────────────────────────────

  test("spawnOwnerPolicy=SubsetOfParent: a child with non-subset owners ABORTS") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]
        // ownersExpr resolves to an address NOT in the parent's owners (parent owners = empty).
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "go", "guard": true,
              "effect": { "_spawn": [ {
                "childId": "$childId",
                "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
                "initialData": { "i": 0 },
                "owners": [ "DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu" ]
              } ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        fdef = base.copy(policy = FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.SubsetOfParent)))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        st = CalculatedState(
          SortedMap(parentId -> fiber(parentId, fdef, data, h, fixture.ordinal, owners = Set.empty)),
          SortedMap.empty
        )
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(parentId, FiberInput.Transition("go", MapValue(Map.empty)), List.empty)
      } yield expect(isPolicyViolation(res, "spawnOwnerPolicy"))
    }
  }

  // ── maxGenerations ───────────────────────────────────────────────────────────────────────────────

  test("maxGenerations: an incomplete ancestor chain FAILS CLOSED (reject)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]
        missingAncestor = UUID.randomUUID() // referenced by parentFiberId but ABSENT from state
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "go", "guard": true,
              "effect": { "_spawn": [ {
                "childId": "$childId",
                "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
                "initialData": { "i": 0 } } ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        fdef = base.copy(policy = FiberPolicy.constrained(maxGenerations = Some(3)))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        // parent references a missing ancestor ⇒ chain unverifiable ⇒ fail-closed reject.
        rec = fiber(parentId, fdef, data, h, fixture.ordinal, parent = Some(missingAncestor))
        st = CalculatedState(SortedMap(parentId -> rec), SortedMap.empty)
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(parentId, FiberInput.Transition("go", MapValue(Map.empty)), List.empty)
      } yield expect(isPolicyViolation(res, "maxGenerations"))
    }
  }

  test("maxGenerations: a parentFiberId CYCLE fails closed (no infinite loop)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        aId <- UUIDGen.randomUUID[IO]
        bId <- UUIDGen.randomUUID[IO]
        cId <- UUIDGen.randomUUID[IO]
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "spawned": { "id": "spawned", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "spawned", "eventName": "go", "guard": true,
              "effect": { "_spawn": [ {
                "childId": "$cId",
                "definition": { "states": { "A": { "id": "A", "isFinal": false } }, "initialState": "A", "transitions": [] },
                "initialData": { "i": 0 } } ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        fdef = base.copy(policy = FiberPolicy.constrained(maxGenerations = Some(1000)))
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        // A.parent = B, B.parent = A  ⇒  a 2-node cycle in parentFiberId.
        aRec = fiber(aId, fdef, data, h, fixture.ordinal, parent = Some(bId))
        bRec = fiber(bId, fdef, data, h, fixture.ordinal, parent = Some(aId))
        st = CalculatedState(SortedMap(aId -> aRec, bId -> bRec), SortedMap.empty)
        res <- FiberEngine
          .make[IO](st, fixture.ordinal)
          .process(aId, FiberInput.Transition("go", MapValue(Map.empty)), List.empty)
      } yield expect(isPolicyViolation(res, "maxGenerations"))
    }
  }

  // ── dependencyPolicy ─────────────────────────────────────────────────────────────────────────────

  test("dependencyPolicy=Allowlist: a non-allowlisted _addDependency ABORTS; an allowlisted one COMMITS") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid   <- UUIDGen.randomUUID[IO]
        depId <- UUIDGen.randomUUID[IO]
        json = s"""{
          "states": { "init": { "id": "init", "isFinal": false }, "done": { "id": "done", "isFinal": false } },
          "initialState": "init",
          "transitions": [
            { "from": "init", "to": "done", "eventName": "go", "guard": true,
              "effect": { "_addDependency": [ { "fiberId": "$depId" } ], "x": 1 }, "dependencies": [] }
          ]
        }"""
        base <- parseDef(json)
        denyDef = base.copy(policy =
          FiberPolicy
            .constrained(dependencyPolicy =
              Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(UUID.randomUUID()))))
            )
        )
        allowDef = base.copy(policy =
          FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(depId)))))
        )
        data = MapValue(Map("x" -> IntValue(0)))
        h <- (data: JsonLogicValue).computeDigest
        input = FiberInput.Transition("go", MapValue(Map.empty))
        denyState = CalculatedState(SortedMap(fid -> fiber(fid, denyDef, data, h, fixture.ordinal)), SortedMap.empty)
        allowState = CalculatedState(SortedMap(fid -> fiber(fid, allowDef, data, h, fixture.ordinal)), SortedMap.empty)
        denyRes  <- FiberEngine.make[IO](denyState, fixture.ordinal).process(fid, input, List.empty)
        allowRes <- FiberEngine.make[IO](allowState, fixture.ordinal).process(fid, input, List.empty)
      } yield expect(isPolicyViolation(denyRes, "dependencyPolicy")) and expect(isCommitted(allowRes))
    }
  }

  // ── tighten-only at migration ────────────────────────────────────────────────────────────────────

  test("tighten-only: a migration that LOOSENS a policy ABORTS with PolicyViolation(\"tighten\")") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid <- UUIDGen.randomUUID[IO]
        json = """{
          "states": { "init": { "id": "init", "isFinal": false } },
          "initialState": "init",
          "transitions": []
        }"""
        base <- parseDef(json)
        // OLD policy: selfReproducing ON (a one-way latch). NEW: cleared ⇒ a loosening ⇒ must abort.
        oldDef = base.copy(policy = FiberPolicy.constrained(selfReproducing = Some(true)))
        newDef = base.copy(policy = FiberPolicy.Unconstrained)
        data = MapValue(Map.empty)
        h <- (data: JsonLogicValue).computeDigest
        // Unbound fiber (schemaBinding = None) so the conformance gate is a no-op; the tighten check fires first.
        rec = fiber(fid, oldDef, data, h, fixture.ordinal, state = StateId("init"))
        st = CalculatedState(SortedMap(fid -> rec), SortedMap.empty)
        binding = xyz.kd5ujc.schema.registry.SchemaBinding(
          xyz.kd5ujc.schema.registry.RegistryName.unsafe("x.package"),
          xyz.kd5ujc.schema.registry.SemVer(2, 0, 0),
          io.constellationnetwork.security.hash.Hash("sh"),
          io.constellationnetwork.security.hash.Hash("lh")
        )
        res <- FiberEngine.make[IO](st, fixture.ordinal).migrate(fid, newDef, binding, None)
      } yield expect(isPolicyViolation(res, "tighten"))
    }
  }

  // ── version & compatibility family at migration ──────────────────────────────────────────────────

  test("upgradePolicy=Immutable: EVERY migration ABORTS, even an identity re-bind with verified signers") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid  <- UUIDGen.randomUUID[IO]
        base <- parseDef("""{"states":{"init":{"id":"init","isFinal":false}},"initialState":"init","transitions":[]}""")
        pol = FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable))
        fdef = base.copy(policy = pol)
        data = MapValue(Map.empty)
        h <- (data: JsonLogicValue).computeDigest
        rec = fiber(fid, fdef, data, h, fixture.ordinal, state = StateId("init"))
        st = CalculatedState(SortedMap(fid -> rec), SortedMap.empty)
        res <- FiberEngine.make[IO](st, fixture.ordinal).migrate(fid, fdef, anyBinding, None, Set(anyAddr))
      } yield expect(isPolicyViolation(res, "upgradePolicy"))
    }
  }

  test("upgradePolicy=Governed: an authorized signer COMMITS with commuteObligation=true; an outsider ABORTS") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid  <- UUIDGen.randomUUID[IO]
        base <- parseDef("""{"states":{"init":{"id":"init","isFinal":false}},"initialState":"init","transitions":[]}""")
        // Governed by `anyAddr`; the NEW policy keeps the SAME Governed tier (tighten-only same-rank rotation OK).
        gov = UpgradePolicy.Governed(MigrationAuthority.Signers(Set(anyAddr)))
        pol = FiberPolicy.constrained(upgradePolicy = Some(gov))
        fdef = base.copy(policy = pol)
        data = MapValue(Map.empty)
        h <- (data: JsonLogicValue).computeDigest
        rec = fiber(fid, fdef, data, h, fixture.ordinal, state = StateId("init"))
        st = CalculatedState(SortedMap(fid -> rec), SortedMap.empty)
        okRes <- FiberEngine.make[IO](st, fixture.ordinal).migrate(fid, fdef, anyBinding, None, Set(anyAddr))
        noRes <- FiberEngine.make[IO](st, fixture.ordinal).migrate(fid, fdef, anyBinding, None, Set(otherAddr))
      } yield expect(commuteObligationOf(okRes).contains(true)) and expect(isPolicyViolation(noRes, "upgradePolicy"))
    }
  }

  test("upgradePolicy=Arbitrary (absent): migration COMMITS with commuteObligation=false") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      for {
        fid  <- UUIDGen.randomUUID[IO]
        base <- parseDef("""{"states":{"init":{"id":"init","isFinal":false}},"initialState":"init","transitions":[]}""")
        data = MapValue(Map.empty)
        h <- (data: JsonLogicValue).computeDigest
        rec = fiber(fid, base, data, h, fixture.ordinal, state = StateId("init"))
        st = CalculatedState(SortedMap(fid -> rec), SortedMap.empty)
        res <- FiberEngine.make[IO](st, fixture.ordinal).migrate(fid, base, anyBinding, None, Set(anyAddr))
      } yield expect(commuteObligationOf(res).contains(false))
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

  private val anyAddr: io.constellationnetwork.schema.address.Address = mkAddr(
    "DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu"
  )

  private val otherAddr: io.constellationnetwork.schema.address.Address = mkAddr(
    "DAG5VpYPJCqdv4K3VnpNrpTABvC8RjqrfZN8rUvE"
  )

  private val anyBinding: xyz.kd5ujc.schema.registry.SchemaBinding =
    xyz.kd5ujc.schema.registry.SchemaBinding(
      xyz.kd5ujc.schema.registry.RegistryName.unsafe("x.package"),
      xyz.kd5ujc.schema.registry.SemVer(2, 0, 0),
      io.constellationnetwork.security.hash.Hash("sh"),
      io.constellationnetwork.security.hash.Hash("lh")
    )

  private def mkAddr(str: String): io.constellationnetwork.schema.address.Address =
    eu.timepit.refined.refineV[io.constellationnetwork.schema.address.DAGAddressRefined].apply[String](str) match {
      case Right(v) => io.constellationnetwork.schema.address.Address(v)
      case Left(e)  => sys.error(s"bad test address: $e")
    }

  /** The commuteObligation flag of the single UpgradeReceipt in a committed migration (None if not committed). */
  private def commuteObligationOf(r: TransactionResult): Option[Boolean] = r match {
    case TransactionResult.Committed(_, _, logs, _, _, _, _, _) =>
      logs.collectFirst { case u: FiberLogEntry.UpgradeReceipt => u.commuteObligation }
    case _ => None
  }

  private def isCommitted(r: TransactionResult): Boolean = r match {
    case _: TransactionResult.Committed => true
    case _                              => false
  }

  private def isPolicyViolation(r: TransactionResult, dial: String): Boolean = r match {
    case TransactionResult.Aborted(FailureReason.PolicyViolation(d, _), _, _) => d == dial
    case _                                                                    => false
  }
}
