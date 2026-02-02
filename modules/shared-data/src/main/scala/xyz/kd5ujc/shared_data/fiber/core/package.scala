package xyz.kd5ujc.shared_data.fiber

import cats.data.{ReaderT, StateT}
import cats.mtl.{Ask, Stateful}
import cats.{~>, Applicative, Monad}

import xyz.kd5ujc.schema.fiber.FiberContext

package object core {

  /** Effect type for execution state tracking */
  type ExecutionT[F[_], A] = StateT[F, ExecutionState, A]

  /** Effect type with both execution state and fiber context */
  type FiberT[F[_], A] = ReaderT[ExecutionT[F, *], FiberContext, A]

  // === Extension Methods for Lifting ===

  implicit class LiftVia[F[_], A](private val fa: F[A]) extends AnyVal {

    /** Lift F[A] into G[A] using an implicit natural transformation F ~> G */
    def liftTo[G[_]](implicit fk: F ~> G): G[A] = fk(fa)
  }

  implicit class LiftToExecutionT[F[_]: Monad, A](fa: F[A]) {

    /** Lift an F[A] into ExecutionT[F, A] */
    def liftExec: ExecutionT[F, A] = StateT.liftF(fa)
  }

  implicit class LiftToFiberT[F[_]: Monad, A](fa: F[A]) {

    /** Lift an F[A] into FiberT[F, A] */
    def liftFiber: FiberT[F, A] = ReaderT.liftF(StateT.liftF(fa))
  }

  implicit class ExecutionTToFiberT[F[_]: Monad, A](eta: ExecutionT[F, A]) {

    /** Lift an ExecutionT[F, A] into FiberT[F, A] */
    def liftFiber: FiberT[F, A] = ReaderT.liftF(eta)
  }

  implicit class PureToFiberT[A](a: A) {

    /** Lift a pure value into FiberT */
    def pureFiber[F[_]: Monad]: FiberT[F, A] =
      ReaderT.pure[ExecutionT[F, *], FiberContext, A](a)
  }

  implicit class PureToExecutionT[A](a: A) {

    /** Lift a pure value into ExecutionT */
    def pureExec[F[_]: Monad]: ExecutionT[F, A] =
      StateT.pure[F, ExecutionState, A](a)
  }

  /**
   * MTL instances for FiberT.
   * Import these when working in FiberT context.
   */
  object FiberTInstances {

    /** Stateful[FiberT[F, *], ExecutionState] - state access in FiberT */
    implicit def fiberTStateful[F[_]: Monad]: Stateful[FiberT[F, *], ExecutionState] =
      new Stateful[FiberT[F, *], ExecutionState] {
        val monad: Monad[FiberT[F, *]] = Monad[FiberT[F, *]]

        def get: FiberT[F, ExecutionState] = ReaderT.liftF(StateT.get)

        def set(s: ExecutionState): FiberT[F, Unit] = ReaderT.liftF(StateT.set(s))

        override def inspect[A](f: ExecutionState => A): FiberT[F, A] = ReaderT.liftF(StateT.inspect(f))

        override def modify(f: ExecutionState => ExecutionState): FiberT[F, Unit] =
          ReaderT.liftF(StateT.modify(f))
      }

    /** Ask[FiberT[F, *], FiberContext] - context access in FiberT */
    implicit def fiberTAsk[F[_]: Monad]: Ask[FiberT[F, *], FiberContext] =
      new Ask[FiberT[F, *], FiberContext] {
        val applicative: Applicative[FiberT[F, *]] = Applicative[FiberT[F, *]]

        def ask[E2 >: FiberContext]: FiberT[F, E2] =
          ReaderT[ExecutionT[F, *], FiberContext, E2](ctx => StateT.pure(ctx))
      }

    /** Natural transformation F ~> FiberT[F, *] for lifting effects */
    implicit def fiberTLift[F[_]: Monad]: F ~> FiberT[F, *] =
      new (F ~> FiberT[F, *]) {
        def apply[A](fa: F[A]): FiberT[F, A] = fa.liftFiber
      }
  }

  /**
   * MTL instances for ExecutionT.
   * Import these when working directly with ExecutionT (without FiberContext).
   */
  object ExecutionTInstances {

    /** Stateful[ExecutionT[F, *], ExecutionState] - state access in ExecutionT */
    implicit def executionTStateful[F[_]: Monad]: Stateful[ExecutionT[F, *], ExecutionState] =
      new Stateful[ExecutionT[F, *], ExecutionState] {
        val monad: Monad[ExecutionT[F, *]] = Monad[ExecutionT[F, *]]

        def get: ExecutionT[F, ExecutionState] = StateT.get

        def set(s: ExecutionState): ExecutionT[F, Unit] = StateT.set(s)

        override def inspect[A](f: ExecutionState => A): ExecutionT[F, A] = StateT.inspect(f)

        override def modify(f: ExecutionState => ExecutionState): ExecutionT[F, Unit] =
          StateT.modify(f)
      }

    /** Natural transformation F ~> ExecutionT[F, *] for lifting effects */
    implicit def executionTLift[F[_]: Monad]: F ~> ExecutionT[F, *] =
      new (F ~> ExecutionT[F, *]) {
        def apply[A](fa: F[A]): ExecutionT[F, A] = fa.liftExec
      }
  }
}
