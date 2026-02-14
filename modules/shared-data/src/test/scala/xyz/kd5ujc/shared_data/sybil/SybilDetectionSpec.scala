package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import scala.concurrent.duration._

/**
 * Comprehensive test suite for Sybil resistance and collusion detection system.
 *
 * Tests all components of the multi-layer defense system including:
 * - Behavioral similarity detection
 * - Hardware fingerprinting
 * - Network graph analysis
 * - Automated penalty application
 * - Appeals process
 */
class SybilDetectionSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  val config = SybilDetectionConfig(
    behaviorSimilarityThreshold = 0.85,
    minSuspiciousClusterSize = 3,
    correlationTimeWindow = 300000L,
    sybilReputationSlash = 0.90,
    falsePositiveTarget = 0.05,
    minimumStake = 1000.0,
    requireHardwareAttestation = false
  )

  val behaviorAnalyzer = new BehaviorAnalyzer(config)
  val hardwareAttestation = new HardwareAttestation(config)
  val networkAnalyzer = new NetworkAnalyzer(config)
  val penaltySystem = new PenaltySystem(config)
  val sybilDetector = new SybilDetector(config, behaviorAnalyzer, hardwareAttestation, networkAnalyzer, penaltySystem)

  "BehaviorAnalyzer" should {

    "detect identical behavioral patterns" in {
      val baseProfile = createTestBehaviorProfile(AgentId("agent1"))
      val identicalProfile = baseProfile.copy(agentId = AgentId("agent2"))

      val similarity = behaviorAnalyzer.calculateBehavioralSimilarity(baseProfile, identicalProfile)
      similarity should be > 0.95
    }

    "identify legitimate behavioral differences" in {
      val profile1 = createTestBehaviorProfile(AgentId("agent1"))
      val profile2 = profile1.copy(
        agentId = AgentId("agent2"),
        avgResponseTime = profile1.avgResponseTime * 2,
        typingPattern = profile1.typingPattern.map(_ * 1.5),
        vocabularyFingerprint = Set("different", "vocabulary", "here")
      )

      val similarity = behaviorAnalyzer.calculateBehavioralSimilarity(profile1, profile2)
      similarity should be < 0.4
    }

    "detect suspicious clusters" in {
      val profiles = (1 to 5).map { i =>
        createTestBehaviorProfile(AgentId(s"agent$i"))
      }.toList

      behaviorAnalyzer.detectSuspiciousGroups(profiles).asserting { clusters =>
        clusters should have size 1
        clusters.head.agents should have size 5
        clusters.head.suspicionScore should be > 0.85
      }
    }

    "handle response time analysis correctly" in {
      val fastProfile = createTestBehaviorProfile(AgentId("fast"), avgResponseTime = 100.0)
      val slowProfile = createTestBehaviorProfile(AgentId("slow"), avgResponseTime = 1000.0)

      val similarity = behaviorAnalyzer.calculateBehavioralSimilarity(fastProfile, slowProfile)
      similarity should be < 0.6
    }

    "analyze typing patterns accurately" in {
      val profile1 = createTestBehaviorProfile(
        AgentId("user1"),
        typingPattern = List(50.0, 75.0, 60.0, 80.0, 55.0)
      )
      val profile2 = createTestBehaviorProfile(
        AgentId("user2"),
        typingPattern = List(200.0, 150.0, 180.0, 220.0, 175.0)
      )

      val similarity = behaviorAnalyzer.calculateBehavioralSimilarity(profile1, profile2)
      similarity should be < 0.5
    }
  }

  "HardwareAttestation" should {

    "generate valid hardware fingerprints" in {
      hardwareAttestation.generateFingerprint().asserting { fingerprint =>
        fingerprint.cpuSignature should not be empty
        fingerprint.systemHash should not be empty
        fingerprint.timestamp should be > 0L
      }
    }

    "verify fingerprint authenticity" in {
      for {
        fingerprint         <- hardwareAttestation.generateFingerprint()
        (valid, confidence) <- hardwareAttestation.verifyFingerprint(fingerprint)
      } yield {
        valid should be(true)
        confidence should be > 0.5
      }
    }

    "detect hardware sharing" in {
      val sharedFingerprint = HardwareFingerprint(
        teeAttestation = Some("shared_attestation"),
        cpuSignature = "shared_cpu_signature",
        systemHash = "shared_system_hash",
        timestamp = System.currentTimeMillis()
      )

      val fingerprints = Map(
        AgentId("agent1") -> sharedFingerprint,
        AgentId("agent2") -> sharedFingerprint,
        AgentId("agent3") -> sharedFingerprint
      )

      hardwareAttestation.detectHardwareSharing(fingerprints).asserting { clusters =>
        clusters should have size 1
        clusters.head.agents should have size 3
        clusters.head.suspicionScore should be > 0.8
      }
    }

    "calculate hardware similarity correctly" in {
      val fp1 = HardwareFingerprint(None, "cpu_sig_1", "system_1", System.currentTimeMillis())
      val fp2 = HardwareFingerprint(None, "cpu_sig_2", "system_2", System.currentTimeMillis())
      val fp3 = fp1.copy(timestamp = System.currentTimeMillis() + 1000)

      val similarity1 = hardwareAttestation.calculateHardwareSimilarity(fp1, fp2)
      val similarity2 = hardwareAttestation.calculateHardwareSimilarity(fp1, fp3)

      similarity1 should be < similarity2
      similarity2 should be > 0.6 // Same hardware, different timestamp
    }
  }

  "NetworkAnalyzer" should {

    "build interaction graph correctly" in {
      val transactions = List(
        TransactionRecord(AgentId("agent1"), Some(AgentId("agent2")), 100.0, System.currentTimeMillis()),
        TransactionRecord(AgentId("agent2"), Some(AgentId("agent3")), 200.0, System.currentTimeMillis())
      )

      val delegations = List(
        DelegationRecord(
          AgentId("agent1"),
          AgentId("agent2"),
          Set("vote", "trade"),
          3600000L,
          System.currentTimeMillis()
        )
      )

      val votes = List(
        VoteRecord(AgentId("agent1"), "proposal_1", "yes", System.currentTimeMillis()),
        VoteRecord(AgentId("agent2"), "proposal_1", "yes", System.currentTimeMillis() + 1000)
      )

      networkAnalyzer.buildInteractionGraph(transactions, delegations, votes).asserting { graph =>
        graph.nodes should have size 3
        graph.edges.size should be >= 2
      }
    }

    "detect coordinated voting" in {
      val votes = (1 to 5).map { i =>
        VoteRecord(AgentId(s"agent$i"), "proposal_1", "yes", System.currentTimeMillis() + i * 1000)
      }.toList

      val graph = NetworkGraph(
        nodes = votes.map(_.voter).toSet,
        edges = Set.empty,
        suspiciousClusters = List.empty
      )

      networkAnalyzer.analyzeNetwork(graph).asserting { clusters =>
        clusters.exists(_.behaviorType == SuspiciousBehaviorType.CoordinatedVoting) should be(true)
      }
    }

    "calculate agent network metrics" in {
      val agentId = AgentId("central_agent")
      val graph = createTestNetworkGraph(agentId)

      val metrics = networkAnalyzer.calculateAgentMetrics(agentId, graph)

      metrics.agentId should be(agentId)
      metrics.degree should be > 0
      metrics.betweennessCentrality should be >= 0.0
      metrics.clusteringCoefficient should be >= 0.0
    }

    "identify artificial interaction patterns" in {
      // Create a graph with obvious artificial patterns (star topology)
      val centralAgent = AgentId("central")
      val edges = (1 to 10).map { i =>
        NetworkEdge(
          from = centralAgent,
          to = AgentId(s"spoke$i"),
          weight = 1.0,
          interactionType = InteractionType.Transaction,
          lastInteraction = System.currentTimeMillis()
        )
      }.toSet

      val graph = NetworkGraph(
        nodes = edges.flatMap(e => Set(e.from, e.to)),
        edges = edges,
        suspiciousClusters = List.empty
      )

      networkAnalyzer.analyzeNetwork(graph).asserting { clusters =>
        clusters.exists(_.behaviorType == SuspiciousBehaviorType.FakeInteractions) should be(true)
      }
    }
  }

  "PenaltySystem" should {

    "calculate penalty severity correctly" in {
      val highConfidenceDetection = SybilDetectionResult(
        agents = Set(AgentId("sybil1")),
        sybilProbability = 0.95,
        componentScores = Map("behavior" -> 0.9, "hardware" -> 0.8, "network" -> 0.7),
        confidence = 0.9,
        recommendedAction = RecommendedAction.ApplyPenalty(PenaltyType.StakeSlash, 0.9),
        detectedAt = System.currentTimeMillis(),
        explanation = "High-confidence Sybil detection"
      )

      val severity = penaltySystem.calculatePenaltySeverity(highConfidenceDetection)
      severity.score should be > 0.8
      severity.category should be(SeverityCategory.Critical)
    }

    "apply appropriate penalties" in {
      val detectionResult = SybilDetectionResult(
        agents = Set(AgentId("confirmed_sybil")),
        sybilProbability = 0.9,
        componentScores = Map("behavior" -> 0.85, "hardware" -> 0.95, "network" -> 0.8),
        confidence = 0.85,
        recommendedAction = RecommendedAction.ApplyPenalty(PenaltyType.ReputationSlash, 0.9),
        detectedAt = System.currentTimeMillis(),
        explanation = "Confirmed Sybil attack"
      )

      penaltySystem.applyPenalties(detectionResult).asserting { application =>
        application.affectedAgents should contain(AgentId("confirmed_sybil"))
        application.appliedPenalties should not be empty
        application.totalSeverity should be > 0.5
      }
    }

    "handle penalty appeals" in {
      val appeal = PenaltyAppeal(
        appealId = "appeal_123",
        agentId = AgentId("appealing_agent"),
        detectionId = "detection_123",
        penaltyId = "penalty_123",
        evidence = List(
          AppealEvidence(
            "false_positive",
            "Evidence shows legitimate behavior",
            Map("proof" -> "behavioral_analysis.pdf")
          )
        ),
        appealDeadline = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L,
        submittedAt = System.currentTimeMillis()
      )

      penaltySystem.processAppeal(appeal, List.empty).asserting { result =>
        result.appealId should be("appeal_123")
        result.reviewOutcome should not be null
      }
    }

    "get agent penalty status" in {
      val agentId = AgentId("test_agent")

      penaltySystem.getAgentPenaltyStatus(agentId).asserting { status =>
        status.agentId should be(agentId)
        status.lastUpdated should be > 0L
      }
    }
  }

  "SybilDetector" should {

    "perform comprehensive Sybil detection" in {
      val agents = Set(AgentId("agent1"), AgentId("agent2"), AgentId("agent3"))
      val behaviorProfiles = agents.map { id =>
        id -> createTestBehaviorProfile(id)
      }.toMap
      val hardwareFingerprints = agents.map { id =>
        id -> createTestHardwareFingerprint()
      }.toMap
      val networkGraph = createTestNetworkGraph(agents.head)

      sybilDetector.detectSybilAttacks(agents, behaviorProfiles, hardwareFingerprints, networkGraph).asserting {
        result =>
          result.analyzedAgents should be(agents)
          result.individualResults should have size agents.size
          result.systemHealth.overallScore should be >= 0.0
      }
    }

    "screen new agent registrations" in {
      val registrationData = AgentRegistrationData(
        agentId = AgentId("new_agent"),
        providedStake = 1500.0,
        registrationTimestamp = System.currentTimeMillis(),
        ipAddress = "192.168.1.1",
        userAgent = "Mozilla/5.0"
      )

      sybilDetector.screenNewAgent(AgentId("new_agent"), registrationData).asserting { result =>
        result.agentId should be(AgentId("new_agent"))
        result.riskScore should be >= 0.0
        result.riskScore should be <= 1.0
      }
    }

    "generate analysis reports" in {
      val detectionId = "test_detection_123"

      sybilDetector.generateAnalysisReport(detectionId).asserting { report =>
        report.detectionId should be(detectionId)
        report.executiveSummary should not be empty
        report.generatedAt should be > 0L
      }
    }

    "update configuration correctly" in {
      val newConfig = config.copy(behaviorSimilarityThreshold = 0.9)

      sybilDetector.updateConfiguration(newConfig).asserting { update =>
        update.newConfig.behaviorSimilarityThreshold should be(0.9)
        update.recalibrationResults should not be empty
      }
    }

    "start continuous monitoring" in {
      sybilDetector.startContinuousMonitoring().asserting { session =>
        session.sessionId should not be empty
        session.startedAt should be > 0L
        session.config should be(config)
      }
    }
  }

  "Integration scenarios" should {

    "detect coordinated Sybil attack" in {
      // Create a coordinated attack scenario with 5 Sybil agents
      val sybilAgents = (1 to 5).map(i => AgentId(s"sybil$i")).toSet
      val legitimateAgents = (1 to 3).map(i => AgentId(s"legit$i")).toSet
      val allAgents = sybilAgents ++ legitimateAgents

      // Sybil agents have very similar behavior
      val sybilBehaviorBase = createTestBehaviorProfile(AgentId("template"))
      val behaviorProfiles = allAgents.map { id =>
        if (sybilAgents.contains(id)) {
          id -> sybilBehaviorBase.copy(agentId = id)
        } else {
          id -> createDiverseBehaviorProfile(id)
        }
      }.toMap

      // Sybil agents share hardware fingerprints
      val sharedFingerprint = createTestHardwareFingerprint()
      val hardwareFingerprints = allAgents.map { id =>
        if (sybilAgents.contains(id)) {
          id -> sharedFingerprint
        } else {
          id -> createTestHardwareFingerprint()
        }
      }.toMap

      // Create network with coordinated voting
      val networkGraph = createCoordinatedNetworkGraph(sybilAgents.toList)

      sybilDetector.detectSybilAttacks(allAgents, behaviorProfiles, hardwareFingerprints, networkGraph).asserting {
        result =>
          // Should detect the Sybil cluster
          result.suspiciousClusters should not be empty

          // Sybil agents should have high detection scores
          val sybilResults = result.individualResults.filter(r => sybilAgents.intersect(r.agents).nonEmpty)
          sybilResults.foreach(_.sybilProbability should be > 0.7)

          // Legitimate agents should have low detection scores
          val legitResults = result.individualResults.filter(r => legitimateAgents.intersect(r.agents).nonEmpty)
          legitResults.foreach(_.sybilProbability should be < 0.4)

          // Penalties should be applied to Sybil agents
          result.appliedPenalties should not be empty
      }
    }

    "handle false positive scenario" in {
      // Create agents with similar legitimate behavior (e.g., institutional traders)
      val institutionalAgents = (1 to 4).map(i => AgentId(s"institution$i")).toSet

      // Similar but legitimate behavior patterns
      val baseBehavior = createTestBehaviorProfile(AgentId("base"))
      val behaviorProfiles = institutionalAgents.map { id =>
        id -> baseBehavior.copy(
          agentId = id,
          avgResponseTime = baseBehavior.avgResponseTime + scala.util.Random.nextDouble() * 50, // Small variation
          vocabularyFingerprint = baseBehavior.vocabularyFingerprint ++ Set(s"institution_${id.value}")
        )
      }.toMap

      // Different hardware fingerprints (legitimate)
      val hardwareFingerprints = institutionalAgents.map { id =>
        id -> createTestHardwareFingerprint()
      }.toMap

      val networkGraph = createLegitimateNetworkGraph(institutionalAgents.toList)

      sybilDetector
        .detectSybilAttacks(institutionalAgents, behaviorProfiles, hardwareFingerprints, networkGraph)
        .asserting { result =>
          // Should have moderate behavioral similarity but low overall Sybil probability due to different hardware
          val maxSybilProbability = result.individualResults.map(_.sybilProbability).max
          maxSybilProbability should be < 0.6 // Should not trigger high-confidence detection
        }
    }

    "detect mixed attack with legitimate agents" in {
      val sybilAgents = Set(AgentId("sybil1"), AgentId("sybil2"))
      val legitimateAgents = Set(AgentId("legit1"), AgentId("legit2"), AgentId("legit3"))
      val allAgents = sybilAgents ++ legitimateAgents

      val behaviorProfiles = allAgents.map { id =>
        if (sybilAgents.contains(id)) {
          id -> createTestBehaviorProfile(AgentId("sybil_template")).copy(agentId = id)
        } else {
          id -> createDiverseBehaviorProfile(id)
        }
      }.toMap

      val hardwareFingerprints = allAgents.map { id =>
        id -> createTestHardwareFingerprint()
      }.toMap

      val networkGraph = createMixedNetworkGraph(sybilAgents.toList, legitimateAgents.toList)

      sybilDetector.detectSybilAttacks(allAgents, behaviorProfiles, hardwareFingerprints, networkGraph).asserting {
        result =>
          // Should correctly identify only the Sybil agents
          val sybilDetections = result.individualResults.filter(_.sybilProbability > 0.6)
          sybilDetections.flatMap(_.agents) should contain theSameElementsAs sybilAgents
      }
    }
  }

  // Helper methods for creating test data

  private def createTestBehaviorProfile(
    agentId:         AgentId,
    avgResponseTime: Double = 150.0,
    typingPattern:   List[Double] = List(50.0, 60.0, 55.0, 65.0, 52.0)
  ): BehaviorProfile =
    BehaviorProfile(
      agentId = agentId,
      avgResponseTime = avgResponseTime,
      responseTimeStdDev = 25.0,
      typingPattern = typingPattern,
      vocabularyFingerprint = Set("the", "and", "or", "to", "from"),
      transactionPattern = TransactionPattern(
        avgTxInterval = 3600.0,
        typicalAmounts = List(100.0, 200.0, 150.0),
        gasPreferences = GasProfile((20.0, 50.0), List(21000L, 50000L), 0.5),
        contractUsage = Map("market" -> 5, "governance" -> 2)
      ),
      activityPattern = Map(9 -> 0.3, 10 -> 0.4, 11 -> 0.2, 14 -> 0.1),
      lastUpdated = System.currentTimeMillis()
    )

  private def createDiverseBehaviorProfile(agentId: AgentId): BehaviorProfile = {
    val random = new scala.util.Random(agentId.value.hashCode)
    createTestBehaviorProfile(
      agentId,
      avgResponseTime = 100.0 + random.nextDouble() * 200.0,
      typingPattern = List.fill(5)(40.0 + random.nextDouble() * 40.0)
    ).copy(
      vocabularyFingerprint = Set("unique", s"words_${agentId.value}", "different", "vocabulary"),
      activityPattern = Map(
        8 + random.nextInt(8)  -> 0.4,
        12 + random.nextInt(8) -> 0.3,
        16 + random.nextInt(4) -> 0.3
      )
    )
  }

  private def createTestHardwareFingerprint(): HardwareFingerprint = {
    val random = scala.util.Random.nextLong()
    HardwareFingerprint(
      teeAttestation = None,
      cpuSignature = s"cpu_sig_$random",
      systemHash = s"system_hash_$random",
      timestamp = System.currentTimeMillis()
    )
  }

  private def createTestNetworkGraph(centralAgent: AgentId): NetworkGraph = {
    val edges = (1 to 3).map { i =>
      NetworkEdge(
        from = centralAgent,
        to = AgentId(s"neighbor$i"),
        weight = 0.5,
        interactionType = InteractionType.Transaction,
        lastInteraction = System.currentTimeMillis()
      )
    }.toSet

    NetworkGraph(
      nodes = edges.flatMap(e => Set(e.from, e.to)),
      edges = edges,
      suspiciousClusters = List.empty
    )
  }

  private def createCoordinatedNetworkGraph(agents: List[AgentId]): NetworkGraph = {
    val edges = agents
      .combinations(2)
      .map { case List(a1, a2) =>
        NetworkEdge(a1, a2, 0.9, InteractionType.CoVoting, System.currentTimeMillis())
      }
      .toSet

    NetworkGraph(
      nodes = agents.toSet,
      edges = edges,
      suspiciousClusters = List.empty
    )
  }

  private def createLegitimateNetworkGraph(agents: List[AgentId]): NetworkGraph = {
    val edges = agents
      .sliding(2)
      .map { case List(a1, a2) =>
        NetworkEdge(a1, a2, 0.3, InteractionType.Transaction, System.currentTimeMillis())
      }
      .toSet

    NetworkGraph(
      nodes = agents.toSet,
      edges = edges,
      suspiciousClusters = List.empty
    )
  }

  private def createMixedNetworkGraph(sybilAgents: List[AgentId], legitAgents: List[AgentId]): NetworkGraph = {
    val sybilEdges = sybilAgents.combinations(2).map { case List(a1, a2) =>
      NetworkEdge(a1, a2, 0.95, InteractionType.CoVoting, System.currentTimeMillis())
    }

    val legitEdges = legitAgents.combinations(2).map { case List(a1, a2) =>
      NetworkEdge(a1, a2, 0.2, InteractionType.Transaction, System.currentTimeMillis())
    }

    val allEdges = (sybilEdges ++ legitEdges).toSet

    NetworkGraph(
      nodes = (sybilAgents ++ legitAgents).toSet,
      edges = allEdges,
      suspiciousClusters = List.empty
    )
  }
}
