package xyz.kd5ujc.shared_data.identity.agent

import cats.effect.IO
import java.util.UUID

/**
 * Delegation Management System for OttoChain Agent Identity
 *
 * Manages agent delegation privileges, session keys, stake bonds, and revocation.
 * Implements the hybrid session keys + signed intents approach from the security analysis.
 *
 * Features:
 * - Session key delegation with 24h maximum expiry
 * - Stake/bond mechanism for delegation authority
 * - Real-time revocation checking
 * - Anti-Sybil resistance through reputation requirements
 * - Integration with reputation scoring system
 */
object DelegationManager {

  // Maximum session key duration (24 hours)
  final val MAX_SESSION_DURATION = 86400L

  // Minimum stake amounts by delegation level
  final val MIN_STAKE_BASIC = 100L
  final val MIN_STAKE_ADVANCED = 500L
  final val MIN_STAKE_EXPERT = 1000L

  // Revocation propagation time (30 seconds)
  final val REVOCATION_PROPAGATION_TIME = 30L

  /**
   * Active delegation session
   */
  case class DelegationSession(
    delegationId:     String,
    agentAddress:     String,
    delegatorAddress: String,
    sessionPublicKey: String,
    scopedOperations: List[String],
    stakeAmount:      Long,
    maxSpendLimit:    Long,
    expiresAt:        Long,
    createdAt:        Long,
    isRevoked:        Boolean = false,
    revokedAt:        Option[Long] = None,
    revokedBy:        Option[String] = None,
    revokedReason:    Option[String] = None
  )

  /**
   * Stake bond for delegation privileges
   */
  case class StakeBond(
    agentAddress:    String,
    bondedAmount:    Long,
    delegationLevel: String,
    bondedAt:        Long,
    unlockAt:        Long,
    slashedAmount:   Long = 0L
  )

  /**
   * Delegation request validation result
   */
  case class ValidationResult(
    isValid:  Boolean,
    errors:   List[String] = List.empty,
    warnings: List[String] = List.empty
  )

  /**
   * Agent delegation state
   */
  case class AgentDelegationState(
    address:               String,
    stakeBonds:            List[StakeBond],
    activeDelegations:     List[DelegationSession],
    totalStakeBonded:      Long,
    delegationLevel:       String,
    reputationState:       ReputationScoring.AgentReputationState,
    successfulDelegations: Int,
    failedDelegations:     Int,
    slashingEvents:        Int,
    lastDelegationAt:      Long
  )

  /**
   * Validate delegation request against agent capabilities and reputation
   */
  def validateDelegationRequest(
    agentState:          AgentDelegationState,
    requestedOperations: List[String],
    stakeAmount:         Long,
    sessionDuration:     Long,
    maxSpendLimit:       Long
  ): IO[ValidationResult] = IO {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()

    // Check reputation requirements
    val minReputation = agentState.delegationLevel match {
      case "EXPERT"   => ReputationScoring.DELEGATION_EXPERT_THRESHOLD
      case "ADVANCED" => ReputationScoring.DELEGATION_ADVANCED_THRESHOLD
      case "BASIC"    => ReputationScoring.DELEGATION_BASIC_THRESHOLD
      case _          => Int.MaxValue
    }

    if (agentState.reputationState.compositeScore < minReputation) {
      errors += s"Agent reputation ${agentState.reputationState.compositeScore} below required ${minReputation}"
    }

    // Check stake bond requirements
    val minStakeRequired = agentState.delegationLevel match {
      case "EXPERT"   => MIN_STAKE_EXPERT
      case "ADVANCED" => MIN_STAKE_ADVANCED
      case "BASIC"    => MIN_STAKE_BASIC
      case _          => Long.MaxValue
    }

    if (stakeAmount < minStakeRequired) {
      errors += s"Stake amount ${stakeAmount} below required minimum ${minStakeRequired}"
    }

    // Check if agent has sufficient bonded stake
    val availableStake = agentState.totalStakeBonded - agentState.activeDelegations.map(_.stakeAmount).sum
    if (stakeAmount > availableStake) {
      errors += s"Insufficient bonded stake. Available: ${availableStake}, Requested: ${stakeAmount}"
    }

    // Check session duration limits
    if (sessionDuration > MAX_SESSION_DURATION) {
      errors += s"Session duration ${sessionDuration} exceeds maximum ${MAX_SESSION_DURATION}"
    }

    val maxSessionForLevel = agentState.reputationState.delegationCapability.maxSessionDuration
    if (sessionDuration > maxSessionForLevel) {
      errors += s"Session duration ${sessionDuration} exceeds level limit ${maxSessionForLevel}"
    }

    // Check operation permissions
    val allowedOps = agentState.reputationState.delegationCapability.allowedOperations
    val unauthorizedOps = requestedOperations.filterNot(allowedOps.contains)
    if (unauthorizedOps.nonEmpty) {
      errors += s"Unauthorized operations: ${unauthorizedOps.mkString(", ")}"
    }

    // Check for too many concurrent delegations (anti-spam)
    if (agentState.activeDelegations.length >= 10) {
      errors += "Too many active delegations (maximum 10)"
    }

    // Warning for high violation rate
    val violationRate = if (agentState.reputationState.attestationCount > 0) {
      agentState.reputationState.violationCount.toDouble / agentState.reputationState.attestationCount.toDouble
    } else 0.0

    if (violationRate > 0.2) {
      warnings += s"Agent has high violation rate: ${(violationRate * 100).toInt}%"
    }

    // Warning for new agent
    if (agentState.reputationState.attestationCount < 5) {
      warnings += "Agent has limited attestation history"
    }

    ValidationResult(
      isValid = errors.isEmpty,
      errors = errors.toList,
      warnings = warnings.toList
    )
  }

  /**
   * Create new delegation session
   */
  def createDelegationSession(
    agentAddress:     String,
    delegatorAddress: String,
    sessionPublicKey: String,
    scopedOperations: List[String],
    stakeAmount:      Long,
    maxSpendLimit:    Long,
    durationSeconds:  Long
  ): IO[DelegationSession] = IO {
    val delegationId = UUID.randomUUID().toString
    val currentTime = System.currentTimeMillis() / 1000 // Unix timestamp
    val expiresAt = currentTime + durationSeconds

    DelegationSession(
      delegationId = delegationId,
      agentAddress = agentAddress,
      delegatorAddress = delegatorAddress,
      sessionPublicKey = sessionPublicKey,
      scopedOperations = scopedOperations,
      stakeAmount = stakeAmount,
      maxSpendLimit = maxSpendLimit,
      expiresAt = expiresAt,
      createdAt = currentTime
    )
  }

  /**
   * Revoke delegation session
   */
  def revokeDelegation(
    session:   DelegationSession,
    revokedBy: String,
    reason:    String
  ): IO[DelegationSession] = IO {
    session.copy(
      isRevoked = true,
      revokedAt = Some(System.currentTimeMillis() / 1000),
      revokedBy = Some(revokedBy),
      revokedReason = Some(reason)
    )
  }

  /**
   * Check if delegation is valid and not revoked
   */
  def isDelegationValid(session: DelegationSession): IO[Boolean] = IO {
    val currentTime = System.currentTimeMillis() / 1000

    // Check if revoked
    if (session.isRevoked) false
    // Check if expired
    else if (currentTime > session.expiresAt) false
    // Valid
    else true
  }

  /**
   * Bond stake for delegation privileges
   */
  def bondStakeForDelegation(
    agentAddress: String,
    amount:       Long,
    targetLevel:  String
  ): IO[StakeBond] = IO {
    val currentTime = System.currentTimeMillis() / 1000
    val unlockTime = currentTime + (30 * 24 * 60 * 60) // 30 days lock period

    StakeBond(
      agentAddress = agentAddress,
      bondedAmount = amount,
      delegationLevel = targetLevel,
      bondedAt = currentTime,
      unlockAt = unlockTime
    )
  }

  /**
   * Slash bonded stake for misbehavior
   */
  def slashStake(
    bond:               StakeBond,
    slashingPercentage: Double = 0.25
  ): IO[StakeBond] = IO {
    val slashAmount = (bond.bondedAmount * slashingPercentage).toLong
    bond.copy(slashedAmount = bond.slashedAmount + slashAmount)
  }

  /**
   * Calculate delegation fees
   */
  def calculateDelegationFees(
    stakeAmount:     Long,
    sessionDuration: Long,
    operations:      List[String]
  ): IO[Long] = IO {
    val baseFee = 1L // Base fee of 1 unit
    val stakeFee = (stakeAmount * 0.001).toLong // 0.1% of stake
    val durationFee = sessionDuration / 3600 // 1 unit per hour
    val operationFee = operations.length * 2L // 2 units per operation type

    baseFee + stakeFee + durationFee + operationFee
  }

  /**
   * Update agent delegation state after delegation completion
   */
  def updateDelegationMetrics(
    agentState:    AgentDelegationState,
    session:       DelegationSession,
    wasSuccessful: Boolean
  ): IO[AgentDelegationState] = IO {
    val updatedSuccessful =
      if (wasSuccessful) agentState.successfulDelegations + 1 else agentState.successfulDelegations
    val updatedFailed = if (!wasSuccessful) agentState.failedDelegations + 1 else agentState.failedDelegations

    // Remove completed delegation from active list
    val updatedActiveDelegations = agentState.activeDelegations.filterNot(_.delegationId == session.delegationId)

    // Update reputation based on delegation outcome
    val reputationUpdate = if (wasSuccessful) {
      ReputationScoring.updateReputationFromAttestation(
        agentState.reputationState,
        "COMPLETION",
        1.0
      )
    } else {
      ReputationScoring.updateReputationFromAttestation(
        agentState.reputationState,
        "VIOLATION",
        1.0
      )
    }

    reputationUpdate.map { updatedReputation =>
      agentState.copy(
        activeDelegations = updatedActiveDelegations,
        reputationState = updatedReputation,
        successfulDelegations = updatedSuccessful,
        failedDelegations = updatedFailed,
        lastDelegationAt = System.currentTimeMillis() / 1000
      )
    }
  }.flatten

  /**
   * Get agent delegation statistics
   */
  def getAgentDelegationStats(agentState: AgentDelegationState): IO[Map[String, Any]] = IO {
    val totalDelegations = agentState.successfulDelegations + agentState.failedDelegations
    val successRate = if (totalDelegations > 0) {
      agentState.successfulDelegations.toDouble / totalDelegations.toDouble
    } else 0.0

    val totalStakeManaged = agentState.activeDelegations.map(_.stakeAmount).sum

    Map(
      "agent_address"          -> agentState.address,
      "delegation_level"       -> agentState.delegationLevel,
      "total_stake_bonded"     -> agentState.totalStakeBonded,
      "total_stake_managed"    -> totalStakeManaged,
      "active_delegations"     -> agentState.activeDelegations.length,
      "successful_delegations" -> agentState.successfulDelegations,
      "failed_delegations"     -> agentState.failedDelegations,
      "success_rate"           -> successRate,
      "reputation_score"       -> agentState.reputationState.compositeScore,
      "slashing_events"        -> agentState.slashingEvents,
      "last_delegation_at"     -> agentState.lastDelegationAt
    )
  }

  /**
   * Emergency revocation for security breaches
   */
  def emergencyRevokeAllDelegations(
    agentState: AgentDelegationState,
    reason:     String
  ): IO[AgentDelegationState] = IO {
    val revokedDelegations = agentState.activeDelegations.map { delegation =>
      delegation.copy(
        isRevoked = true,
        revokedAt = Some(System.currentTimeMillis() / 1000),
        revokedBy = Some("SYSTEM"),
        revokedReason = Some(s"EMERGENCY: $reason")
      )
    }

    // Apply reputation penalty for emergency revocation
    val penalizedReputation = ReputationScoring.applySlashingPenalty(
      agentState.reputationState,
      0.5 // 50% penalty for emergency situations
    )

    penalizedReputation.map { updatedReputation =>
      agentState.copy(
        activeDelegations = revokedDelegations,
        reputationState = updatedReputation,
        failedDelegations = agentState.failedDelegations + revokedDelegations.length,
        slashingEvents = agentState.slashingEvents + 1
      )
    }
  }.flatten

  /**
   * Initialize delegation state for new agent
   */
  def initializeAgentDelegationState(
    address:                String,
    initialReputationState: ReputationScoring.AgentReputationState
  ): AgentDelegationState =
    AgentDelegationState(
      address = address,
      stakeBonds = List.empty,
      activeDelegations = List.empty,
      totalStakeBonded = 0L,
      delegationLevel = initialReputationState.delegationCapability.level,
      reputationState = initialReputationState,
      successfulDelegations = 0,
      failedDelegations = 0,
      slashingEvents = 0,
      lastDelegationAt = 0L
    )
}
