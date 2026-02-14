package xyz.kd5ujc.shared_data.sybil

import cats.effect.IO
import cats.implicits._
import io.circe.{Json, parser}
import java.security.MessageDigest
import java.util.Base64
import scala.util.{Try, Success, Failure}

/**
 * Hardware attestation system for detecting shared hardware across multiple agent identities.
 *
 * This component implements hardware fingerprinting techniques including:
 * - TEE/SGX attestation verification
 * - CPU signature analysis
 * - System configuration fingerprinting
 * - Device uniqueness scoring
 */
class HardwareAttestation(config: SybilDetectionConfig) {

  /**
   * Generate hardware fingerprint for the current system.
   * This should be called by legitimate agents during registration.
   */
  def generateFingerprint(): IO[HardwareFingerprint] = for {
    teeAttestation <- generateTEEAttestation()
    cpuSignature   <- extractCPUSignature()
    systemHash     <- computeSystemHash()
    timestamp = System.currentTimeMillis()
  } yield HardwareFingerprint(
    teeAttestation = teeAttestation,
    cpuSignature = cpuSignature,
    systemHash = systemHash,
    timestamp = timestamp
  )

  /**
   * Verify the authenticity of a hardware fingerprint.
   * Returns (isValid, confidence) where confidence is 0.0-1.0
   */
  def verifyFingerprint(fingerprint: HardwareFingerprint): IO[(Boolean, Double)] = for {
    teeValid    <- verifyTEEAttestation(fingerprint.teeAttestation)
    cpuValid    <- verifyCPUSignature(fingerprint.cpuSignature)
    systemValid <- verifySystemHash(fingerprint.systemHash)
    age = calculateFingerprintAge(fingerprint.timestamp)
  } yield {
    val validity = teeValid && cpuValid && systemValid && age < 3600000 // 1 hour max age
    val confidence = calculateConfidence(teeValid, cpuValid, systemValid, age)
    (validity, confidence)
  }

  /**
   * Detect potential hardware sharing across multiple agent identities.
   */
  def detectHardwareSharing(
    fingerprints: Map[AgentId, HardwareFingerprint]
  ): IO[List[SuspiciousCluster]] = IO {
    val sharedHardwareGroups = findSharedHardware(fingerprints)

    sharedHardwareGroups.map { case (sharedFeatures, agents) =>
      val suspicionScore = calculateHardwareSharingSuspicion(sharedFeatures, agents.size)

      SuspiciousCluster(
        agents = agents,
        suspicionScore = suspicionScore,
        behaviorType = SuspiciousBehaviorType.IdenticalBehavior,
        evidence = List(SuspicionEvidence.HardwareFingerprinting(agents)),
        detectedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Calculate similarity between two hardware fingerprints.
   * Returns similarity score from 0.0 to 1.0.
   */
  def calculateHardwareSimilarity(
    fp1: HardwareFingerprint,
    fp2: HardwareFingerprint
  ): Double = {
    val weights = Map(
      "tee"    -> 0.4, // TEE attestation is most reliable
      "cpu"    -> 0.3, // CPU signature is fairly unique
      "system" -> 0.3 // System hash provides additional entropy
    )

    val teeScore = compareTEEAttestation(fp1.teeAttestation, fp2.teeAttestation)
    val cpuScore = compareCPUSignature(fp1.cpuSignature, fp2.cpuSignature)
    val systemScore = compareSystemHash(fp1.systemHash, fp2.systemHash)

    weights("tee") * teeScore + weights("cpu") * cpuScore + weights("system") * systemScore
  }

  // TEE/SGX Attestation Methods

  private def generateTEEAttestation(): IO[Option[String]] = IO {
    // In a real implementation, this would interface with TEE/SGX APIs
    // For now, we'll simulate the attestation process
    if (config.requireHardwareAttestation) {
      // Check if TEE/SGX is available
      detectTEECapability()
        .map { capability =>
          if (capability.isAvailable) {
            // Generate mock attestation signature
            val attestationData = s"${capability.teeType}:${capability.version}:${System.currentTimeMillis()}"
            Some(generateMockAttestation(attestationData))
          } else {
            None
          }
        }
        .getOrElse(None)
    } else {
      None
    }
  }

  private def verifyTEEAttestation(attestation: Option[String]): IO[Boolean] = IO {
    attestation match {
      case Some(att) => verifyAttestationSignature(att)
      case None      => !config.requireHardwareAttestation // Valid only if not required
    }
  }

  private def compareTEEAttestation(att1: Option[String], att2: Option[String]): Double =
    (att1, att2) match {
      case (Some(a1), Some(a2)) if a1 == a2 => 1.0 // Identical attestations (suspicious)
      case (Some(_), Some(_))               => 0.0 // Different attestations (good)
      case (None, None)                     => 0.5 // Both missing (neutral)
      case _                                => 0.0 // One missing, one present (different systems)
    }

  // CPU Signature Methods

  private def extractCPUSignature(): IO[String] = IO {
    // Extract CPU model, features, and capabilities
    val cpuInfo = getCPUInfo()
    val features = getCPUFeatures()
    val performance = getCPUPerformanceSignature()

    // Create a deterministic signature
    val combined = s"${cpuInfo.model}:${cpuInfo.family}:${features.sorted.mkString(",")}:$performance"
    hashString(combined)
  }

  private def verifyCPUSignature(signature: String): IO[Boolean] = IO {
    // Verify the CPU signature format and plausibility
    signature.length >= 32 && signature.matches("[a-fA-F0-9]+")
  }

  private def compareCPUSignature(sig1: String, sig2: String): Double =
    if (sig1 == sig2) 1.0 else hammingDistance(sig1, sig2)

  // System Hash Methods

  private def computeSystemHash(): IO[String] = IO {
    val systemInfo = collectSystemInfo()
    hashString(systemInfo)
  }

  private def verifySystemHash(hash: String): IO[Boolean] = IO {
    hash.length >= 32 && hash.matches("[a-fA-F0-9]+")
  }

  private def compareSystemHash(hash1: String, hash2: String): Double =
    if (hash1 == hash2) 1.0 else hammingDistance(hash1, hash2)

  // Hardware Sharing Detection

  private def findSharedHardware(
    fingerprints: Map[AgentId, HardwareFingerprint]
  ): List[(Set[String], Set[AgentId])] = {
    val fpList = fingerprints.toList
    val threshold = 0.95 // Very high threshold for hardware sharing

    val sharedGroups = scala.collection.mutable.ListBuffer[(Set[String], Set[AgentId])]()

    for {
      i <- fpList.indices
      j <- fpList.indices if i < j
      (agent1, fp1) = fpList(i)
      (agent2, fp2) = fpList(j)
      similarity = calculateHardwareSimilarity(fp1, fp2)
      if similarity >= threshold
    } {
      val sharedFeatures = identifySharedFeatures(fp1, fp2)
      val agents = Set(agent1, agent2)
      sharedGroups += ((sharedFeatures, agents))
    }

    // Merge overlapping groups
    mergeOverlappingGroups(sharedGroups.toList)
  }

  private def calculateHardwareSharingSuspicion(sharedFeatures: Set[String], agentCount: Int): Double = {
    val featureWeight = sharedFeatures.size * 0.2
    val countWeight = math.min(1.0, agentCount / 10.0) * 0.8
    math.min(1.0, featureWeight + countWeight)
  }

  private def identifySharedFeatures(fp1: HardwareFingerprint, fp2: HardwareFingerprint): Set[String] = {
    val features = scala.collection.mutable.Set[String]()

    if (fp1.teeAttestation == fp2.teeAttestation && fp1.teeAttestation.isDefined) {
      features += "tee_attestation"
    }
    if (fp1.cpuSignature == fp2.cpuSignature) {
      features += "cpu_signature"
    }
    if (fp1.systemHash == fp2.systemHash) {
      features += "system_hash"
    }

    features.toSet
  }

  private def mergeOverlappingGroups(
    groups: List[(Set[String], Set[AgentId])]
  ): List[(Set[String], Set[AgentId])] = {
    // Simple implementation - could be optimized with Union-Find
    val merged = scala.collection.mutable.ListBuffer[(Set[String], Set[AgentId])]()

    groups.foreach { case (features1, agents1) =>
      val overlapping = merged.zipWithIndex.find { case ((_, agents2), _) =>
        agents1.intersect(agents2).nonEmpty
      }

      overlapping match {
        case Some(((features2, agents2), index)) =>
          // Merge with existing group
          val mergedFeatures = features1.intersect(features2)
          val mergedAgents = agents1.union(agents2)
          merged(index) = (mergedFeatures, mergedAgents)
        case None =>
          // Add as new group
          merged += ((features1, agents1))
      }
    }

    merged.toList
  }

  // Utility Methods

  private def calculateFingerprintAge(timestamp: Long): Long =
    System.currentTimeMillis() - timestamp

  private def calculateConfidence(
    teeValid:    Boolean,
    cpuValid:    Boolean,
    systemValid: Boolean,
    age:         Long
  ): Double = {
    val validityScore = List(teeValid, cpuValid, systemValid).count(identity) / 3.0
    val ageScore = math.max(0.0, 1.0 - (age.toDouble / 3600000.0)) // Decay over 1 hour
    (validityScore + ageScore) / 2.0
  }

  private def hashString(input: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString
  }

  private def hammingDistance(s1: String, s2: String): Double = {
    if (s1.length != s2.length) return 0.0
    val differences = s1.zip(s2).count { case (c1, c2) => c1 != c2 }
    1.0 - (differences.toDouble / s1.length)
  }

  // Mock implementations for system info gathering
  // In production, these would interface with actual system APIs

  private case class CPUInfo(model: String, family: String, vendor: String)
  private case class TEECapability(isAvailable: Boolean, teeType: String, version: String)

  private def detectTEECapability(): Option[TEECapability] =
    // Mock detection - in real implementation would check for SGX/TrustZone/etc
    Try {
      val hasIntelSGX = checkIntelSGX()
      val hasArmTrustZone = checkArmTrustZone()

      if (hasIntelSGX) {
        Some(TEECapability(true, "Intel_SGX", "2.0"))
      } else if (hasArmTrustZone) {
        Some(TEECapability(true, "ARM_TrustZone", "1.0"))
      } else {
        Some(TEECapability(false, "None", "0.0"))
      }
    }.getOrElse(None)

  private def checkIntelSGX(): Boolean =
    // Mock implementation - would check CPUID and SGX support
    scala.util.Random.nextBoolean()

  private def checkArmTrustZone(): Boolean =
    // Mock implementation - would check ARM security extensions
    scala.util.Random.nextBoolean()

  private def getCPUInfo(): CPUInfo =
    // Mock implementation - would read from /proc/cpuinfo or equivalent
    CPUInfo("Intel Core i7-12700K", "6", "GenuineIntel")

  private def getCPUFeatures(): List[String] =
    // Mock implementation - would extract CPU feature flags
    List("sse", "sse2", "avx", "avx2", "aes", "rdrand", "rdseed")

  private def getCPUPerformanceSignature(): String =
    // Mock implementation - would run micro-benchmarks
    "perf_12345"

  private def collectSystemInfo(): String =
    // Mock implementation - would collect system configuration
    s"os:linux:kernel:5.15.0:mem:32GB:disk:1TB:${System.currentTimeMillis()}"

  private def generateMockAttestation(data: String): String = {
    // Mock attestation generation - in production would use TEE APIs
    val signature = hashString(s"ATTESTATION:$data:${scala.util.Random.nextLong()}")
    Base64.getEncoder.encodeToString(signature.getBytes)
  }

  private def verifyAttestationSignature(attestation: String): Boolean =
    // Mock verification - in production would verify against TEE root keys
    Try {
      val decoded = Base64.getDecoder.decode(attestation)
      decoded.length >= 32
    }.getOrElse(false)
}
