package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.implicits._
import java.time.{Instant, Duration}
import scala.util.{Try, Success, Failure}

/**
 * Automated penalty system for applying sanctions against detected Sybil attacks and collusion.
 *
 * This system implements graduated penalties including:
 * - Reputation slashing with configurable severity
 * - Stake slashing for economic deterrence
 * - Temporary or permanent account suspension
 * - Reduced reward eligibility
 * - Appeals process for false positives
 */
class PenaltySystem(config: SybilDetectionConfig) {

  /**
   * Apply appropriate penalties based on Sybil detection results.
   */
  def applyPenalties(detectionResult: SybilDetectionResult): IO[PenaltyApplication] =
    for {
      penalties    <- determinePenalties(detectionResult)
      applications <- penalties.traverse(executePenalty)
      notification <- createPenaltyNotification(detectionResult, applications)
    } yield PenaltyApplication(
      detectionId = generateDetectionId(detectionResult),
      affectedAgents = detectionResult.agents,
      appliedPenalties = applications,
      totalSeverity = calculateTotalSeverity(applications),
      notification = notification,
      appealDeadline = calculateAppealDeadline(),
      executedAt = System.currentTimeMillis()
    )

  /**
   * Process an appeal against applied penalties.
   */
  def processAppeal(
    appeal:           PenaltyAppeal,
    reviewerEvidence: List[AppealEvidence]
  ): IO[AppealResult] =
    for {
      validity <- validateAppeal(appeal)
      review <-
        if (validity.isValid) reviewAppeal(appeal, reviewerEvidence)
        else IO.pure(AppealReview.Rejected("Invalid appeal"))
      outcome <- processAppealResult(appeal, review)
    } yield AppealResult(
      appealId = appeal.appealId,
      originalDetectionId = appeal.detectionId,
      reviewOutcome = review,
      adjustedPenalties = outcome.adjustedPenalties,
      compensation = outcome.compensation,
      processedAt = System.currentTimeMillis()
    )

  /**
   * Calculate penalty severity based on detection confidence and evidence.
   */
  def calculatePenaltySeverity(detectionResult: SybilDetectionResult): PenaltySeverity = {
    val baseScore = detectionResult.sybilProbability
    val confidenceMultiplier = detectionResult.confidence
    val evidenceMultiplier = calculateEvidenceWeight(detectionResult.componentScores)
    val historyMultiplier = calculateHistoryMultiplier(detectionResult.agents)

    val severity = baseScore * confidenceMultiplier * evidenceMultiplier * historyMultiplier

    PenaltySeverity(
      score = math.min(1.0, severity),
      category = categorizeSeverity(severity),
      factors = Map(
        "base_probability" -> baseScore,
        "confidence"       -> confidenceMultiplier,
        "evidence_weight"  -> evidenceMultiplier,
        "history_factor"   -> historyMultiplier
      )
    )
  }

  /**
   * Get penalty status for a specific agent.
   */
  def getAgentPenaltyStatus(agentId: AgentId): IO[AgentPenaltyStatus] =
    for {
      activePenalties  <- getActivePenalties(agentId)
      penaltyHistory   <- getPenaltyHistory(agentId)
      reputationImpact <- calculateReputationImpact(activePenalties)
      restrictions     <- calculateCurrentRestrictions(activePenalties)
    } yield AgentPenaltyStatus(
      agentId = agentId,
      activePenalties = activePenalties,
      totalReputationSlash = reputationImpact.totalSlash,
      currentRestrictions = restrictions,
      penaltyHistory = penaltyHistory,
      appealEligible = checkAppealEligibility(activePenalties),
      lastUpdated = System.currentTimeMillis()
    )

  // Penalty Determination Logic

  private def determinePenalties(detectionResult: SybilDetectionResult): IO[List[ScheduledPenalty]] = IO {
    val severity = calculatePenaltySeverity(detectionResult)
    val penalties = scala.collection.mutable.ListBuffer[ScheduledPenalty]()

    detectionResult.agents.foreach { agentId =>
      // Reputation slashing (always applied for confirmed Sybils)
      if (severity.score >= 0.8) {
        val slashPercentage = math.min(config.sybilReputationSlash, severity.score)
        penalties += ScheduledPenalty(
          agentId = agentId,
          penaltyType = PenaltyType.ReputationSlash,
          severity = slashPercentage,
          reason = s"Sybil detection: ${detectionResult.explanation}",
          evidence = detectionResult.componentScores,
          duration = None, // Permanent reputation impact
          effectiveAt = System.currentTimeMillis()
        )
      }

      // Stake slashing for high-confidence detections
      if (severity.score >= 0.9 && detectionResult.confidence >= 0.85) {
        val stakeSlashPercentage = (severity.score - 0.5) * 0.4 // Up to 20% stake slash
        penalties += ScheduledPenalty(
          agentId = agentId,
          penaltyType = PenaltyType.StakeSlash,
          severity = stakeSlashPercentage,
          reason = s"High-confidence Sybil detection: ${detectionResult.explanation}",
          evidence = detectionResult.componentScores,
          duration = None,
          effectiveAt = System.currentTimeMillis()
        )
      }

      // Temporary suspension for coordinated behavior
      if (severity.category == SeverityCategory.High) {
        val suspensionDays = math.min(30, (severity.score * 60).toInt)
        penalties += ScheduledPenalty(
          agentId = agentId,
          penaltyType = PenaltyType.TemporarySuspension,
          severity = 1.0,
          reason = s"Coordinated Sybil behavior detected",
          evidence = detectionResult.componentScores,
          duration = Some(Duration.ofDays(suspensionDays)),
          effectiveAt = System.currentTimeMillis()
        )
      }

      // Permanent ban for extreme cases
      if (severity.score >= 0.95 && detectionResult.confidence >= 0.9) {
        penalties += ScheduledPenalty(
          agentId = agentId,
          penaltyType = PenaltyType.PermanentBan,
          severity = 1.0,
          reason = s"Confirmed large-scale Sybil attack",
          evidence = detectionResult.componentScores,
          duration = None,
          effectiveAt = System.currentTimeMillis()
        )
      }

      // Reduced rewards for lower-confidence cases
      if (severity.score >= 0.6 && severity.score < 0.8) {
        val reductionPercentage = severity.score * 0.5 // Up to 50% reduction
        penalties += ScheduledPenalty(
          agentId = agentId,
          penaltyType = PenaltyType.ReducedRewards,
          severity = reductionPercentage,
          reason = s"Potential Sybil behavior - reduced rewards",
          evidence = detectionResult.componentScores,
          duration = Some(Duration.ofDays(7)), // 1 week penalty
          effectiveAt = System.currentTimeMillis()
        )
      }
    }

    penalties.toList
  }

  private def executePenalty(penalty: ScheduledPenalty): IO[AppliedPenalty] =
    for {
      preState <- getAgentState(penalty.agentId)
      execution <- penalty.penaltyType match {
        case PenaltyType.ReputationSlash     => executeReputationSlash(penalty, preState)
        case PenaltyType.StakeSlash          => executeStakeSlash(penalty, preState)
        case PenaltyType.TemporarySuspension => executeSuspension(penalty, preState)
        case PenaltyType.PermanentBan        => executeBan(penalty, preState)
        case PenaltyType.ReducedRewards      => executeRewardReduction(penalty, preState)
      }
      postState <- getAgentState(penalty.agentId)
      record    <- recordPenaltyExecution(penalty, preState, postState, execution)
    } yield AppliedPenalty(
      penaltyId = generatePenaltyId(),
      scheduledPenalty = penalty,
      executionResult = execution,
      preExecutionState = preState,
      postExecutionState = postState,
      executedAt = System.currentTimeMillis(),
      reversible = execution.reversible
    )

  // Penalty Execution Methods

  private def executeReputationSlash(
    penalty:  ScheduledPenalty,
    preState: AgentState
  ): IO[PenaltyExecution] = IO {
    val currentReputation = preState.reputation
    val slashAmount = currentReputation * penalty.severity
    val newReputation = math.max(0.0, currentReputation - slashAmount)

    // Update reputation in the system (would interface with reputation service)
    updateAgentReputation(penalty.agentId, newReputation)

    PenaltyExecution(
      success = true,
      impact = Map(
        "old_reputation" -> currentReputation.toString,
        "new_reputation" -> newReputation.toString,
        "slash_amount"   -> slashAmount.toString
      ),
      reversible = false, // Reputation slashes are permanent
      error = None
    )
  }

  private def executeStakeSlash(
    penalty:  ScheduledPenalty,
    preState: AgentState
  ): IO[PenaltyExecution] = IO {
    val currentStake = preState.stakedAmount
    val slashAmount = currentStake * penalty.severity
    val newStake = math.max(0.0, currentStake - slashAmount)

    // Update stake in the system
    updateAgentStake(penalty.agentId, newStake, slashAmount)

    PenaltyExecution(
      success = true,
      impact = Map(
        "old_stake"    -> currentStake.toString,
        "new_stake"    -> newStake.toString,
        "slash_amount" -> slashAmount.toString
      ),
      reversible = true, // Stake slashes can be reversed through appeals
      error = None
    )
  }

  private def executeSuspension(
    penalty:  ScheduledPenalty,
    preState: AgentState
  ): IO[PenaltyExecution] = IO {
    val suspensionEnd = penalty.duration
      .map { duration =>
        Instant.ofEpochMilli(penalty.effectiveAt).plus(duration).toEpochMilli
      }
      .getOrElse(Long.MaxValue)

    // Set agent status to suspended
    updateAgentStatus(penalty.agentId, AgentStatus.Suspended, Some(suspensionEnd))

    PenaltyExecution(
      success = true,
      impact = Map(
        "status"         -> "suspended",
        "suspension_end" -> suspensionEnd.toString
      ),
      reversible = true,
      error = None
    )
  }

  private def executeBan(
    penalty:  ScheduledPenalty,
    preState: AgentState
  ): IO[PenaltyExecution] = IO {
    // Set agent status to permanently banned
    updateAgentStatus(penalty.agentId, AgentStatus.Banned, None)

    PenaltyExecution(
      success = true,
      impact = Map(
        "status"    -> "banned",
        "permanent" -> "true"
      ),
      reversible = false, // Permanent bans require manual review
      error = None
    )
  }

  private def executeRewardReduction(
    penalty:  ScheduledPenalty,
    preState: AgentState
  ): IO[PenaltyExecution] = IO {
    val reductionEnd = penalty.duration
      .map { duration =>
        Instant.ofEpochMilli(penalty.effectiveAt).plus(duration).toEpochMilli
      }
      .getOrElse(Long.MaxValue)

    // Set reward multiplier
    updateAgentRewardMultiplier(penalty.agentId, 1.0 - penalty.severity, reductionEnd)

    PenaltyExecution(
      success = true,
      impact = Map(
        "reward_multiplier" -> (1.0 - penalty.severity).toString,
        "reduction_end"     -> reductionEnd.toString
      ),
      reversible = true,
      error = None
    )
  }

  // Appeal Processing

  private def validateAppeal(appeal: PenaltyAppeal): IO[AppealValidation] = IO {
    val isWithinDeadline = appeal.submittedAt <= appeal.appealDeadline
    val hasValidEvidence = appeal.evidence.nonEmpty
    val isAgentEligible = checkAppealEligibility(List(appeal.penaltyId))

    AppealValidation(
      isValid = isWithinDeadline && hasValidEvidence && isAgentEligible,
      reasons = List(
        if (!isWithinDeadline) Some("Appeal submitted after deadline") else None,
        if (!hasValidEvidence) Some("Insufficient evidence provided") else None,
        if (!isAgentEligible) Some("Agent not eligible for appeal") else None
      ).flatten
    )
  }

  private def reviewAppeal(
    appeal:           PenaltyAppeal,
    reviewerEvidence: List[AppealEvidence]
  ): IO[AppealReview] =
    for {
      originalDetection  <- getOriginalDetection(appeal.detectionId)
      evidenceAnalysis   <- analyzeAppealEvidence(appeal.evidence ++ reviewerEvidence)
      falsePositiveScore <- calculateFalsePositiveScore(originalDetection, evidenceAnalysis)
    } yield
      if (falsePositiveScore >= 0.7) {
        AppealReview.Upheld(
          reason = "Strong evidence of false positive detection",
          confidenceScore = falsePositiveScore,
          recommendedCompensation = calculateCompensation(appeal.penaltyId)
        )
      } else if (falsePositiveScore >= 0.4) {
        AppealReview.PartiallyUpheld(
          reason = "Some evidence suggests detection may have been overly harsh",
          confidenceScore = falsePositiveScore,
          penaltyReduction = 0.5
        )
      } else {
        AppealReview.Rejected(
          reason = "Original detection appears valid based on available evidence"
        )
      }

  private def processAppealResult(appeal: PenaltyAppeal, review: AppealReview): IO[AppealOutcome] =
    review match {
      case AppealReview.Upheld(_, _, compensation) =>
        for {
          _ <- reversePenalty(appeal.penaltyId)
          _ <- provideCompensation(appeal.agentId, compensation)
        } yield AppealOutcome(
          adjustedPenalties = List.empty,
          compensation = Some(compensation)
        )

      case AppealReview.PartiallyUpheld(_, _, reduction) =>
        for {
          _ <- reducePenalty(appeal.penaltyId, reduction)
        } yield AppealOutcome(
          adjustedPenalties = List(), // Would contain reduced penalty details
          compensation = None
        )

      case AppealReview.Rejected(_) =>
        IO.pure(
          AppealOutcome(
            adjustedPenalties = List(), // No changes
            compensation = None
          )
        )
    }

  // Utility Functions

  private def generateDetectionId(result: SybilDetectionResult): String =
    s"detect_${result.detectedAt}_${result.agents.map(_.value).mkString("_").hashCode.abs}"

  private def generatePenaltyId(): String =
    s"penalty_${System.currentTimeMillis()}_${scala.util.Random.nextInt(10000)}"

  private def calculateAppealDeadline(): Long =
    System.currentTimeMillis() + (7 * 24 * 3600 * 1000L) // 7 days

  private def calculateTotalSeverity(penalties: List[AppliedPenalty]): Double =
    penalties.map(_.scheduledPenalty.severity).sum / penalties.length

  private def calculateEvidenceWeight(componentScores: Map[String, Double]): Double = {
    val weights = Map(
      "behavioral_similarity"   -> 0.3,
      "hardware_fingerprinting" -> 0.4,
      "network_analysis"        -> 0.2,
      "timing_correlation"      -> 0.1
    )

    componentScores.map { case (component, score) =>
      weights.getOrElse(component, 0.1) * score
    }.sum
  }

  private def calculateHistoryMultiplier(agents: Set[AgentId]): Double =
    // Increase penalty for repeat offenders
    // In production, would check penalty history database
    1.0 // Placeholder

  private def categorizeSeverity(score: Double): SeverityCategory =
    if (score >= 0.8) SeverityCategory.Critical
    else if (score >= 0.6) SeverityCategory.High
    else if (score >= 0.4) SeverityCategory.Medium
    else SeverityCategory.Low

  // Mock implementations for state management
  // In production, these would interface with the actual state management system

  private def getAgentState(agentId: AgentId): IO[AgentState] = IO {
    AgentState(
      agentId = agentId,
      reputation = 1.0,
      stakedAmount = 1000.0,
      status = AgentStatus.Active,
      rewardMultiplier = 1.0,
      lastUpdated = System.currentTimeMillis()
    )
  }

  private def updateAgentReputation(agentId: AgentId, newReputation: Double): Unit = {
    // Would update reputation in database/state
  }

  private def updateAgentStake(agentId: AgentId, newStake: Double, slashAmount: Double): Unit = {
    // Would update stake in database/state and transfer slashed tokens
  }

  private def updateAgentStatus(agentId: AgentId, status: AgentStatus, endTime: Option[Long]): Unit = {
    // Would update agent status in database
  }

  private def updateAgentRewardMultiplier(agentId: AgentId, multiplier: Double, endTime: Long): Unit = {
    // Would update reward calculation parameters
  }

  private def getActivePenalties(agentId: AgentId): IO[List[AppliedPenalty]] = IO(List.empty)
  private def getPenaltyHistory(agentId:  AgentId): IO[List[AppliedPenalty]] = IO(List.empty)

  private def calculateReputationImpact(penalties: List[AppliedPenalty]): IO[ReputationImpact] =
    IO(ReputationImpact(0.0))

  private def calculateCurrentRestrictions(penalties: List[AppliedPenalty]): IO[List[String]] =
    IO(List.empty)
  private def checkAppealEligibility(penalties: List[String]): Boolean = true

  private def createPenaltyNotification(result: SybilDetectionResult, penalties: List[AppliedPenalty]): IO[String] =
    IO(s"Penalties applied for Sybil detection: ${result.explanation}")

  private def recordPenaltyExecution(
    penalty:   ScheduledPenalty,
    pre:       AgentState,
    post:      AgentState,
    execution: PenaltyExecution
  ): IO[Unit] = IO.unit

  private def getOriginalDetection(detectionId: String): IO[SybilDetectionResult] =
    IO(SybilDetectionResult(Set.empty, 0.0, Map.empty, 0.0, RecommendedAction.NoAction, 0L, ""))

  private def analyzeAppealEvidence(evidence: List[AppealEvidence]): IO[EvidenceAnalysis] =
    IO(EvidenceAnalysis(0.5))

  private def calculateFalsePositiveScore(detection: SybilDetectionResult, analysis: EvidenceAnalysis): IO[Double] =
    IO(0.3)
  private def calculateCompensation(penaltyId: String): Double = 100.0
  private def reversePenalty(penaltyId:        String): IO[Unit] = IO.unit
  private def provideCompensation(agentId:     AgentId, amount:   Double): IO[Unit] = IO.unit
  private def reducePenalty(penaltyId:         String, reduction: Double): IO[Unit] = IO.unit
}

// Supporting data types for penalty system

case class PenaltySeverity(
  score:    Double,
  category: SeverityCategory,
  factors:  Map[String, Double]
)

sealed trait SeverityCategory

object SeverityCategory {
  case object Low extends SeverityCategory
  case object Medium extends SeverityCategory
  case object High extends SeverityCategory
  case object Critical extends SeverityCategory
}

case class ScheduledPenalty(
  agentId:     AgentId,
  penaltyType: PenaltyType,
  severity:    Double,
  reason:      String,
  evidence:    Map[String, Double],
  duration:    Option[Duration],
  effectiveAt: Long
)

case class AppliedPenalty(
  penaltyId:          String,
  scheduledPenalty:   ScheduledPenalty,
  executionResult:    PenaltyExecution,
  preExecutionState:  AgentState,
  postExecutionState: AgentState,
  executedAt:         Long,
  reversible:         Boolean
)

case class PenaltyExecution(
  success:    Boolean,
  impact:     Map[String, String],
  reversible: Boolean,
  error:      Option[String]
)

case class PenaltyApplication(
  detectionId:      String,
  affectedAgents:   Set[AgentId],
  appliedPenalties: List[AppliedPenalty],
  totalSeverity:    Double,
  notification:     String,
  appealDeadline:   Long,
  executedAt:       Long
)

case class AgentState(
  agentId:          AgentId,
  reputation:       Double,
  stakedAmount:     Double,
  status:           AgentStatus,
  rewardMultiplier: Double,
  lastUpdated:      Long
)

sealed trait AgentStatus

object AgentStatus {
  case object Active extends AgentStatus
  case object Suspended extends AgentStatus
  case object Banned extends AgentStatus
}

case class AgentPenaltyStatus(
  agentId:              AgentId,
  activePenalties:      List[AppliedPenalty],
  totalReputationSlash: Double,
  currentRestrictions:  List[String],
  penaltyHistory:       List[AppliedPenalty],
  appealEligible:       Boolean,
  lastUpdated:          Long
)

case class PenaltyAppeal(
  appealId:       String,
  agentId:        AgentId,
  detectionId:    String,
  penaltyId:      String,
  evidence:       List[AppealEvidence],
  appealDeadline: Long,
  submittedAt:    Long
)

case class AppealEvidence(
  evidenceType:   String,
  description:    String,
  supportingData: Map[String, String]
)

case class AppealValidation(
  isValid: Boolean,
  reasons: List[String]
)

sealed trait AppealReview

object AppealReview {
  case class Upheld(reason: String, confidenceScore: Double, recommendedCompensation: Double) extends AppealReview
  case class PartiallyUpheld(reason: String, confidenceScore: Double, penaltyReduction: Double) extends AppealReview
  case class Rejected(reason: String) extends AppealReview
}

case class AppealResult(
  appealId:            String,
  originalDetectionId: String,
  reviewOutcome:       AppealReview,
  adjustedPenalties:   List[AppliedPenalty],
  compensation:        Option[Double],
  processedAt:         Long
)

case class AppealOutcome(
  adjustedPenalties: List[AppliedPenalty],
  compensation:      Option[Double]
)

case class ReputationImpact(totalSlash: Double)
case class EvidenceAnalysis(score: Double)
