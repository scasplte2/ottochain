package xyz.kd5ujc.shared_data.fiber.core

import java.util.UUID

import cats.Monad
import cats.data.Chain
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FailureReason, FiberContext, FiberLogEntry, GasExhaustionPhase}

/**
 * MTL-style operations for execution tracking and context access.
 *
 * These operations work in any G[_] with the appropriate MTL instances,
 * enabling use in both ExecutionT and FiberT contexts.
 */
object ExecutionOps {

  def chargeGas[G[_]](amount: Long)(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.chargeGas(amount))

  def incrementDepth[G[_]](implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.incrementDepth)

  /** Mark a (fiberId, inputKey) as processed for cycle detection */
  def markProcessed[G[_]](
    fiberId:  UUID,
    inputKey: String
  )(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.markProcessed(fiberId, inputKey))

  def checkCycle[G[_]](
    fiberId:  UUID,
    inputKey: String
  )(implicit S: Stateful[G, ExecutionState]): G[Boolean] =
    S.inspect(_.wasProcessed(fiberId, inputKey))

  /** Append a log entry (receipt or invocation) to the accumulated log */
  def appendLog[G[_]](entry: FiberLogEntry)(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.appendLog(entry))

  /** Get all accumulated log entries */
  def getLogs[G[_]](implicit S: Stateful[G, ExecutionState]): G[Chain[FiberLogEntry]] =
    S.inspect(_.logEntries)

  def getState[G[_]](implicit S: Stateful[G, ExecutionState]): G[ExecutionState] =
    S.get

  def getGasUsed[G[_]](implicit S: Stateful[G, ExecutionState]): G[Long] =
    S.inspect(_.gasUsed)

  def getDepth[G[_]](implicit S: Stateful[G, ExecutionState]): G[Int] =
    S.inspect(_.depth)

  def askContext[G[_]](implicit A: Ask[G, FiberContext]): G[FiberContext] =
    A.ask

  def askOrdinal[G[_]](implicit A: Ask[G, FiberContext]): G[SnapshotOrdinal] =
    A.reader(_.ordinal)

  def askLimits[G[_]](implicit A: Ask[G, FiberContext]): G[ExecutionLimits] =
    A.reader(_.limits)

  def askGasConfig[G[_]](implicit
    A: Ask[G, FiberContext]
  ): G[io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig] =
    A.reader(_.jlvmGasConfig)

  def askSnapshotHash[G[_]](implicit A: Ask[G, FiberContext]): G[io.constellationnetwork.security.hash.Hash] =
    A.reader(_.lastSnapshotHash)

  def askEpochProgress[G[_]](implicit A: Ask[G, FiberContext]): G[io.constellationnetwork.schema.epoch.EpochProgress] =
    A.reader(_.epochProgress)

  def checkLimits[G[_]: Monad](implicit
    S: Stateful[G, ExecutionState],
    A: Ask[G, FiberContext]
  ): G[Option[FailureReason]] =
    for {
      limits  <- askLimits
      gasUsed <- getGasUsed
      depth   <- getDepth
    } yield
      if (depth >= limits.maxDepth)
        FailureReason.DepthExceeded(depth, limits.maxDepth).some
      else if (gasUsed >= limits.maxGas)
        FailureReason.GasExhaustedFailure(gasUsed, limits.maxGas, GasExhaustionPhase.Trigger).some
      else None

  def remainingGas[G[_]: Monad](implicit
    S: Stateful[G, ExecutionState],
    A: Ask[G, FiberContext]
  ): G[Long] =
    for {
      limits  <- askLimits
      gasUsed <- getGasUsed
    } yield (limits.maxGas - gasUsed).max(0L)

}
