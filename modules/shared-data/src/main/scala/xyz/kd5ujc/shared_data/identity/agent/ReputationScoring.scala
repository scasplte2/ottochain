package xyz.kd5ujc.shared_data.identity.agent

import cats.effect.IO
import scala.math._

/**
 * Reputation Scoring Framework for OttoChain Agent Identity
 *
 * Implements multi-component reputation scoring with time decay and delegation privilege calculation.
 * Based on the security analysis design for trust-based agent selection.
 *
 * Components:
 * - Performance (40%): Task completion success rate
 * - Reliability (30%): Uptime and responsiveness metrics
 * - Specialization (20%): Domain-specific expertise
 * - Network (10%): Peer vouching and social proof
 *
 * Features:
 * - Time decay with 90-day half-life for inactive agents
 * - Anti-gaming measures and outlier detection
 * - Delegation level calculation based on composite score
 */
object ReputationScoring {

  // Base reputation score for new agents
  final val BASE_REPUTATION = 10

  // Component weights for reputation calculation
  final val PERFORMANCE_WEIGHT = 0.40
  final val RELIABILITY_WEIGHT = 0.30
  final val SPECIALIZATION_WEIGHT = 0.20
  final val NETWORK_WEIGHT = 0.10

  // Time decay parameters
  final val DECAY_HALF_LIFE_DAYS = 90.0
  final val DECAY_LAMBDA = log(2.0) / DECAY_HALF_LIFE_DAYS

  // Reputation thresholds for delegation levels
  final val DELEGATION_BASIC_THRESHOLD = 25
  final val DELEGATION_ADVANCED_THRESHOLD = 50
  final val DELEGATION_EXPERT_THRESHOLD = 100

  // Attestation scoring values
  final val COMPLETION_SCORE = 5
  final val BEHAVIORAL_SCORE = 3
  final val VOUCH_SCORE = 2
  final val VIOLATION_PENALTY = -10

  /**
   * Core reputation components for an agent
   */
  case class ReputationComponents(
    performance:      Double, // 0-100, task completion success rate
    reliability:      Double, // 0-100, uptime and responsiveness
    specialization:   Double, // 0-100, domain expertise rating
    network:          Double, // 0-100, peer vouching score
    lastActivityDays: Int // Days since last activity (for decay)
  )

  /**
   * Delegation capability based on reputation
   */
  case class DelegationCapability(
    level:              String, // BASIC, ADVANCED, EXPERT
    maxStakeAmount:     Long, // Maximum stake allowed at this level
    maxSessionDuration: Long, // Maximum session duration (seconds)
    allowedOperations:  List[String] // Operations permitted
  )

  /**
   * Agent reputation state
   */
  case class AgentReputationState(
    address:              String,
    baseReputation:       Int,
    components:           ReputationComponents,
    compositeScore:       Double,
    delegationCapability: DelegationCapability,
    attestationCount:     Int,
    completionCount:      Int,
    violationCount:       Int,
    vouchCount:           Int,
    lastUpdated:          Long // Timestamp
  )

  /**
   * Calculate composite reputation score from components
   */
  def calculateCompositeScore(components: ReputationComponents): Double = {
    val baseScore =
      components.performance * PERFORMANCE_WEIGHT +
      components.reliability * RELIABILITY_WEIGHT +
      components.specialization * SPECIALIZATION_WEIGHT +
      components.network * NETWORK_WEIGHT

    // Apply time decay for inactive agents
    val decayFactor = exp(-DECAY_LAMBDA * components.lastActivityDays)

    baseScore * decayFactor
  }

  /**
   * Determine delegation capability based on reputation score
   */
  def determineDelegationCapability(compositeScore: Double): DelegationCapability =
    if (compositeScore >= DELEGATION_EXPERT_THRESHOLD) {
      DelegationCapability(
        level = "EXPERT",
        maxStakeAmount = 10000L,
        maxSessionDuration = 86400L, // 24 hours
        allowedOperations = List("market", "governance", "corporate", "contract", "token_transfer")
      )
    } else if (compositeScore >= DELEGATION_ADVANCED_THRESHOLD) {
      DelegationCapability(
        level = "ADVANCED",
        maxStakeAmount = 5000L,
        maxSessionDuration = 43200L, // 12 hours
        allowedOperations = List("market", "governance", "contract", "token_transfer")
      )
    } else if (compositeScore >= DELEGATION_BASIC_THRESHOLD) {
      DelegationCapability(
        level = "BASIC",
        maxStakeAmount = 1000L,
        maxSessionDuration = 21600L, // 6 hours
        allowedOperations = List("market", "contract")
      )
    } else {
      DelegationCapability(
        level = "NONE",
        maxStakeAmount = 0L,
        maxSessionDuration = 0L,
        allowedOperations = List.empty
      )
    }

  /**
   * Update reputation based on attestation
   */
  def updateReputationFromAttestation(
    currentState:    AgentReputationState,
    attestationType: String,
    platformWeight:  Double = 1.0 // Weight based on platform reputation
  ): IO[AgentReputationState] = IO {
    val deltaScore = attestationType match {
      case "COMPLETION" => COMPLETION_SCORE * platformWeight
      case "BEHAVIORAL" => BEHAVIORAL_SCORE * platformWeight
      case "VOUCH"      => VOUCH_SCORE * platformWeight
      case "VIOLATION"  => VIOLATION_PENALTY * platformWeight
      case _            => 0.0
    }

    val newBaseReputation = max(0, currentState.baseReputation + deltaScore.toInt)

    val updatedAttestationCount = currentState.attestationCount + 1
    val updatedCompletionCount =
      if (attestationType == "COMPLETION") currentState.completionCount + 1 else currentState.completionCount
    val updatedViolationCount =
      if (attestationType == "VIOLATION") currentState.violationCount + 1 else currentState.violationCount
    val updatedVouchCount = if (attestationType == "VOUCH") currentState.vouchCount + 1 else currentState.vouchCount

    // Recalculate performance component based on completion ratio
    val newPerformance = if (updatedAttestationCount > 0) {
      (updatedCompletionCount.toDouble / updatedAttestationCount.toDouble) * 100.0
    } else currentState.components.performance

    // Update reliability based on violation rate (inverse)
    val violationRate = if (updatedAttestationCount > 0) {
      updatedViolationCount.toDouble / updatedAttestationCount.toDouble
    } else 0.0
    val newReliability = max(0.0, 100.0 - (violationRate * 100.0))

    // Network score based on vouch count with diminishing returns
    val newNetwork = min(100.0, updatedVouchCount * 10.0 - (updatedVouchCount * updatedVouchCount * 0.1))

    val updatedComponents = currentState.components.copy(
      performance = newPerformance,
      reliability = newReliability,
      network = newNetwork,
      lastActivityDays = 0 // Reset activity timer
    )

    val newCompositeScore = calculateCompositeScore(updatedComponents)
    val newDelegationCapability = determineDelegationCapability(newCompositeScore)

    currentState.copy(
      baseReputation = newBaseReputation,
      components = updatedComponents,
      compositeScore = newCompositeScore,
      delegationCapability = newDelegationCapability,
      attestationCount = updatedAttestationCount,
      completionCount = updatedCompletionCount,
      violationCount = updatedViolationCount,
      vouchCount = updatedVouchCount,
      lastUpdated = System.currentTimeMillis()
    )
  }

  /**
   * Apply slashing penalty for delegation misbehavior
   */
  def applySlashingPenalty(
    currentState:      AgentReputationState,
    penaltyPercentage: Double = 0.25 // 25% penalty by default
  ): IO[AgentReputationState] = IO {
    val penaltyAmount = (currentState.baseReputation * penaltyPercentage).toInt
    val newBaseReputation = max(0, currentState.baseReputation - penaltyAmount)

    // Reduce reliability significantly for slashing
    val newReliability = max(0.0, currentState.components.reliability - 30.0)

    val updatedComponents = currentState.components.copy(
      reliability = newReliability,
      lastActivityDays = 0
    )

    val newCompositeScore = calculateCompositeScore(updatedComponents)
    val newDelegationCapability = determineDelegationCapability(newCompositeScore)

    currentState.copy(
      baseReputation = newBaseReputation,
      components = updatedComponents,
      compositeScore = newCompositeScore,
      delegationCapability = newDelegationCapability,
      lastUpdated = System.currentTimeMillis()
    )
  }

  /**
   * Calculate trust score for agent selection (0.0 to 1.0)
   */
  def calculateTrustScore(
    reputationScore:          Double,
    availabilityScore:        Double,
    costEfficiencyScore:      Double,
    specializationMatchScore: Double
  ): Double = {
    val reputationWeight = 0.25
    val availabilityWeight = 0.25
    val costEfficiencyWeight = 0.20
    val specializationWeight = 0.30

    val normalizedReputation = min(1.0, reputationScore / 100.0)

    normalizedReputation * reputationWeight +
    availabilityScore * availabilityWeight +
    costEfficiencyScore * costEfficiencyWeight +
    specializationMatchScore * specializationWeight
  }

  /**
   * Detect potential Sybil agents based on patterns
   */
  def detectSybilPatterns(agents: List[AgentReputationState]): IO[List[String]] = IO {
    val suspiciousAgents = scala.collection.mutable.ListBuffer[String]()

    // Group by similar reputation patterns (potential coordinated behavior)
    val reputationGroups = agents.groupBy(_.compositeScore.toInt)
    reputationGroups.foreach { case (score, agentGroup) =>
      if (agentGroup.size > 5 && score > 50) {
        // Too many agents with identical high scores - suspicious
        suspiciousAgents ++= agentGroup.map(_.address)
      }
    }

    // Detect rapid reputation growth (potential gaming)
    agents.foreach { agent =>
      val ageHours = (System.currentTimeMillis() - agent.lastUpdated) / (1000 * 60 * 60)
      if (agent.compositeScore > 80 && ageHours < 24) {
        // Very high reputation gained in less than 24 hours
        suspiciousAgents += agent.address
      }
    }

    // Detect accounts with high vouch counts but low completion counts
    agents.foreach { agent =>
      if (agent.vouchCount > 10 && agent.completionCount < 3) {
        // More vouches than actual work - potential gaming
        suspiciousAgents += agent.address
      }
    }

    suspiciousAgents.toList.distinct
  }

  /**
   * Initialize reputation state for a new agent
   */
  def initializeAgent(address: String): AgentReputationState = {
    val initialComponents = ReputationComponents(
      performance = 50.0, // Neutral starting performance
      reliability = 80.0, // High initial reliability assumption
      specialization = 30.0, // Low initial specialization
      network = 0.0, // No network initially
      lastActivityDays = 0
    )

    val compositeScore = calculateCompositeScore(initialComponents)
    val delegationCapability = determineDelegationCapability(compositeScore)

    AgentReputationState(
      address = address,
      baseReputation = BASE_REPUTATION,
      components = initialComponents,
      compositeScore = compositeScore,
      delegationCapability = delegationCapability,
      attestationCount = 0,
      completionCount = 0,
      violationCount = 0,
      vouchCount = 0,
      lastUpdated = System.currentTimeMillis()
    )
  }
}
