package xyz.kd5ujc.metagraph_l0

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataOnChainState, DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{
  CommitKey,
  CommittedBreadcrumb,
  CommittedCommitment,
  CommittedOnChain,
  CommittedView
}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite

/**
 * The CommittedState adapter:
 *
 *   - `CommittedView[CalculatedState]` projects fibers under `fiber/<uuid>` and scripts under
 *     `script/<id>`, deterministically, with the delta override agreeing with the default
 *     structural diff;
 *   - a fiber transition yields a minimal delta whose trie application reproduces the full
 *     rebuild (the invariant `CommittedState.setCommitted` asserts on every transition);
 *   - the assembled L0 service emits the on-chain breadcrumb from `combine`;
 *   - the `/committed/...` and committed-cell-backed custom routes respond.
 */
object CommittedAdapterSuite extends SimpleIOSuite {

  private val view: CommittedView[CalculatedState] = CommittedView[CalculatedState]

  private def simpleMachine: StateMachineDefinition = {
    val draft = StateId("draft")
    val active = StateId("active")
    StateMachineDefinition(
      states = Map(
        draft  -> State(draft, isFinal = false),
        active -> State(active, isFinal = true)
      ),
      initialState = draft,
      transitions = List(
        Transition(
          from = draft,
          to = active,
          eventName = "activate",
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(MapValue(Map("activated" -> BoolValue(true))))
        )
      )
    )
  }

  private def breadcrumbOf(onChain: DataOnChainState): Option[CommittedBreadcrumb] =
    onChain match {
      case c: CommittedOnChain[_] => c.breadcrumb.some
      case _                      => none
    }

  test("projection: fibers -> fiber/<uuid>, scripts -> script/<id>, deterministic enumeration") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberA   <- UUIDGen.randomUUID[IO]
        fiberB   <- UUIDGen.randomUUID[IO]
        scriptId <- UUIDGen.randomUUID[IO]

        createA = Updates.CreateStateMachine(fiberA, simpleMachine, MapValue(Map.empty))
        createB = Updates.CreateStateMachine(fiberB, simpleMachine, MapValue(Map.empty))
        scriptProgram <- IO.fromEither(parse("""{"result": "success"}""").flatMap(_.as[JsonLogicExpression]))
        createScript = Updates.CreateScript(scriptId, scriptProgram, None, AccessControlPolicy.Public)

        proofA <- fixture.registry.generateProofs(createA, Set(Alice, Bob))
        proofB <- fixture.registry.generateProofs(createB, Set(Alice, Bob))
        proofS <- fixture.registry.generateProofs(createScript, Set(Alice))

        state0 = DataState(OnChain.genesis, CalculatedState.genesis)
        state1 <- combiner.insert(state0, Signed(createA, proofA))
        state2 <- combiner.insert(state1, Signed(createB, proofB))
        state3 <- combiner.insert(state2, Signed(createScript, proofS))

        entries = view.entries(state3.calculated)

        // The same records assembled in a different in-memory order project identically
        reassembled = CalculatedState(
          SortedMap.from(state3.calculated.stateMachines.toList.reverse),
          SortedMap.from(state3.calculated.scripts.toList.reverse)
        )
      } yield expect.same(
        entries.keySet.map(_.value),
        Set(s"fiber/$fiberA", s"fiber/$fiberB", s"script/$scriptId")
      ) and
      expect(entries.keys.forall(k => CommitKey.from(k.value).isRight)) and
      expect(entries.keys.map(_.namespace).toSet == Set("fiber", "script")) and
      expect.same(view.entries(reassembled).toList, entries.toList) and
      expect(view.delta(state3.calculated, reassembled).isEmpty)
    }
  }

  test("fiber transition: minimal delta, delta-applied trie == rebuilt trie, MPT root changes") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        create = Updates.CreateStateMachine(fiberId, simpleMachine, MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(create, Set(Alice, Bob))
        created     <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        transition = Updates.TransitionStateMachine(fiberId, "activate", MapValue(Map.empty), FiberOrdinal.MinValue)
        transitionProof <- fixture.registry.generateProofs(transition, Set(Alice, Bob))
        transitioned    <- combiner.insert(created, Signed(transition, transitionProof))

        prevEntries = view.entries(created.calculated)
        nextEntries = view.entries(transitioned.calculated)
        delta = view.delta(created.calculated, transitioned.calculated)

        // The overridden delta must agree with the default structural diff over entries
        expectedUpserts = nextEntries.filter { case (k, v) => !prevEntries.get(k).contains(v) }
        expectedRemoves = prevEntries.keySet.diff(nextEntries.keySet)

        prevTrie    <- CommittedCommitment.buildTrie[IO](prevEntries)
        nextTrie    <- CommittedCommitment.buildTrie[IO](nextEntries)
        appliedTrie <- CommittedCommitment.applyDelta[IO](prevTrie, delta)
      } yield expect(
        transitioned.calculated.stateMachines.get(fiberId).map(_.currentState).contains(StateId("active"))
      ) and
      expect.same(delta.upserts.keySet.map(_.value), Set(s"fiber/$fiberId")) and
      expect(delta.removes.isEmpty) and
      expect.same(delta.upserts.toList, expectedUpserts.toList) and
      expect(delta.removes == expectedRemoves) and
      expect(prevTrie.rootNode.digest != nextTrie.rootNode.digest) and
      expect(appliedTrie.rootNode.digest == nextTrie.rootNode.digest)
    }
  }

  test("service combine: on-chain breadcrumb advances ordinal and commits the new roots") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        service <- ML0Service.make[IO]()
        fiberId <- UUIDGen.randomUUID[IO]

        genesisBc = breadcrumbOf(service.genesis.onChain)

        create = Updates.CreateStateMachine(fiberId, simpleMachine, MapValue(Map.empty))
        proof <- fixture.registry.generateProofs(create, Set(Alice, Bob))

        combined <- service.combine(service.genesis, List(Signed(create, proof)))
        combinedBc = breadcrumbOf(combined.onChain)

        // A second (empty) batch chains off the first breadcrumb: state unchanged -> same
        // mptRoot, but the catalog accrues the previous root -> new catalogRoot.
        combinedAgain <- service.combine(combined, List.empty)
        secondBc = breadcrumbOf(combinedAgain.onChain)
      } yield expect(genesisBc.map(_.ordinal.value.value).contains(0L)) and
      expect(combinedBc.map(_.ordinal.value.value).contains(1L)) and
      expect(secondBc.map(_.ordinal.value.value).contains(2L)) and
      expect((genesisBc, combinedBc).mapN(_.roots.mptRoot != _.roots.mptRoot).contains(true)) and
      expect((genesisBc, combinedBc).mapN(_.roots.catalogRoot != _.roots.catalogRoot).contains(true)) and
      expect((combinedBc, secondBc).mapN(_.roots.mptRoot == _.roots.mptRoot).contains(true)) and
      expect((combinedBc, secondBc).mapN(_.roots.catalogRoot != _.roots.catalogRoot).contains(true))
    }
  }

  test("routes: /committed/root and the committed-cell-backed custom routes respond") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        service <- ML0Service.make[IO]()
        app = service.routes.orNotFound

        rootResp <- app.run(Request[IO](Method.GET, uri"/committed/root"))
        rootJson <- rootResp.as[Json]
        cursor = rootJson.hcursor

        checkpointResp    <- app.run(Request[IO](Method.GET, uri"/v1/checkpoint"))
        stateMachinesResp <- app.run(Request[IO](Method.GET, uri"/v1/state-machines"))
      } yield expect(rootResp.status == Status.Ok) and
      expect(cursor.get[Long]("ordinal") == Right(0L)) and
      expect(cursor.get[Boolean]("hydrated") == Right(true)) and
      expect(cursor.get[String]("mptRoot").isRight) and
      expect(cursor.get[String]("calculatedStateHash").isRight) and
      expect(checkpointResp.status == Status.Ok) and
      expect(stateMachinesResp.status == Status.Ok)
    }
  }
}
