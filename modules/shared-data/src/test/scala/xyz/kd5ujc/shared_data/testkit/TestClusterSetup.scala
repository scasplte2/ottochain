package xyz.kd5ujc.shared_data.testkit

import cats.effect.{IO, Ref}
import cats.syntax.all._
import xyz.kd5ujc.shared_data.identity.agent.DelegationManager
import scala.concurrent.duration._
import java.util.UUID

/**
 * Test cluster setup utilities for E2E delegation testing
 */
object TestClusterSetup {

  /**
   * Represents a test transaction result
   */
  case class TransactionResult(
    transactionHash: String,
    isAccepted:      Boolean,
    isRejected:      Boolean = false,
    isDelegated:     Boolean = false,
    wasRelayed:      Boolean = false,
    originalSigner:  String = "",
    relayerAddress:  String = "",
    rejectionReason: Option[String] = None,
    amount:          Option[Long] = None
  )

  /**
   * Represents delegation transaction result
   */
  case class DelegationResult(
    delegationId: String,
    isValid:      Boolean,
    errors:       List[String] = List.empty
  )

  /**
   * Represents transaction state in cluster
   */
  case class TransactionState(
    transactionHash:  String,
    isConfirmed:      Boolean,
    wasRelayed:       Boolean = false,
    originalSigner:   String = "",
    confirmationTime: Long = 0L
  )

  /**
   * Mock test cluster for delegation E2E testing
   */
  class TestCluster(
    activeDelegations:  Ref[IO, Map[String, DelegationManager.DelegationSession]],
    revokedDelegations: Ref[IO, Set[String]],
    transactionHistory: Ref[IO, List[TransactionResult]],
    spendingTracker:    Ref[IO, Map[String, Long]] // delegationId -> totalSpent
  ) {

    /**
     * Submit delegation to cluster
     */
    def submitDelegation(
      session:        DelegationManager.DelegationSession,
      relayerAddress: String
    ): IO[DelegationResult] =
      for {
        // Check if delegation is valid
        isValid <- DelegationManager.isDelegationValid(session)

        result <-
          if (isValid) {
            // Add to active delegations
            activeDelegations
              .update(_ + (session.delegationId -> session))
              .as(
                DelegationResult(session.delegationId, isValid = true)
              )
          } else {
            IO.pure(
              DelegationResult(
                session.delegationId,
                isValid = false,
                errors = List("Invalid delegation session")
              )
            )
          }
      } yield result

    /**
     * Submit revocation to cluster
     */
    def submitRevocation(
      revokedSession: DelegationManager.DelegationSession,
      revokerAddress: String
    ): IO[DelegationResult] =
      for {
        // Add to revoked set and remove from active
        _ <- revokedDelegations.update(_ + revokedSession.delegationId)
        _ <- activeDelegations.update(_ - revokedSession.delegationId)
      } yield DelegationResult(revokedSession.delegationId, isValid = true)

    /**
     * Submit emergency revocation
     */
    def submitEmergencyRevocation(
      agentAddress: String,
      sessions:     List[DelegationManager.DelegationSession],
      reason:       String
    ): IO[DelegationResult] =
      for {
        sessionIds <- IO.pure(sessions.map(_.delegationId))

        // Revoke all sessions
        _ <- revokedDelegations.update(_ ++ sessionIds.toSet)
        _ <- activeDelegations.update(current => current -- sessionIds)

      } yield DelegationResult("emergency_revocation", isValid = true)

    /**
     * Submit delegated transaction
     */
    def submitDelegatedTransaction(
      transaction:       TestTransaction,
      delegationSession: DelegationManager.DelegationSession,
      relayerAddress:    String
    ): IO[TransactionResult] =
      for {
        // Check if delegation is still active and not revoked
        activeDelegationsMap <- activeDelegations.get
        revokedSet           <- revokedDelegations.get

        result <-
          if (revokedSet.contains(delegationSession.delegationId)) {
            // Delegation is revoked
            IO.pure(
              TransactionResult(
                transactionHash = UUID.randomUUID().toString,
                isAccepted = false,
                isRejected = true,
                rejectionReason = Some("delegation revoked")
              )
            )
          } else if (!activeDelegationsMap.contains(delegationSession.delegationId)) {
            // Delegation not found
            IO.pure(
              TransactionResult(
                transactionHash = UUID.randomUUID().toString,
                isAccepted = false,
                isRejected = true,
                rejectionReason = Some("delegation not found")
              )
            )
          } else {
            // Check delegation validity (expiry, etc.)
            activeDelegation = activeDelegationsMap(delegationSession.delegationId)
            currentTime = System.currentTimeMillis() / 1000

            if (currentTime > activeDelegation.expiresAt) {
              // Delegation expired
              IO.pure(
                TransactionResult(
                  transactionHash = UUID.randomUUID().toString,
                  isAccepted = false,
                  isRejected = true,
                  rejectionReason = Some("delegation expired")
                )
              )
            } else if (!activeDelegation.scopedOperations.contains(transaction.operationType)) {
              // Operation not in scope
              IO.pure(
                TransactionResult(
                  transactionHash = UUID.randomUUID().toString,
                  isAccepted = false,
                  isRejected = true,
                  rejectionReason = Some("operation not in scope")
                )
              )
            } else {
              // Check spending limit
              for {
                currentSpending <- spendingTracker.get
                totalSpent = currentSpending.getOrElse(delegationSession.delegationId, 0L)
                transactionAmount = transaction.amount.getOrElse(0L)

                result <-
                  if (totalSpent + transactionAmount > activeDelegation.maxSpendLimit) {
                    IO.pure(
                      TransactionResult(
                        transactionHash = UUID.randomUUID().toString,
                        isAccepted = false,
                        isRejected = true,
                        rejectionReason = Some("spending limit exceeded")
                      )
                    )
                  } else {
                    // Transaction accepted
                    val txHash = UUID.randomUUID().toString
                    val txResult = TransactionResult(
                      transactionHash = txHash,
                      isAccepted = true,
                      isDelegated = true,
                      wasRelayed = true,
                      originalSigner = transaction.delegatorAddress,
                      relayerAddress = relayerAddress,
                      amount = transaction.amount
                    )

                    for {
                      // Update spending tracker and add to history
                      _ <- spendingTracker.update(current =>
                        current + (delegationSession.delegationId -> (totalSpent + transactionAmount))
                      )
                      _ <- transactionHistory.update(txResult :: _)
                    } yield txResult
                  }
              } yield result
            }
          }
      } yield result

    /**
     * Wait for transaction confirmation
     */
    def waitForTransactionConfirmation(
      transactionHash: String,
      timeout:         Duration
    ): IO[TransactionState] =
      for {
        history <- transactionHistory.get
        result <- history.find(_.transactionHash == transactionHash) match {
          case Some(tx) =>
            IO.pure(
              TransactionState(
                transactionHash = transactionHash,
                isConfirmed = true,
                wasRelayed = tx.wasRelayed,
                originalSigner = tx.originalSigner,
                confirmationTime = System.currentTimeMillis()
              )
            )
          case None =>
            IO.pure(
              TransactionState(
                transactionHash = transactionHash,
                isConfirmed = false
              )
            )
        }
      } yield result

    /**
     * Get total spent amount for delegation
     */
    def getSpentAmountForDelegation(delegationId: String): IO[Long] =
      spendingTracker.get.map(_.getOrElse(delegationId, 0L))

    /**
     * Shutdown test cluster
     */
    def shutdown(): IO[Unit] =
      for {
        _ <- activeDelegations.set(Map.empty)
        _ <- revokedDelegations.set(Set.empty)
        _ <- transactionHistory.set(List.empty)
        _ <- spendingTracker.set(Map.empty)
      } yield ()
  }

  /**
   * Test transaction representation
   */
  case class TestTransaction(
    delegatorAddress: String,
    sessionKey:       String,
    operationType:    String,
    operation:        String,
    amount:           Option[Long] = None,
    transactionId:    Option[String] = None
  )

  /**
   * Setup local test cluster for delegation testing
   */
  def setupLocalCluster(): IO[TestCluster] =
    for {
      activeDelegationsRef  <- Ref.of[IO, Map[String, DelegationManager.DelegationSession]](Map.empty)
      revokedDelegationsRef <- Ref.of[IO, Set[String]](Set.empty)
      transactionHistoryRef <- Ref.of[IO, List[TransactionResult]](List.empty)
      spendingTrackerRef    <- Ref.of[IO, Map[String, Long]](Map.empty)
    } yield new TestCluster(
      activeDelegationsRef,
      revokedDelegationsRef,
      transactionHistoryRef,
      spendingTrackerRef
    )
}
