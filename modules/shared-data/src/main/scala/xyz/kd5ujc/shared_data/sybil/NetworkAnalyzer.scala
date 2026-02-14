package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.implicits._
import scala.collection.mutable

/**
 * Network graph analysis system for detecting coordinated behavior and collusion.
 *
 * This analyzer examines interaction patterns between agents to identify:
 * - Coordinated voting patterns
 * - Synchronized transaction timing
 * - Resource pooling behaviors
 * - Artificial interaction networks
 * - Sybil attack coordination
 */
class NetworkAnalyzer(config: SybilDetectionConfig) {

  /**
   * Analyze the interaction network to detect suspicious coordination patterns.
   */
  def analyzeNetwork(graph: NetworkGraph): IO[List[SuspiciousCluster]] = for {
    coordinatedVoting      <- detectCoordinatedVoting(graph)
    synchronousActivity    <- detectSynchronousActivity(graph)
    resourcePooling        <- detectResourcePooling(graph)
    artificialInteractions <- detectArtificialInteractions(graph)
  } yield List(coordinatedVoting, synchronousActivity, resourcePooling, artificialInteractions).flatten

  /**
   * Build interaction graph from transaction and delegation data.
   */
  def buildInteractionGraph(
    transactions: List[TransactionRecord],
    delegations:  List[DelegationRecord],
    votes:        List[VoteRecord]
  ): IO[NetworkGraph] = IO {
    val nodeSet = mutable.Set[AgentId]()
    val edgeList = mutable.ListBuffer[NetworkEdge]()

    // Add transaction edges
    transactions.foreach { tx =>
      nodeSet += tx.from
      if (tx.to.isDefined) {
        nodeSet += tx.to.get
        edgeList += NetworkEdge(
          from = tx.from,
          to = tx.to.get,
          weight = calculateTransactionWeight(tx),
          interactionType = InteractionType.Transaction,
          lastInteraction = tx.timestamp
        )
      }
    }

    // Add delegation edges
    delegations.foreach { del =>
      nodeSet += del.delegator
      nodeSet += del.delegatee
      edgeList += NetworkEdge(
        from = del.delegator,
        to = del.delegatee,
        weight = calculateDelegationWeight(del),
        interactionType = InteractionType.Delegation,
        lastInteraction = del.timestamp
      )
    }

    // Add voting correlation edges
    val votingEdges = detectVotingCorrelations(votes)
    edgeList ++= votingEdges
    votingEdges.foreach { edge =>
      nodeSet += edge.from
      nodeSet += edge.to
    }

    NetworkGraph(
      nodes = nodeSet.toSet,
      edges = edgeList.toSet,
      suspiciousClusters = List.empty // Will be populated by analysis
    )
  }

  /**
   * Detect coordinated voting patterns that may indicate collusion.
   */
  private def detectCoordinatedVoting(graph: NetworkGraph): IO[List[SuspiciousCluster]] = IO {
    val votingEdges = graph.edges.filter(_.interactionType == InteractionType.CoVoting)
    val clusters = identifyDenseSubgraphs(votingEdges, threshold = 0.8)

    clusters.filter(_.size >= config.minSuspiciousClusterSize).map { cluster =>
      val avgWeight = votingEdges
        .filter(e => cluster.contains(e.from) && cluster.contains(e.to))
        .map(_.weight)
        .sum / cluster.size

      val evidence = List(
        SuspicionEvidence.NetworkPatterns(
          centralityScore = calculateClusterCentrality(cluster, graph),
          clusterCoefficient = calculateClusterCoefficient(cluster, graph)
        )
      )

      SuspiciousCluster(
        agents = cluster,
        suspicionScore = avgWeight,
        behaviorType = SuspiciousBehaviorType.CoordinatedVoting,
        evidence = evidence,
        detectedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Detect suspiciously synchronized activity patterns.
   */
  private def detectSynchronousActivity(graph: NetworkGraph): IO[List[SuspiciousCluster]] = IO {
    val synchronousEdges = graph.edges.filter(_.interactionType == InteractionType.SimultaneousAction)
    val timeWindows = groupByTimeWindows(synchronousEdges)

    timeWindows.flatMap { case (_, edges) =>
      val participants = edges.flatMap(e => Set(e.from, e.to)).toSet

      if (participants.size >= config.minSuspiciousClusterSize) {
        val synchronyScore = calculateSynchronyScore(edges)
        if (synchronyScore >= 0.7) {
          Some(
            SuspiciousCluster(
              agents = participants,
              suspicionScore = synchronyScore,
              behaviorType = SuspiciousBehaviorType.SynchronousActivity,
              evidence = List(
                SuspicionEvidence.TimingCorrelation(
                  correlation = synchronyScore,
                  actions = edges.map(_.interactionType.toString).toList.distinct
                )
              ),
              detectedAt = System.currentTimeMillis()
            )
          )
        } else None
      } else None
    }
  }

  /**
   * Detect resource pooling behaviors that may indicate coordination.
   */
  private def detectResourcePooling(graph: NetworkGraph): IO[List[SuspiciousCluster]] = IO {
    val resourceEdges = graph.edges.filter(_.interactionType == InteractionType.ResourceSharing)
    val poolingPatterns = identifyPoolingPatterns(resourceEdges)

    poolingPatterns.map { pattern =>
      SuspiciousCluster(
        agents = pattern.participants,
        suspicionScore = pattern.suspicionScore,
        behaviorType = SuspiciousBehaviorType.ResourcePooling,
        evidence = List(
          SuspicionEvidence.EconomicAnomaly(
            description = s"Resource pooling pattern detected with ${pattern.participants.size} participants",
            affectedAgents = pattern.participants
          )
        ),
        detectedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Detect artificial interaction patterns that may indicate fake activity.
   */
  private def detectArtificialInteractions(graph: NetworkGraph): IO[List[SuspiciousCluster]] = IO {
    val artificialPatterns = identifyArtificialPatterns(graph)

    artificialPatterns.map { pattern =>
      SuspiciousCluster(
        agents = pattern.agents,
        suspicionScore = pattern.score,
        behaviorType = SuspiciousBehaviorType.FakeInteractions,
        evidence = List(
          SuspicionEvidence.NetworkPatterns(
            centralityScore = pattern.centralityAnomaly,
            clusterCoefficient = pattern.clusteringAnomaly
          )
        ),
        detectedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Calculate graph metrics for an agent.
   */
  def calculateAgentMetrics(agentId: AgentId, graph: NetworkGraph): AgentNetworkMetrics = {
    val agentEdges = graph.edges.filter(e => e.from == agentId || e.to == agentId)
    val neighbors = agentEdges.flatMap(e => Set(e.from, e.to)).filter(_ != agentId)

    val degree = agentEdges.size
    val centrality = calculateBetweennessCentrality(agentId, graph)
    val clusteringCoefficient = calculateLocalClusteringCoefficient(agentId, graph)
    val pageRankScore = calculatePageRank(agentId, graph)

    AgentNetworkMetrics(
      agentId = agentId,
      degree = degree,
      betweennessCentrality = centrality,
      clusteringCoefficient = clusteringCoefficient,
      pageRank = pageRankScore,
      neighbors = neighbors.toSet
    )
  }

  // Graph Analysis Algorithms

  private def identifyDenseSubgraphs(
    edges:     Set[NetworkEdge],
    threshold: Double
  ): List[Set[AgentId]] = {
    val adjacencyMatrix = buildAdjacencyMatrix(edges)
    val clusters = mutable.ListBuffer[Set[AgentId]]()

    // Use a simple greedy algorithm to find dense subgraphs
    val nodes = edges.flatMap(e => Set(e.from, e.to)).toList
    val visited = mutable.Set[AgentId]()

    nodes.foreach { startNode =>
      if (!visited.contains(startNode)) {
        val cluster = expandCluster(startNode, adjacencyMatrix, threshold, visited)
        if (cluster.size >= config.minSuspiciousClusterSize) {
          clusters += cluster
        }
      }
    }

    clusters.toList
  }

  private def expandCluster(
    startNode:       AgentId,
    adjacencyMatrix: Map[AgentId, Map[AgentId, Double]],
    threshold:       Double,
    visited:         mutable.Set[AgentId]
  ): Set[AgentId] = {
    val cluster = mutable.Set[AgentId](startNode)
    val queue = mutable.Queue[AgentId](startNode)
    visited += startNode

    while (queue.nonEmpty) {
      val current = queue.dequeue()
      val neighbors = adjacencyMatrix.getOrElse(current, Map.empty)

      neighbors.foreach { case (neighbor, weight) =>
        if (!visited.contains(neighbor) && weight >= threshold) {
          cluster += neighbor
          queue.enqueue(neighbor)
          visited += neighbor
        }
      }
    }

    cluster.toSet
  }

  private def buildAdjacencyMatrix(edges: Set[NetworkEdge]): Map[AgentId, Map[AgentId, Double]] = {
    val matrix = mutable.Map[AgentId, mutable.Map[AgentId, Double]]()

    edges.foreach { edge =>
      matrix.getOrElseUpdate(edge.from, mutable.Map.empty) += (edge.to -> edge.weight)
      matrix.getOrElseUpdate(edge.to, mutable.Map.empty) += (edge.from -> edge.weight)
    }

    matrix.view.mapValues(_.toMap).toMap
  }

  private def calculateBetweennessCentrality(agentId: AgentId, graph: NetworkGraph): Double = {
    // Simplified betweenness centrality calculation
    val allPaths = findShortestPaths(graph)
    val pathsThroughAgent =
      allPaths.count(path => path.contains(agentId) && path.head != agentId && path.last != agentId)
    pathsThroughAgent.toDouble / allPaths.length
  }

  private def calculateLocalClusteringCoefficient(agentId: AgentId, graph: NetworkGraph): Double = {
    val neighbors = graph.edges
      .filter(e => e.from == agentId || e.to == agentId)
      .flatMap(e => Set(e.from, e.to))
      .filter(_ != agentId)

    if (neighbors.size < 2) return 0.0

    val possibleEdges = neighbors.size * (neighbors.size - 1) / 2
    val actualEdges = neighbors.toList.combinations(2).count { case List(n1, n2) =>
      graph.edges.exists(e => (e.from == n1 && e.to == n2) || (e.from == n2 && e.to == n1))
    }

    actualEdges.toDouble / possibleEdges
  }

  private def calculatePageRank(agentId: AgentId, graph: NetworkGraph): Double = {
    // Simplified PageRank implementation
    val nodes = graph.nodes.toList
    val dampingFactor = 0.85
    val initialScore = 1.0 / nodes.size

    // Run simplified PageRank (normally would iterate to convergence)
    val incomingEdges = graph.edges.filter(_.to == agentId)
    val pageRankContributions = incomingEdges.map { edge =>
      val outDegree = graph.edges.count(_.from == edge.from)
      if (outDegree > 0) initialScore / outDegree else 0.0
    }.sum

    (1.0 - dampingFactor) / nodes.size + dampingFactor * pageRankContributions
  }

  private def findShortestPaths(graph: NetworkGraph): List[List[AgentId]] =
    // Simplified - would use Dijkstra's algorithm in production
    graph.edges.map(e => List(e.from, e.to)).toList

  private def calculateClusterCentrality(cluster: Set[AgentId], graph: NetworkGraph): Double =
    cluster.map(calculateBetweennessCentrality(_, graph)).sum / cluster.size

  private def calculateClusterCoefficient(cluster: Set[AgentId], graph: NetworkGraph): Double =
    cluster.map(calculateLocalClusteringCoefficient(_, graph)).sum / cluster.size

  // Utility Functions

  private def detectVotingCorrelations(votes: List[VoteRecord]): List[NetworkEdge] = {
    val votesByProposal = votes.groupBy(_.proposalId)
    val correlationEdges = mutable.ListBuffer[NetworkEdge]()

    votesByProposal.foreach { case (_, proposalVotes) =>
      val voterPairs = proposalVotes.combinations(2).toList
      voterPairs.foreach { case List(vote1, vote2) =>
        if (vote1.choice == vote2.choice) {
          val timeDiff = math.abs(vote1.timestamp - vote2.timestamp)
          val correlation = calculateTimeCorrelation(timeDiff)

          if (correlation >= 0.7) {
            correlationEdges += NetworkEdge(
              from = vote1.voter,
              to = vote2.voter,
              weight = correlation,
              interactionType = InteractionType.CoVoting,
              lastInteraction = math.max(vote1.timestamp, vote2.timestamp)
            )
          }
        }
      }
    }

    correlationEdges.toList
  }

  private def calculateTimeCorrelation(timeDiff: Long): Double = {
    val maxCorrelationWindow = config.correlationTimeWindow
    math.max(0.0, 1.0 - (timeDiff.toDouble / maxCorrelationWindow))
  }

  private def calculateTransactionWeight(tx: TransactionRecord): Double = {
    // Weight based on transaction amount and recency
    val amountWeight = math.min(1.0, tx.amount / 1000.0)
    val recencyWeight = calculateRecencyWeight(tx.timestamp)
    (amountWeight + recencyWeight) / 2.0
  }

  private def calculateDelegationWeight(del: DelegationRecord): Double = {
    // Weight based on delegation duration and scope
    val durationWeight = math.min(1.0, del.duration.toDouble / (24 * 3600 * 1000)) // Max 1 day
    val scopeWeight = del.scope.size.toDouble / 10.0 // Normalize scope complexity
    (durationWeight + scopeWeight) / 2.0
  }

  private def calculateRecencyWeight(timestamp: Long): Double = {
    val age = System.currentTimeMillis() - timestamp
    val maxAge = 7 * 24 * 3600 * 1000L // 1 week
    math.max(0.0, 1.0 - (age.toDouble / maxAge))
  }

  private def groupByTimeWindows(edges: Set[NetworkEdge]): Map[Long, Set[NetworkEdge]] = {
    val windowSize = config.correlationTimeWindow
    edges.groupBy(e => e.lastInteraction / windowSize)
  }

  private def calculateSynchronyScore(edges: Set[NetworkEdge]): Double = {
    if (edges.isEmpty) return 0.0

    val timestamps = edges.map(_.lastInteraction).toList.sorted
    val maxTimeDiff = timestamps.max - timestamps.min
    val avgTimeDiff = if (timestamps.size > 1) {
      timestamps.zip(timestamps.tail).map { case (t1, t2) => t2 - t1 }.sum.toDouble / (timestamps.size - 1)
    } else 0.0

    if (maxTimeDiff <= config.correlationTimeWindow) 1.0 else avgTimeDiff / maxTimeDiff
  }

  private def identifyPoolingPatterns(edges: Set[NetworkEdge]): List[PoolingPattern] = {
    // Identify patterns where multiple agents pool resources
    val patterns = mutable.ListBuffer[PoolingPattern]()
    val groups = edges.groupBy(e => (e.from, e.to)).filter(_._2.size > 1)

    groups.foreach { case ((from, to), edgeGroup) =>
      val totalWeight = edgeGroup.map(_.weight).sum
      val participants = Set(from, to)

      if (totalWeight >= 0.8) {
        patterns += PoolingPattern(
          participants = participants,
          suspicionScore = totalWeight,
          poolType = "resource_sharing"
        )
      }
    }

    patterns.toList
  }

  private def identifyArtificialPatterns(graph: NetworkGraph): List[ArtificialPattern] = {
    val patterns = mutable.ListBuffer[ArtificialPattern]()

    graph.nodes.foreach { node =>
      val metrics = calculateAgentMetrics(node, graph)

      // Check for artificial patterns
      val isCentralityAnomaly = metrics.betweennessCentrality > 0.9
      val isClusteringAnomaly = metrics.clusteringCoefficient < 0.1 && metrics.degree > 10

      if (isCentralityAnomaly || isClusteringAnomaly) {
        patterns += ArtificialPattern(
          agents = Set(node),
          score = if (isCentralityAnomaly) 0.9 else 0.7,
          centralityAnomaly = metrics.betweennessCentrality,
          clusteringAnomaly = metrics.clusteringCoefficient
        )
      }
    }

    patterns.toList
  }
}

// Supporting data types for network analysis

case class TransactionRecord(
  from:      AgentId,
  to:        Option[AgentId],
  amount:    Double,
  timestamp: Long
)

case class DelegationRecord(
  delegator: AgentId,
  delegatee: AgentId,
  scope:     Set[String],
  duration:  Long,
  timestamp: Long
)

case class VoteRecord(
  voter:      AgentId,
  proposalId: String,
  choice:     String,
  timestamp:  Long
)

case class AgentNetworkMetrics(
  agentId:               AgentId,
  degree:                Int,
  betweennessCentrality: Double,
  clusteringCoefficient: Double,
  pageRank:              Double,
  neighbors:             Set[AgentId]
)

case class PoolingPattern(
  participants:   Set[AgentId],
  suspicionScore: Double,
  poolType:       String
)

case class ArtificialPattern(
  agents:            Set[AgentId],
  score:             Double,
  centralityAnomaly: Double,
  clusteringAnomaly: Double
)
