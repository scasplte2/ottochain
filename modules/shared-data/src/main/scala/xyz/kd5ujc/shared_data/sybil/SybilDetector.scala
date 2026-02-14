package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.implicits._

/**
 * Main orchestrator for Sybil resistance and collusion detection system.
 *
 * This class coordinates all detection components to provide a unified
 * interface for identifying and responding to Sybil attacks across
 * multiple analysis dimensions.
 */
class SybilDetector(
  config:              SybilDetectionConfig,
  behaviorAnalyzer:    BehaviorAnalyzer,
  hardwareAttestation: HardwareAttestation,
  networkAnalyzer:     NetworkAnalyzer,
  penaltySystem:       PenaltySystem
) {

  /**
   * Perform comprehensive Sybil detection analysis on a set of agents.
   */
  def detectSybilAttacks(
    agents:               Set[AgentId],
    behaviorProfiles:     Map[AgentId, BehaviorProfile],
    hardwareFingerprints: Map[AgentId, HardwareFingerprint],
    networkGraph:         NetworkGraph
  ): IO[ComprehensiveDetectionResult] =
    for {
      // Run all detection components in parallel
      behaviorResults <- runBehaviorAnalysis(agents, behaviorProfiles)
      hardwareResults <- runHardwareAnalysis(agents, hardwareFingerprints)
      networkResults  <- runNetworkAnalysis(networkGraph)

      // Combine results from all components
      combinedResults <- combineDetectionResults(agents, behaviorResults, hardwareResults, networkResults)

      // Apply penalties for confirmed detections
      penaltyApplications <- combinedResults
        .filter(_.sybilProbability >= 0.6)
        .traverse { result =>
          penaltySystem.applyPenalties(result).map(Some(_)).recover { case _ => None }
        }
        .map(_.flatten)

      // Generate monitoring alerts
      alerts <- generateSecurityAlerts(combinedResults)

    } yield ComprehensiveDetectionResult(
      detectionId = generateDetectionId(),
      analyzedAgents = agents,
      individualResults = combinedResults,
      suspiciousClusters = extractSuspiciousClusters(behaviorResults, hardwareResults, networkResults),
      appliedPenalties = penaltyApplications,
      securityAlerts = alerts,
      systemHealth = assessSystemHealth(combinedResults),
      detectedAt = System.currentTimeMillis()
    )

  /**
   * Continuous monitoring mode for real-time Sybil detection.
   */
  def startContinuousMonitoring(): IO[MonitoringSession] =
    for {
      sessionId <- IO.pure(generateSessionId())
      _         <- IO(println(s"Starting continuous Sybil monitoring session: $sessionId"))
    } yield MonitoringSession(
      sessionId = sessionId,
      startedAt = System.currentTimeMillis(),
      config = config
    )

  /**
   * Process new agent registration for immediate Sybil screening.
   */
  def screenNewAgent(
    agentId:          AgentId,
    registrationData: AgentRegistrationData
  ): IO[ScreeningResult] =
    for {
      // Generate initial fingerprints
      initialFingerprint <- hardwareAttestation.generateFingerprint()

      // Verify fingerprint authenticity
      (fingerprintValid, confidence) <- hardwareAttestation.verifyFingerprint(initialFingerprint)

      // Check for duplicate hardware
      existingFingerprints <- getExistingFingerprints()
      hardwareSimilarities <- existingFingerprints.toList.traverse { case (existingAgent, fingerprint) =>
        IO.pure((existingAgent, hardwareAttestation.calculateHardwareSimilarity(initialFingerprint, fingerprint)))
      }

      // Check for suspicious registration patterns
      registrationFlags <- analyzeRegistrationPatterns(registrationData)

      // Calculate initial risk score
      riskScore = calculateInitialRiskScore(confidence, hardwareSimilarities, registrationFlags)

    } yield ScreeningResult(
      agentId = agentId,
      riskLevel = categorizeRiskLevel(riskScore),
      riskScore = riskScore,
      flags = registrationFlags,
      hardwareFingerprint = initialFingerprint,
      recommendedAction = determineScreeningAction(riskScore),
      screenedAt = System.currentTimeMillis()
    )

  /**
   * Generate detailed analysis report for a specific detection.
   */
  def generateAnalysisReport(detectionId: String): IO[DetectionReport] =
    for {
      detectionData    <- getDetectionData(detectionId)
      behaviorAnalysis <- generateBehaviorReport(detectionData)
      hardwareAnalysis <- generateHardwareReport(detectionData)
      networkAnalysis  <- generateNetworkReport(detectionData)
      riskAssessment   <- generateRiskAssessment(detectionData)
      recommendations  <- generateRecommendations(detectionData)
    } yield DetectionReport(
      detectionId = detectionId,
      executiveSummary = generateExecutiveSummary(detectionData),
      behaviorAnalysis = behaviorAnalysis,
      hardwareAnalysis = hardwareAnalysis,
      networkAnalysis = networkAnalysis,
      riskAssessment = riskAssessment,
      recommendations = recommendations,
      generatedAt = System.currentTimeMillis()
    )

  /**
   * Update system configuration and recalibrate detection thresholds.
   */
  def updateConfiguration(newConfig: SybilDetectionConfig): IO[ConfigurationUpdate] =
    for {
      validationResult <- validateConfiguration(newConfig)
      _ <-
        if (validationResult.isValid) {
          IO.pure(updateSystemConfig(newConfig))
        } else {
          IO.raiseError(
            new IllegalArgumentException(s"Invalid configuration: ${validationResult.errors.mkString(", ")}")
          )
        }
      recalibration <- recalibrateThresholds(newConfig)
    } yield ConfigurationUpdate(
      oldConfig = config,
      newConfig = newConfig,
      recalibrationResults = recalibration,
      updatedAt = System.currentTimeMillis()
    )

  // Component Analysis Methods

  private def runBehaviorAnalysis(
    agents:   Set[AgentId],
    profiles: Map[AgentId, BehaviorProfile]
  ): IO[List[SuspiciousCluster]] = {
    val relevantProfiles = agents.flatMap(id => profiles.get(id)).toList
    behaviorAnalyzer.detectSuspiciousGroups(relevantProfiles)
  }

  private def runHardwareAnalysis(
    agents:       Set[AgentId],
    fingerprints: Map[AgentId, HardwareFingerprint]
  ): IO[List[SuspiciousCluster]] = {
    val relevantFingerprints = agents.flatMap(id => fingerprints.get(id).map(id -> _)).toMap
    hardwareAttestation.detectHardwareSharing(relevantFingerprints)
  }

  private def runNetworkAnalysis(graph: NetworkGraph): IO[List[SuspiciousCluster]] =
    networkAnalyzer.analyzeNetwork(graph)

  private def combineDetectionResults(
    agents:           Set[AgentId],
    behaviorClusters: List[SuspiciousCluster],
    hardwareClusters: List[SuspiciousCluster],
    networkClusters:  List[SuspiciousCluster]
  ): IO[List[SybilDetectionResult]] = IO {

    agents.map { agentId =>
      // Find all clusters containing this agent
      val behaviorCluster = behaviorClusters.find(_.agents.contains(agentId))
      val hardwareCluster = hardwareClusters.find(_.agents.contains(agentId))
      val networkCluster = networkClusters.find(_.agents.contains(agentId))

      // Calculate component scores
      val componentScores = Map(
        "behavior_similarity"     -> behaviorCluster.map(_.suspicionScore).getOrElse(0.0),
        "hardware_fingerprinting" -> hardwareCluster.map(_.suspicionScore).getOrElse(0.0),
        "network_analysis"        -> networkCluster.map(_.suspicionScore).getOrElse(0.0)
      )

      // Calculate overall Sybil probability
      val sybilProbability = calculateOverallProbability(componentScores)

      // Calculate confidence based on evidence convergence
      val confidence = calculateConfidence(componentScores, behaviorCluster, hardwareCluster, networkCluster)

      // Determine recommended action
      val recommendedAction = determineAction(sybilProbability, confidence, componentScores)

      // Generate explanation
      val explanation = generateExplanation(agentId, componentScores, behaviorCluster, hardwareCluster, networkCluster)

      SybilDetectionResult(
        agents = Set(agentId),
        sybilProbability = sybilProbability,
        componentScores = componentScores,
        confidence = confidence,
        recommendedAction = recommendedAction,
        detectedAt = System.currentTimeMillis(),
        explanation = explanation
      )
    }.toList
  }

  private def calculateOverallProbability(componentScores: Map[String, Double]): Double = {
    val weights = Map(
      "behavior_similarity"     -> 0.35,
      "hardware_fingerprinting" -> 0.40,
      "network_analysis"        -> 0.25
    )

    componentScores.map { case (component, score) =>
      weights.getOrElse(component, 0.0) * score
    }.sum
  }

  private def calculateConfidence(
    componentScores: Map[String, Double],
    behaviorCluster: Option[SuspiciousCluster],
    hardwareCluster: Option[SuspiciousCluster],
    networkCluster:  Option[SuspiciousCluster]
  ): Double = {
    val evidenceCount = List(behaviorCluster, hardwareCluster, networkCluster).count(_.isDefined)
    val scoreVariance = if (componentScores.nonEmpty) {
      val values = componentScores.values.toList
      val mean = values.sum / values.length
      val variance = values.map(v => math.pow(v - mean, 2)).sum / values.length
      1.0 - math.min(1.0, variance / 0.25) // Normalize variance
    } else 0.0

    // Confidence increases with evidence count and decreases with score variance
    val evidenceConfidence = evidenceCount.toDouble / 3.0
    (evidenceConfidence + scoreVariance) / 2.0
  }

  private def determineAction(
    probability: Double,
    confidence:  Double,
    scores:      Map[String, Double]
  ): RecommendedAction =
    if (probability >= 0.9 && confidence >= 0.8) {
      RecommendedAction.ApplyPenalty(PenaltyType.StakeSlash, 0.9)
    } else if (probability >= 0.8 && confidence >= 0.7) {
      RecommendedAction.ApplyPenalty(PenaltyType.ReputationSlash, 0.8)
    } else if (probability >= 0.6) {
      RecommendedAction.RequireAdditionalVerification
    } else if (probability >= 0.4) {
      RecommendedAction.IncreaseMonitoring
    } else {
      RecommendedAction.NoAction
    }

  private def generateExplanation(
    agentId:         AgentId,
    scores:          Map[String, Double],
    behaviorCluster: Option[SuspiciousCluster],
    hardwareCluster: Option[SuspiciousCluster],
    networkCluster:  Option[SuspiciousCluster]
  ): String = {
    val evidencePoints = scala.collection.mutable.ListBuffer[String]()

    behaviorCluster.foreach { cluster =>
      evidencePoints += s"Behavioral similarity detected with ${cluster.agents.size - 1} other agents (score: ${scores("behavior_similarity")})"
    }

    hardwareCluster.foreach { cluster =>
      evidencePoints += s"Hardware fingerprint sharing detected (score: ${scores("hardware_fingerprinting")})"
    }

    networkCluster.foreach { cluster =>
      evidencePoints += s"Coordinated network activity detected (score: ${scores("network_analysis")})"
    }

    if (evidencePoints.isEmpty) {
      s"Agent $agentId shows no significant Sybil indicators"
    } else {
      s"Agent $agentId flagged for: ${evidencePoints.mkString("; ")}"
    }
  }

  // Utility Methods

  private def extractSuspiciousClusters(
    behaviorClusters: List[SuspiciousCluster],
    hardwareClusters: List[SuspiciousCluster],
    networkClusters:  List[SuspiciousCluster]
  ): List[SuspiciousCluster] =
    (behaviorClusters ++ hardwareClusters ++ networkClusters)
      .filter(_.suspicionScore >= 0.6)
      .sortBy(-_.suspicionScore)

  private def generateSecurityAlerts(results: List[SybilDetectionResult]): IO[List[SecurityAlert]] = IO {
    results.filter(_.sybilProbability >= 0.7).map { result =>
      SecurityAlert(
        alertId = generateAlertId(),
        severity = if (result.sybilProbability >= 0.9) AlertSeverity.Critical else AlertSeverity.High,
        message = s"Potential Sybil attack detected: ${result.explanation}",
        affectedAgents = result.agents,
        detectionScore = result.sybilProbability,
        recommendedAction = result.recommendedAction.toString,
        alertedAt = System.currentTimeMillis()
      )
    }
  }

  private def assessSystemHealth(results: List[SybilDetectionResult]): SystemHealthAssessment = {
    val totalAgents = results.length
    val flaggedAgents = results.count(_.sybilProbability >= 0.6)
    val criticalThreats = results.count(_.sybilProbability >= 0.9)

    val healthScore = if (totalAgents > 0) {
      1.0 - (flaggedAgents.toDouble / totalAgents)
    } else 1.0

    SystemHealthAssessment(
      overallScore = healthScore,
      totalAgentsAnalyzed = totalAgents,
      flaggedAgents = flaggedAgents,
      criticalThreats = criticalThreats,
      systemStatus = if (healthScore >= 0.9) "Healthy" else if (healthScore >= 0.7) "Warning" else "Critical",
      lastAssessment = System.currentTimeMillis()
    )
  }

  // Mock implementations for supporting functions

  private def generateDetectionId(): String =
    s"detect_${System.currentTimeMillis()}_${scala.util.Random.nextInt(10000)}"
  private def generateSessionId(): String = s"session_${System.currentTimeMillis()}_${scala.util.Random.nextInt(10000)}"
  private def generateAlertId(): String = s"alert_${System.currentTimeMillis()}_${scala.util.Random.nextInt(10000)}"

  private def getExistingFingerprints(): IO[Map[AgentId, HardwareFingerprint]] = IO(Map.empty)

  private def analyzeRegistrationPatterns(data: AgentRegistrationData): IO[List[String]] = IO {
    val flags = scala.collection.mutable.ListBuffer[String]()

    if (data.registrationTimestamp > System.currentTimeMillis() - 3600000) {
      flags += "very_recent_registration"
    }

    if (data.providedStake < config.minimumStake) {
      flags += "insufficient_stake"
    }

    flags.toList
  }

  private def calculateInitialRiskScore(
    confidence:   Double,
    similarities: List[(AgentId, Double)],
    flags:        List[String]
  ): Double = {
    val baseScore = 1.0 - confidence
    val similarityPenalty = similarities.map(_._2).maxOption.getOrElse(0.0)
    val flagPenalty = flags.length * 0.1

    math.min(1.0, baseScore + similarityPenalty + flagPenalty)
  }

  private def categorizeRiskLevel(score: Double): RiskLevel =
    if (score >= 0.8) RiskLevel.High
    else if (score >= 0.5) RiskLevel.Medium
    else RiskLevel.Low

  private def determineScreeningAction(score: Double): String =
    if (score >= 0.8) "manual_review_required"
    else if (score >= 0.5) "additional_verification"
    else "automatic_approval"

  private def getDetectionData(detectionId: String): IO[DetectionData] =
    IO(DetectionData(detectionId, List.empty, System.currentTimeMillis()))

  private def generateBehaviorReport(data: DetectionData): IO[String] =
    IO("Behavioral analysis completed - no significant anomalies detected")

  private def generateHardwareReport(data: DetectionData): IO[String] =
    IO("Hardware fingerprinting analysis completed")

  private def generateNetworkReport(data: DetectionData): IO[String] =
    IO("Network analysis completed")

  private def generateRiskAssessment(data: DetectionData): IO[String] =
    IO("Risk assessment: Low to moderate Sybil threat detected")

  private def generateRecommendations(data: DetectionData): IO[List[String]] =
    IO(List("Continue monitoring", "Review detection thresholds"))

  private def generateExecutiveSummary(data: DetectionData): String =
    s"Detection ${data.detectionId} completed analysis of ${data.results.length} agents"

  private def validateConfiguration(config: SybilDetectionConfig): IO[ConfigValidationResult] =
    IO(ConfigValidationResult(isValid = true, errors = List.empty))

  private def updateSystemConfig(config: SybilDetectionConfig): Unit = {
    // Update system configuration
  }

  private def recalibrateThresholds(config: SybilDetectionConfig): IO[List[String]] =
    IO(List("Behavioral similarity threshold updated", "Hardware fingerprinting threshold updated"))
}

// Supporting data types

case class ComprehensiveDetectionResult(
  detectionId:        String,
  analyzedAgents:     Set[AgentId],
  individualResults:  List[SybilDetectionResult],
  suspiciousClusters: List[SuspiciousCluster],
  appliedPenalties:   List[PenaltyApplication],
  securityAlerts:     List[SecurityAlert],
  systemHealth:       SystemHealthAssessment,
  detectedAt:         Long
)

case class MonitoringSession(
  sessionId: String,
  startedAt: Long,
  config:    SybilDetectionConfig
)

case class AgentRegistrationData(
  agentId:               AgentId,
  providedStake:         Double,
  registrationTimestamp: Long,
  ipAddress:             String,
  userAgent:             String
)

case class ScreeningResult(
  agentId:             AgentId,
  riskLevel:           RiskLevel,
  riskScore:           Double,
  flags:               List[String],
  hardwareFingerprint: HardwareFingerprint,
  recommendedAction:   String,
  screenedAt:          Long
)

sealed trait RiskLevel

object RiskLevel {
  case object Low extends RiskLevel
  case object Medium extends RiskLevel
  case object High extends RiskLevel
}

case class DetectionReport(
  detectionId:      String,
  executiveSummary: String,
  behaviorAnalysis: String,
  hardwareAnalysis: String,
  networkAnalysis:  String,
  riskAssessment:   String,
  recommendations:  List[String],
  generatedAt:      Long
)

case class ConfigurationUpdate(
  oldConfig:            SybilDetectionConfig,
  newConfig:            SybilDetectionConfig,
  recalibrationResults: List[String],
  updatedAt:            Long
)

case class SecurityAlert(
  alertId:           String,
  severity:          AlertSeverity,
  message:           String,
  affectedAgents:    Set[AgentId],
  detectionScore:    Double,
  recommendedAction: String,
  alertedAt:         Long
)

sealed trait AlertSeverity

object AlertSeverity {
  case object Low extends AlertSeverity
  case object Medium extends AlertSeverity
  case object High extends AlertSeverity
  case object Critical extends AlertSeverity
}

case class SystemHealthAssessment(
  overallScore:        Double,
  totalAgentsAnalyzed: Int,
  flaggedAgents:       Int,
  criticalThreats:     Int,
  systemStatus:        String,
  lastAssessment:      Long
)

case class DetectionData(
  detectionId: String,
  results:     List[SybilDetectionResult],
  timestamp:   Long
)

case class ConfigValidationResult(
  isValid: Boolean,
  errors:  List[String]
)
