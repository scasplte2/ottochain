package xyz.kd5ujc.shared_data.sybil

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/**
 * Core types for Sybil resistance and collusion detection system.
 *
 * This implements multi-layer defense against identity-based attacks including:
 * - Economic barriers (OTTO stake requirements)
 * - Hardware attestation (TEE/SGX fingerprinting)
 * - Behavioral analysis (response patterns, timing)
 * - Graph analysis (coordination detection)
 * - Social verification (creator identity, community endorsements)
 */

/** Unique identifier for agents in the system */
case class AgentId(value: String) extends AnyVal

/** Hardware fingerprint for device identification */
case class HardwareFingerprint(
  /** TEE/SGX attestation signature */
  teeAttestation: Option[String],
  /** CPU model and features */
  cpuSignature: String,
  /** System configuration hash */
  systemHash: String,
  /** Timestamp of fingerprint generation */
  timestamp: Long
)

/** Behavioral patterns extracted from agent interactions */
case class BehaviorProfile(
  /** Agent identifier */
  agentId: AgentId,
  /** Average response time in milliseconds */
  avgResponseTime: Double,
  /** Standard deviation of response times */
  responseTimeStdDev: Double,
  /** Typing patterns (inter-keystroke timing) */
  typingPattern: List[Double],
  /** Common words/phrases used */
  vocabularyFingerprint: Set[String],
  /** Transaction patterns and timing */
  transactionPattern: TransactionPattern,
  /** Activity time distribution (hour of day) */
  activityPattern: Map[Int, Double],
  /** Last update timestamp */
  lastUpdated: Long
)

/** Transaction pattern analysis */
case class TransactionPattern(
  /** Average time between transactions */
  avgTxInterval: Double,
  /** Typical transaction amounts */
  typicalAmounts: List[Double],
  /** Gas price preferences */
  gasPreferences: GasProfile,
  /** Contract interaction patterns */
  contractUsage: Map[String, Int]
)

/** Gas usage profile for behavior analysis */
case class GasProfile(
  /** Preferred gas price range */
  preferredGasPrice: (Double, Double),
  /** Gas limit patterns */
  gasLimitPreferences: List[Long],
  /** MEV sensitivity (willingness to pay higher fees) */
  mevSensitivity: Double
)

/** Network graph analysis for coordination detection */
case class NetworkGraph(
  /** Nodes in the network (agents) */
  nodes: Set[AgentId],
  /** Edges representing interactions */
  edges: Set[NetworkEdge],
  /** Computed clusters of suspicious activity */
  suspiciousClusters: List[SuspiciousCluster]
)

/** Edge in the agent interaction network */
case class NetworkEdge(
  /** Source agent */
  from: AgentId,
  /** Destination agent */
  to: AgentId,
  /** Interaction strength (frequency, value) */
  weight: Double,
  /** Type of interaction */
  interactionType: InteractionType,
  /** Last interaction timestamp */
  lastInteraction: Long
)

/** Types of interactions between agents */
sealed trait InteractionType

object InteractionType {
  case object Transaction extends InteractionType
  case object Delegation extends InteractionType
  case object CoVoting extends InteractionType
  case object SimultaneousAction extends InteractionType
  case object ResourceSharing extends InteractionType

  implicit val encoder: Encoder[InteractionType] = deriveEncoder
  implicit val decoder: Decoder[InteractionType] = deriveDecoder
}

/** Detected cluster of suspicious coordinated activity */
case class SuspiciousCluster(
  /** Agents in the cluster */
  agents: Set[AgentId],
  /** Cluster suspicion score (0.0 to 1.0) */
  suspicionScore: Double,
  /** Type of suspicious behavior detected */
  behaviorType: SuspiciousBehaviorType,
  /** Evidence supporting the detection */
  evidence: List[SuspicionEvidence],
  /** Detection timestamp */
  detectedAt: Long
)

/** Types of suspicious behavior patterns */
sealed trait SuspiciousBehaviorType

object SuspiciousBehaviorType {
  case object IdenticalBehavior extends SuspiciousBehaviorType
  case object CoordinatedVoting extends SuspiciousBehaviorType
  case object SynchronousActivity extends SuspiciousBehaviorType
  case object ResourcePooling extends SuspiciousBehaviorType
  case object FakeInteractions extends SuspiciousBehaviorType

  implicit val encoder: Encoder[SuspiciousBehaviorType] = deriveEncoder
  implicit val decoder: Decoder[SuspiciousBehaviorType] = deriveDecoder
}

/** Evidence supporting suspicion of Sybil/collusion behavior */
sealed trait SuspicionEvidence

object SuspicionEvidence {
  case class BehaviorSimilarity(similarity: Double, agents: Set[AgentId]) extends SuspicionEvidence
  case class HardwareFingerprinting(sharedFingerprints: Set[AgentId]) extends SuspicionEvidence
  case class TimingCorrelation(correlation: Double, actions: List[String]) extends SuspicionEvidence
  case class NetworkPatterns(centralityScore: Double, clusterCoefficient: Double) extends SuspicionEvidence
  case class EconomicAnomaly(description: String, affectedAgents: Set[AgentId]) extends SuspicionEvidence

  implicit val encoder: Encoder[SuspicionEvidence] = deriveEncoder
  implicit val decoder: Decoder[SuspicionEvidence] = deriveDecoder
}

/** Sybil detection result for an agent or group */
case class SybilDetectionResult(
  /** Agents evaluated */
  agents: Set[AgentId],
  /** Overall Sybil probability (0.0 to 1.0) */
  sybilProbability: Double,
  /** Individual component scores */
  componentScores: Map[String, Double],
  /** Confidence in the detection (0.0 to 1.0) */
  confidence: Double,
  /** Recommended action */
  recommendedAction: RecommendedAction,
  /** Detection timestamp */
  detectedAt: Long,
  /** Human-readable explanation */
  explanation: String
)

/** Recommended actions based on Sybil detection */
sealed trait RecommendedAction

object RecommendedAction {
  case object NoAction extends RecommendedAction
  case object IncreaseMonitoring extends RecommendedAction
  case object RequireAdditionalVerification extends RecommendedAction
  case object ReducePermissions extends RecommendedAction
  case object FlagForReview extends RecommendedAction
  case class ApplyPenalty(penaltyType: PenaltyType, severity: Double) extends RecommendedAction

  implicit val encoder: Encoder[RecommendedAction] = deriveEncoder
  implicit val decoder: Decoder[RecommendedAction] = deriveDecoder
}

/** Types of penalties that can be applied */
sealed trait PenaltyType

object PenaltyType {
  case object ReputationSlash extends PenaltyType
  case object StakeSlash extends PenaltyType
  case object TemporarySuspension extends PenaltyType
  case object PermanentBan extends PenaltyType
  case object ReducedRewards extends PenaltyType

  implicit val encoder: Encoder[PenaltyType] = deriveEncoder
  implicit val decoder: Decoder[PenaltyType] = deriveDecoder
}

/** Configuration for Sybil detection algorithms */
case class SybilDetectionConfig(
  /** Threshold for behavioral similarity to trigger investigation */
  behaviorSimilarityThreshold: Double = 0.85,
  /** Minimum cluster size to be considered suspicious */
  minSuspiciousClusterSize: Int = 3,
  /** Time window for correlation analysis (milliseconds) */
  correlationTimeWindow: Long = 300000, // 5 minutes
  /** Reputation slash percentage for confirmed Sybils */
  sybilReputationSlash: Double = 0.90,
  /** False positive tolerance (target rate) */
  falsePositiveTarget: Double = 0.05,
  /** Economic barrier minimum (OTTO stake) */
  minimumStake: Double = 1000.0,
  /** Hardware attestation requirement */
  requireHardwareAttestation: Boolean = false
)

// Circe encoders/decoders for all types
object SybilDetectionTypes {
  implicit val agentIdEncoder: Encoder[AgentId] = Encoder.encodeString.contramap(_.value)
  implicit val agentIdDecoder: Decoder[AgentId] = Decoder.decodeString.map(AgentId.apply)

  implicit val hardwareFingerprintEncoder: Encoder[HardwareFingerprint] = deriveEncoder
  implicit val hardwareFingerprintDecoder: Decoder[HardwareFingerprint] = deriveDecoder

  implicit val gasProfileEncoder: Encoder[GasProfile] = deriveEncoder
  implicit val gasProfileDecoder: Decoder[GasProfile] = deriveDecoder

  implicit val transactionPatternEncoder: Encoder[TransactionPattern] = deriveEncoder
  implicit val transactionPatternDecoder: Decoder[TransactionPattern] = deriveDecoder

  implicit val behaviorProfileEncoder: Encoder[BehaviorProfile] = deriveEncoder
  implicit val behaviorProfileDecoder: Decoder[BehaviorProfile] = deriveDecoder

  implicit val networkEdgeEncoder: Encoder[NetworkEdge] = deriveEncoder
  implicit val networkEdgeDecoder: Decoder[NetworkEdge] = deriveDecoder

  implicit val suspiciousClusterEncoder: Encoder[SuspiciousCluster] = deriveEncoder
  implicit val suspiciousClusterDecoder: Decoder[SuspiciousCluster] = deriveDecoder

  implicit val networkGraphEncoder: Encoder[NetworkGraph] = deriveEncoder
  implicit val networkGraphDecoder: Decoder[NetworkGraph] = deriveDecoder

  implicit val sybilDetectionResultEncoder: Encoder[SybilDetectionResult] = deriveEncoder
  implicit val sybilDetectionResultDecoder: Decoder[SybilDetectionResult] = deriveDecoder

  implicit val sybilDetectionConfigEncoder: Encoder[SybilDetectionConfig] = deriveEncoder
  implicit val sybilDetectionConfigDecoder: Decoder[SybilDetectionConfig] = deriveDecoder
}
