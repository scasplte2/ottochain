package xyz.kd5ujc.shared_data.identity.agent

import cats.effect.IO
import cats.syntax.all._
import java.util.UUID

/**
 * OttoChain Identity Service Integration for Agent Delegation
 *
 * Integrates agent reputation and delegation systems with existing OttoChain identity infrastructure.
 * Provides unified interface for agent registration, verification, and delegation authority management.
 *
 * Features:
 * - Agent registration with cryptographic identity binding
 * - Platform verification and attestation integration
 * - Sybil resistance through multi-factor verification
 * - Integration with existing identity service state machines
 * - Anti-gaming measures and fraud detection
 */
object IdentityIntegration {

  /**
   * Platform verification levels
   */
  sealed trait PlatformVerificationLevel
  case object Unverified extends PlatformVerificationLevel
  case object BasicVerified extends PlatformVerificationLevel
  case object FullyVerified extends PlatformVerificationLevel

  /**
   * Agent registration status
   */
  sealed trait AgentRegistrationStatus
  case object PendingVerification extends AgentRegistrationStatus
  case object Active extends AgentRegistrationStatus
  case object Suspended extends AgentRegistrationStatus
  case object Revoked extends AgentRegistrationStatus

  /**
   * Platform identity link with verification
   */
  case class PlatformIdentityLink(
    platform:          String,
    platformUserId:    String,
    platformUsername:  String,
    linkedAt:          Long,
    verificationLevel: PlatformVerificationLevel,
    verificationScore: Double,
    attestationCount:  Int = 0
  )

  /**
   * Complete agent identity state
   */
  case class AgentIdentityState(
    address:               String,
    publicKey:             String,
    displayName:           String,
    registrationStatus:    AgentRegistrationStatus,
    platformLinks:         List[PlatformIdentityLink],
    reputationState:       ReputationScoring.AgentReputationState,
    delegationState:       DelegationManager.AgentDelegationState,
    sybilResistanceScore:  Double,
    registeredAt:          Long,
    lastVerificationAt:    Long,
    verificationExpiresAt: Long
  )

  /**
   * Sybil resistance verification requirements
   */
  case class SybilResistanceRequirements(
    minReputationThreshold:     Int = 25,
    minAttestationsRequired:    Int = 3,
    minPlatformLinks:           Int = 2,
    minStakeBond:               Long = 100L,
    verificationPeriodDays:     Int = 90,
    requireHardwareAttestation: Boolean = false
  )

  /**
   * Register new agent with identity binding
   */
  def registerAgent(
    address:             String,
    publicKey:           String,
    displayName:         String,
    initialPlatformLink: Option[PlatformIdentityLink] = None
  ): IO[AgentIdentityState] = IO {
    val currentTime = System.currentTimeMillis() / 1000

    // Initialize reputation state
    val reputationState = ReputationScoring.initializeAgent(address)

    // Initialize delegation state
    val delegationState = DelegationManager.initializeAgentDelegationState(address, reputationState)

    // Add platform link if provided
    val platformLinks = initialPlatformLink.toList

    AgentIdentityState(
      address = address,
      publicKey = publicKey,
      displayName = displayName,
      registrationStatus = PendingVerification,
      platformLinks = platformLinks,
      reputationState = reputationState,
      delegationState = delegationState,
      sybilResistanceScore = calculateInitialSybilResistanceScore(platformLinks),
      registeredAt = currentTime,
      lastVerificationAt = currentTime,
      verificationExpiresAt = currentTime + (90 * 24 * 60 * 60) // 90 days
    )
  }

  /**
   * Add platform identity link with verification
   */
  def addPlatformLink(
    agentState:        AgentIdentityState,
    platform:          String,
    platformUserId:    String,
    platformUsername:  String,
    verificationProof: String
  ): IO[AgentIdentityState] =
    for {
      verificationLevel <- verifyPlatformIdentity(platform, platformUserId, verificationProof)
      verificationScore <- calculatePlatformVerificationScore(platform, verificationLevel)

      newPlatformLink = PlatformIdentityLink(
        platform = platform,
        platformUserId = platformUserId,
        platformUsername = platformUsername,
        linkedAt = System.currentTimeMillis() / 1000,
        verificationLevel = verificationLevel,
        verificationScore = verificationScore
      )

      updatedPlatformLinks = agentState.platformLinks :+ newPlatformLink
      updatedSybilScore <- calculateSybilResistanceScore(updatedPlatformLinks, agentState.reputationState)

      // Update reputation based on new platform link
      updatedReputationState <- ReputationScoring.updateReputationFromAttestation(
        agentState.reputationState,
        "BEHAVIORAL", // Platform linking is behavioral proof
        verificationScore / 100.0 // Weight by verification score
      )

      // Update delegation state with new reputation
      updatedDelegationState = agentState.delegationState.copy(
        reputationState = updatedReputationState,
        delegationLevel = updatedReputationState.delegationCapability.level
      )

    } yield agentState.copy(
      platformLinks = updatedPlatformLinks,
      reputationState = updatedReputationState,
      delegationState = updatedDelegationState,
      sybilResistanceScore = updatedSybilScore,
      lastVerificationAt = System.currentTimeMillis() / 1000
    )

  /**
   * Verify platform identity (simplified - would integrate with actual platform APIs)
   */
  def verifyPlatformIdentity(
    platform:          String,
    platformUserId:    String,
    verificationProof: String
  ): IO[PlatformVerificationLevel] = IO {
    platform match {
      case "github" =>
        // Verify GitHub identity through commit signing or public key verification
        if (verificationProof.startsWith("github_verified_")) FullyVerified else BasicVerified
      case "discord" =>
        // Verify Discord identity through bot interaction
        if (verificationProof.length > 20) BasicVerified else Unverified
      case "telegram" =>
        // Verify Telegram through bot verification
        if (verificationProof.startsWith("tg_")) BasicVerified else Unverified
      case "twitter" =>
        // Verify Twitter through tweet verification
        if (verificationProof.startsWith("tw_")) BasicVerified else Unverified
      case _ => Unverified
    }
  }

  /**
   * Calculate platform verification score
   */
  def calculatePlatformVerificationScore(
    platform:          String,
    verificationLevel: PlatformVerificationLevel
  ): IO[Double] = IO {
    val platformWeights = Map(
      "github"   -> 1.0, // Highest weight - code commits prove identity
      "telegram" -> 0.8, // High weight - active communication
      "discord"  -> 0.7, // Medium-high weight - community participation
      "twitter"  -> 0.6, // Medium weight - social proof
      "custom"   -> 0.3 // Low weight - unknown verification quality
    )

    val levelMultipliers = Map(
      FullyVerified -> 1.0,
      BasicVerified -> 0.7,
      Unverified    -> 0.0
    )

    val baseWeight = platformWeights.getOrElse(platform, 0.5)
    val levelMultiplier = levelMultipliers(verificationLevel)

    (baseWeight * levelMultiplier * 100.0) // Scale to 0-100
  }

  /**
   * Calculate Sybil resistance score
   */
  def calculateSybilResistanceScore(
    platformLinks:   List[PlatformIdentityLink],
    reputationState: ReputationScoring.AgentReputationState
  ): IO[Double] = IO {
    val platformDiversityScore = calculatePlatformDiversityScore(platformLinks)
    val verificationQualityScore = calculateVerificationQualityScore(platformLinks)
    val temporalConsistencyScore = calculateTemporalConsistencyScore(platformLinks)
    val reputationConsistencyScore = calculateReputationConsistencyScore(reputationState)

    // Weighted combination of Sybil resistance factors
    platformDiversityScore * 0.30 +
    verificationQualityScore * 0.25 +
    temporalConsistencyScore * 0.20 +
    reputationConsistencyScore * 0.25
  }

  /**
   * Calculate platform diversity score (prevents single-platform gaming)
   */
  def calculatePlatformDiversityScore(platformLinks: List[PlatformIdentityLink]): Double = {
    val uniquePlatforms = platformLinks.map(_.platform).distinct.length
    val maxPlatforms = 5 // Reasonable maximum for diversity bonus

    Math.min(1.0, uniquePlatforms.toDouble / maxPlatforms.toDouble) * 100.0
  }

  /**
   * Calculate verification quality score
   */
  def calculateVerificationQualityScore(platformLinks: List[PlatformIdentityLink]): Double = {
    if (platformLinks.isEmpty) return 0.0

    val totalVerificationScore = platformLinks.map(_.verificationScore).sum
    val maxPossibleScore = platformLinks.length * 100.0

    (totalVerificationScore / maxPossibleScore) * 100.0
  }

  /**
   * Calculate temporal consistency score (prevents rapid account creation)
   */
  def calculateTemporalConsistencyScore(platformLinks: List[PlatformIdentityLink]): Double = {
    if (platformLinks.isEmpty) return 0.0

    val currentTime = System.currentTimeMillis() / 1000
    val accountAges = platformLinks.map(link => currentTime - link.linkedAt)
    val avgAge = accountAges.sum.toDouble / accountAges.length.toDouble

    // Accounts older than 30 days get full score
    val thirtyDays = 30 * 24 * 60 * 60
    Math.min(1.0, avgAge / thirtyDays) * 100.0
  }

  /**
   * Calculate reputation consistency score (detects artificial inflation)
   */
  def calculateReputationConsistencyScore(reputationState: ReputationScoring.AgentReputationState): Double = {
    val completionRate = if (reputationState.attestationCount > 0) {
      reputationState.completionCount.toDouble / reputationState.attestationCount.toDouble
    } else 0.5 // Neutral for new agents

    val vouchToCompletionRatio = if (reputationState.completionCount > 0) {
      reputationState.vouchCount.toDouble / reputationState.completionCount.toDouble
    } else 0.0

    // Penalize high vouch-to-completion ratios (potential gaming)
    val vouchPenalty = if (vouchToCompletionRatio > 2.0) 0.5 else 1.0

    // Penalize very high violation rates
    val violationRate = if (reputationState.attestationCount > 0) {
      reputationState.violationCount.toDouble / reputationState.attestationCount.toDouble
    } else 0.0
    val violationPenalty = Math.max(0.1, 1.0 - (violationRate * 2.0))

    completionRate * vouchPenalty * violationPenalty * 100.0
  }

  /**
   * Calculate initial Sybil resistance score for new agents
   */
  def calculateInitialSybilResistanceScore(platformLinks: List[PlatformIdentityLink]): Double =
    if (platformLinks.isEmpty) 10.0 // Low but not zero for new agents
    else {
      val diversityScore = calculatePlatformDiversityScore(platformLinks)
      val qualityScore = calculateVerificationQualityScore(platformLinks)

      // New agents get reduced scores until they build reputation
      (diversityScore * 0.3 + qualityScore * 0.7) * 0.5 // 50% reduction for new agents
    }

  /**
   * Check if agent meets Sybil resistance requirements
   */
  def checkSybilResistanceRequirements(
    agentState:   AgentIdentityState,
    requirements: SybilResistanceRequirements
  ): IO[ValidationResult] = IO {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()

    // Check reputation threshold
    if (agentState.reputationState.compositeScore < requirements.minReputationThreshold) {
      errors += s"Reputation ${agentState.reputationState.compositeScore} below threshold ${requirements.minReputationThreshold}"
    }

    // Check attestation count
    if (agentState.reputationState.attestationCount < requirements.minAttestationsRequired) {
      errors += s"Attestations ${agentState.reputationState.attestationCount} below required ${requirements.minAttestationsRequired}"
    }

    // Check platform link diversity
    val verifiedLinks = agentState.platformLinks.filter(_.verificationLevel != Unverified)
    if (verifiedLinks.length < requirements.minPlatformLinks) {
      errors += s"Verified platform links ${verifiedLinks.length} below required ${requirements.minPlatformLinks}"
    }

    // Check stake bond
    if (agentState.delegationState.totalStakeBonded < requirements.minStakeBond) {
      errors += s"Bonded stake ${agentState.delegationState.totalStakeBonded} below required ${requirements.minStakeBond}"
    }

    // Check verification expiry
    val currentTime = System.currentTimeMillis() / 1000
    if (currentTime > agentState.verificationExpiresAt) {
      errors += "Agent verification has expired and needs renewal"
    }

    // Warning for low Sybil resistance score
    if (agentState.sybilResistanceScore < 50.0) {
      warnings += s"Low Sybil resistance score: ${agentState.sybilResistanceScore.toInt}%"
    }

    DelegationManager.ValidationResult(
      isValid = errors.isEmpty,
      errors = errors.toList,
      warnings = warnings.toList
    )
  }

  /**
   * Process attestation and update all relevant state
   */
  def processAttestation(
    agentState:      AgentIdentityState,
    attestationType: String,
    platformId:      String,
    evidence:        String
  ): IO[AgentIdentityState] =
    for {
      // Find platform link for weight calculation
      platformWeight <- IO {
        agentState.platformLinks
          .find(_.platform == platformId)
          .map(_.verificationScore / 100.0)
          .getOrElse(0.5) // Default weight for unknown platforms
      }

      // Update reputation
      updatedReputationState <- ReputationScoring.updateReputationFromAttestation(
        agentState.reputationState,
        attestationType,
        platformWeight
      )

      // Update delegation state with new reputation
      updatedDelegationState = agentState.delegationState.copy(
        reputationState = updatedReputationState,
        delegationLevel = updatedReputationState.delegationCapability.level
      )

      // Update platform link attestation count
      updatedPlatformLinks = agentState.platformLinks.map { link =>
        if (link.platform == platformId) {
          link.copy(attestationCount = link.attestationCount + 1)
        } else link
      }

      // Recalculate Sybil resistance score
      updatedSybilScore <- calculateSybilResistanceScore(updatedPlatformLinks, updatedReputationState)

    } yield agentState.copy(
      platformLinks = updatedPlatformLinks,
      reputationState = updatedReputationState,
      delegationState = updatedDelegationState,
      sybilResistanceScore = updatedSybilScore,
      lastVerificationAt = System.currentTimeMillis() / 1000
    )

  /**
   * Renew agent verification (required every 90 days)
   */
  def renewAgentVerification(agentState: AgentIdentityState): IO[AgentIdentityState] =
    for {
      // Re-verify all platform links
      updatedPlatformLinks <- agentState.platformLinks.traverse { link =>
        verifyPlatformIdentity(link.platform, link.platformUserId, "renewal_check").map { verificationLevel =>
          link.copy(
            verificationLevel = verificationLevel,
            linkedAt = System.currentTimeMillis() / 1000 // Update timestamp
          )
        }
      }

      // Recalculate Sybil resistance score with updated links
      updatedSybilScore <- calculateSybilResistanceScore(updatedPlatformLinks, agentState.reputationState)

      currentTime = System.currentTimeMillis() / 1000

    } yield agentState.copy(
      platformLinks = updatedPlatformLinks,
      sybilResistanceScore = updatedSybilScore,
      lastVerificationAt = currentTime,
      verificationExpiresAt = currentTime + (90 * 24 * 60 * 60) // Extend for another 90 days
    )

  /**
   * Get comprehensive agent identity summary
   */
  def getAgentIdentitySummary(agentState: AgentIdentityState): IO[Map[String, Any]] =
    DelegationManager.getAgentDelegationStats(agentState.delegationState).map { delegationStats =>
      Map(
        "address"                -> agentState.address,
        "display_name"           -> agentState.displayName,
        "registration_status"    -> agentState.registrationStatus.toString,
        "reputation_score"       -> agentState.reputationState.compositeScore,
        "delegation_level"       -> agentState.delegationState.delegationLevel,
        "sybil_resistance_score" -> agentState.sybilResistanceScore,
        "platform_links" -> agentState.platformLinks.map { link =>
          Map(
            "platform"           -> link.platform,
            "username"           -> link.platformUsername,
            "verification_level" -> link.verificationLevel.toString,
            "verification_score" -> link.verificationScore,
            "attestation_count"  -> link.attestationCount
          )
        },
        "delegation_stats"        -> delegationStats,
        "registered_at"           -> agentState.registeredAt,
        "verification_expires_at" -> agentState.verificationExpiresAt
      )
    }

  /**
   * Default Sybil resistance requirements
   */
  val DefaultSybilResistanceRequirements = SybilResistanceRequirements()

  /**
   * Validation result type alias for consistency
   */
  type ValidationResult = DelegationManager.ValidationResult
  val ValidationResult = DelegationManager.ValidationResult
}
