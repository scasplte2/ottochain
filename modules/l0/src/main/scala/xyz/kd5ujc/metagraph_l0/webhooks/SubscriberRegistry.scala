package xyz.kd5ujc.metagraph_l0.webhooks

import cats.effect.kernel.{Async, Ref}
import cats.implicits._

/**
 * Registry for webhook subscribers.
 *
 * Currently in-memory; can be extended to persist to on-chain state
 * or external storage for production use.
 */
trait SubscriberRegistry[F[_]] {

  /** Register a new subscriber */
  def register(callbackUrl: String, secret: Option[String]): F[Subscriber]

  /** Unregister a subscriber by ID */
  def unregister(id: String): F[Boolean]

  /** List all subscribers */
  def list: F[List[Subscriber]]

  /** Get active subscribers only */
  def listActive: F[List[Subscriber]]

  /** Mark a subscriber's delivery as successful */
  def markSuccess(id: String): F[Unit]

  /** Mark a subscriber's delivery as failed (deactivates after threshold) */
  def markFailure(id: String): F[Unit]
}

object SubscriberRegistry {

  /** Maximum consecutive failures before deactivating a subscriber */
  val MaxFailures: Int = 5

  /**
   * Create an in-memory subscriber registry
   */
  def make[F[_]](implicit F: Async[F]): F[SubscriberRegistry[F]] =
    Ref.of[F, Map[String, Subscriber]](Map.empty).map { ref =>
      new SubscriberRegistry[F] {

        def register(callbackUrl: String, secret: Option[String]): F[Subscriber] = {
          val subscriber = Subscriber.create(callbackUrl, secret)
          ref.update(_ + (subscriber.id -> subscriber)).as(subscriber)
        }

        def unregister(id: String): F[Boolean] =
          ref.modify { map =>
            if (map.contains(id)) (map - id, true)
            else (map, false)
          }

        def list: F[List[Subscriber]] =
          ref.get.map(_.values.toList.sortBy(_.createdAt))

        def listActive: F[List[Subscriber]] =
          list.map(_.filter(_.active))

        def markSuccess(id: String): F[Unit] =
          ref.update(_.updatedWith(id)(_.map { s =>
            s.copy(
              lastDeliveryAt = Some(java.time.Instant.now()),
              failCount = 0
            )
          }))

        def markFailure(id: String): F[Unit] =
          ref.update(_.updatedWith(id)(_.map { s =>
            val newFailCount = s.failCount + 1
            s.copy(
              failCount = newFailCount,
              active = newFailCount < MaxFailures
            )
          }))
      }
    }
}
