package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.implicits._

/**
 * Behavioral analysis engine for detecting Sybil attacks through pattern recognition.
 *
 * This analyzer examines multiple behavioral dimensions to identify potentially
 * coordinated or artificially similar behavior patterns that may indicate
 * Sybil attacks or collusion.
 */
class BehaviorAnalyzer(config: SybilDetectionConfig) {

  /**
   * Calculate behavioral similarity between two agents.
   * Returns similarity score from 0.0 (completely different) to 1.0 (identical).
   */
  def calculateBehavioralSimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val weights = Map(
      "responseTime" -> 0.25,
      "typing"       -> 0.20,
      "vocabulary"   -> 0.15,
      "transaction"  -> 0.25,
      "activity"     -> 0.15
    )

    val responseTimeSimilarity = calculateResponseTimeSimilarity(profile1, profile2)
    val typingSimilarity = calculateTypingSimilarity(profile1, profile2)
    val vocabularySimilarity = calculateVocabularySimilarity(profile1, profile2)
    val transactionSimilarity = calculateTransactionSimilarity(profile1, profile2)
    val activitySimilarity = calculateActivityPatternSimilarity(profile1, profile2)

    weights("responseTime") * responseTimeSimilarity +
    weights("typing") * typingSimilarity +
    weights("vocabulary") * vocabularySimilarity +
    weights("transaction") * transactionSimilarity +
    weights("activity") * activitySimilarity
  }

  /**
   * Analyze response time patterns for similarity.
   * Examines both average response time and timing variance patterns.
   */
  private def calculateResponseTimeSimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val avgDiff = math.abs(profile1.avgResponseTime - profile2.avgResponseTime)
    val maxAvgTime = math.max(profile1.avgResponseTime, profile2.avgResponseTime)
    val avgSimilarity = if (maxAvgTime == 0) 1.0 else 1.0 - (avgDiff / maxAvgTime)

    val stdDevDiff = math.abs(profile1.responseTimeStdDev - profile2.responseTimeStdDev)
    val maxStdDev = math.max(profile1.responseTimeStdDev, profile2.responseTimeStdDev)
    val stdDevSimilarity = if (maxStdDev == 0) 1.0 else 1.0 - (stdDevDiff / maxStdDev)

    // Weighted combination: average time (70%) and variance (30%)
    0.7 * avgSimilarity + 0.3 * stdDevSimilarity
  }

  /**
   * Analyze typing patterns using inter-keystroke timing analysis.
   * Highly distinctive biometric that's difficult to fake.
   */
  private def calculateTypingSimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val pattern1 = profile1.typingPattern
    val pattern2 = profile2.typingPattern

    if (pattern1.isEmpty || pattern2.isEmpty) return 0.0

    // Calculate correlation between timing patterns
    val correlation = pearsonCorrelation(pattern1, pattern2)

    // Convert correlation to similarity (handle negative correlations)
    math.max(0.0, correlation)
  }

  /**
   * Analyze vocabulary fingerprinting for writing style similarity.
   * Compares common words, phrases, and linguistic patterns.
   */
  private def calculateVocabularySimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val vocab1 = profile1.vocabularyFingerprint
    val vocab2 = profile2.vocabularyFingerprint

    if (vocab1.isEmpty && vocab2.isEmpty) return 1.0
    if (vocab1.isEmpty || vocab2.isEmpty) return 0.0

    // Jaccard similarity coefficient
    val intersection = vocab1.intersect(vocab2).size
    val union = vocab1.union(vocab2).size

    intersection.toDouble / union.toDouble
  }

  /**
   * Analyze transaction patterns for behavioral similarity.
   * Examines timing, amounts, gas preferences, and contract usage.
   */
  private def calculateTransactionSimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val tx1 = profile1.transactionPattern
    val tx2 = profile2.transactionPattern

    // Transaction interval similarity
    val intervalDiff = math.abs(tx1.avgTxInterval - tx2.avgTxInterval)
    val maxInterval = math.max(tx1.avgTxInterval, tx2.avgTxInterval)
    val intervalSimilarity = if (maxInterval == 0) 1.0 else 1.0 - (intervalDiff / maxInterval)

    // Amount pattern similarity (using distribution comparison)
    val amountSimilarity = calculateAmountPatternSimilarity(tx1.typicalAmounts, tx2.typicalAmounts)

    // Gas preference similarity
    val gasSimilarity = calculateGasSimilarity(tx1.gasPreferences, tx2.gasPreferences)

    // Contract usage similarity
    val contractSimilarity = calculateContractUsageSimilarity(tx1.contractUsage, tx2.contractUsage)

    // Weighted combination
    0.3 * intervalSimilarity + 0.3 * amountSimilarity + 0.2 * gasSimilarity + 0.2 * contractSimilarity
  }

  /**
   * Compare activity patterns (time of day preferences).
   */
  private def calculateActivityPatternSimilarity(
    profile1: BehaviorProfile,
    profile2: BehaviorProfile
  ): Double = {
    val activity1 = profile1.activityPattern
    val activity2 = profile2.activityPattern

    // Ensure both patterns have data for all 24 hours
    val hours = 0 until 24
    val pattern1 = hours.map(h => activity1.getOrElse(h, 0.0)).toList
    val pattern2 = hours.map(h => activity2.getOrElse(h, 0.0)).toList

    // Calculate cosine similarity for activity patterns
    cosineSimilarity(pattern1, pattern2)
  }

  /**
   * Detect groups of agents with suspiciously similar behavior.
   */
  def detectSuspiciousGroups(profiles: List[BehaviorProfile]): IO[List[SuspiciousCluster]] = IO {
    val similarities = for {
      i <- profiles.indices
      j <- profiles.indices if i < j
      profile1 = profiles(i)
      profile2 = profiles(j)
      similarity = calculateBehavioralSimilarity(profile1, profile2)
      if similarity >= config.behaviorSimilarityThreshold
    } yield (profile1.agentId, profile2.agentId, similarity)

    // Build clusters from similar pairs
    val clusters = buildClusters(similarities.toList)

    // Convert to SuspiciousCluster objects
    clusters.filter(_.size >= config.minSuspiciousClusterSize).map { cluster =>
      val avgSimilarity = similarities
        .filter { case (id1, id2, _) => cluster.contains(id1) && cluster.contains(id2) }
        .map(_._3)
        .sum / cluster.size

      SuspiciousCluster(
        agents = cluster,
        suspicionScore = avgSimilarity,
        behaviorType = SuspiciousBehaviorType.IdenticalBehavior,
        evidence = List(SuspicionEvidence.BehaviorSimilarity(avgSimilarity, cluster)),
        detectedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Build clusters from similarity pairs using Union-Find algorithm.
   */
  private def buildClusters(similarities: List[(AgentId, AgentId, Double)]): List[Set[AgentId]] = {
    val agentToCluster = scala.collection.mutable.Map[AgentId, Int]()
    val clusters = scala.collection.mutable.Map[Int, Set[AgentId]]()
    var nextClusterId = 0

    similarities.foreach { case (id1, id2, _) =>
      (agentToCluster.get(id1), agentToCluster.get(id2)) match {
        case (None, None) =>
          // Create new cluster
          agentToCluster(id1) = nextClusterId
          agentToCluster(id2) = nextClusterId
          clusters(nextClusterId) = Set(id1, id2)
          nextClusterId += 1

        case (Some(cluster1), None) =>
          // Add id2 to existing cluster
          agentToCluster(id2) = cluster1
          clusters(cluster1) = clusters(cluster1) + id2

        case (None, Some(cluster2)) =>
          // Add id1 to existing cluster
          agentToCluster(id1) = cluster2
          clusters(cluster2) = clusters(cluster2) + id1

        case (Some(cluster1), Some(cluster2)) if cluster1 != cluster2 =>
          // Merge clusters
          val mergedCluster = clusters(cluster1) ++ clusters(cluster2)
          clusters(cluster1) = mergedCluster
          clusters.remove(cluster2)
          // Update all agents in cluster2 to point to cluster1
          mergedCluster.foreach(agentToCluster(_) = cluster1)

        case _ =>
        // Same cluster, no action needed
      }
    }

    clusters.values.toList
  }

  // Utility functions for statistical calculations

  private def pearsonCorrelation(x: List[Double], y: List[Double]): Double = {
    if (x.length != y.length || x.isEmpty) return 0.0

    val n = x.length
    val sumX = x.sum
    val sumY = y.sum
    val sumXY = x.zip(y).map { case (xi, yi) => xi * yi }.sum
    val sumXX = x.map(xi => xi * xi).sum
    val sumYY = y.map(yi => yi * yi).sum

    val numerator = n * sumXY - sumX * sumY
    val denominator = math.sqrt((n * sumXX - sumX * sumX) * (n * sumYY - sumY * sumY))

    if (denominator == 0) 0.0 else numerator / denominator
  }

  private def cosineSimilarity(x: List[Double], y: List[Double]): Double = {
    if (x.length != y.length || x.isEmpty) return 0.0

    val dotProduct = x.zip(y).map { case (xi, yi) => xi * yi }.sum
    val magnitudeX = math.sqrt(x.map(xi => xi * xi).sum)
    val magnitudeY = math.sqrt(y.map(yi => yi * yi).sum)

    if (magnitudeX == 0 || magnitudeY == 0) 0.0 else dotProduct / (magnitudeX * magnitudeY)
  }

  private def calculateAmountPatternSimilarity(amounts1: List[Double], amounts2: List[Double]): Double = {
    if (amounts1.isEmpty && amounts2.isEmpty) return 1.0
    if (amounts1.isEmpty || amounts2.isEmpty) return 0.0

    // Use Kolmogorov-Smirnov test statistic as dissimilarity measure
    val sorted1 = amounts1.sorted
    val sorted2 = amounts2.sorted
    val combined = (sorted1 ++ sorted2).distinct.sorted

    val ks = combined.map { value =>
      val cdf1 = sorted1.count(_ <= value).toDouble / sorted1.length
      val cdf2 = sorted2.count(_ <= value).toDouble / sorted2.length
      math.abs(cdf1 - cdf2)
    }.max

    1.0 - ks // Convert to similarity
  }

  private def calculateGasSimilarity(gas1: GasProfile, gas2: GasProfile): Double = {
    // Gas price range overlap
    val range1 = gas1.preferredGasPrice
    val range2 = gas2.preferredGasPrice
    val overlap = math.max(0, math.min(range1._2, range2._2) - math.max(range1._1, range2._1))
    val totalRange = math.max(range1._2, range2._2) - math.min(range1._1, range2._1)
    val rangeOverlap = if (totalRange == 0) 1.0 else overlap / totalRange

    // MEV sensitivity similarity
    val mevDiff = math.abs(gas1.mevSensitivity - gas2.mevSensitivity)
    val mevSimilarity = 1.0 - math.min(1.0, mevDiff)

    // Weighted combination
    0.7 * rangeOverlap + 0.3 * mevSimilarity
  }

  private def calculateContractUsageSimilarity(usage1: Map[String, Int], usage2: Map[String, Int]): Double = {
    val allContracts = usage1.keySet ++ usage2.keySet
    if (allContracts.isEmpty) return 1.0

    val vector1 = allContracts.toList.map(contract => usage1.getOrElse(contract, 0).toDouble)
    val vector2 = allContracts.toList.map(contract => usage2.getOrElse(contract, 0).toDouble)

    cosineSimilarity(vector1, vector2)
  }
}
