package xyz.kd5ujc.shared_data.identity.agent

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._
import weaver.SimpleIOSuite
import java.util.UUID

/**
 * Comprehensive test suite for OttoChain Agent Identity & Reputation Integration
 *
 * Tests the complete agent identity system including:
 * - Reputation scoring and updates
 * - Delegation authority management
 * - Stake bonding and slashing
 * - Sybil resistance mechanisms
 * - Platform verification integration
 * - End-to-end delegation flows
 */
object AgentIdentityIntegrationSuite extends SimpleIOSuite {

  // Test addresses
  val testAgentAddress = "agent_123"
  val testDelegatorAddress = "delegator_456"
  val testSessionKey = "session_key_789"

  test("reputation scoring: new agent initialization and basic scoring") {
    for {
      // Initialize new agent
      initialState <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))

      // Verify initial state
      _ = expect.all(
        initialState.address == testAgentAddress,
        initialState.baseReputation == ReputationScoring.BASE_REPUTATION,
        initialState.components.performance == 50.0,
        initialState.components.reliability == 80.0,
        initialState.components.specialization == 30.0,
        initialState.components.network == 0.0,
        initialState.delegationCapability.level == "BASIC" // Initial score should qualify for BASIC
      )

      // Process completion attestation
      stateAfterCompletion <- ReputationScoring.updateReputationFromAttestation(
        initialState,
        "COMPLETION",
        1.0
      )

      _ = expect.all(
        stateAfterCompletion.baseReputation == ReputationScoring.BASE_REPUTATION + ReputationScoring.COMPLETION_SCORE,
        stateAfterCompletion.completionCount == 1,
        stateAfterCompletion.components.performance > initialState.components.performance
      )

      // Process violation attestation
      stateAfterViolation <- ReputationScoring.updateReputationFromAttestation(
        stateAfterCompletion,
        "VIOLATION",
        1.0
      )

      _ = expect.all(
        stateAfterViolation.baseReputation == ReputationScoring.BASE_REPUTATION + ReputationScoring.COMPLETION_SCORE + ReputationScoring.VIOLATION_PENALTY,
        stateAfterViolation.violationCount == 1,
        stateAfterViolation.components.reliability < stateAfterCompletion.components.reliability
      )

    } yield success
  }

  test("reputation scoring: delegation level progression") {
    for {
      // Start with new agent
      initialState <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))

      // Build reputation to ADVANCED level
      advancedState <- (1 to 8).toList.foldLeftM(initialState) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }

      _ = expect.all(
        advancedState.delegationCapability.level == "ADVANCED",
        advancedState.delegationCapability.maxStakeAmount == 5000L,
        advancedState.delegationCapability.maxSessionDuration == 43200L
      )

      // Build reputation to EXPERT level
      expertState <- (1 to 12).toList.foldLeftM(advancedState) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "BEHAVIORAL", 1.0)
      }

      _ = expect.all(
        expertState.compositeScore >= ReputationScoring.DELEGATION_EXPERT_THRESHOLD,
        expertState.delegationCapability.level == "EXPERT",
        expertState.delegationCapability.maxStakeAmount == 10000L,
        expertState.delegationCapability.allowedOperations.contains("corporate")
      )

    } yield success
  }

  test("delegation management: session creation and validation") {
    for {
      // Initialize agent with reputation
      reputationState <- IO.pure(ReputationScoring.initializeAgent(testAgentAddress))

      // Build reputation for BASIC level
      advancedReputation <- (1 to 5).toList.foldLeftM(reputationState) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }

      // Initialize delegation state
      delegationState = DelegationManager.initializeAgentDelegationState(testAgentAddress, advancedReputation)

      // Bond stake for delegation
      stakeBond <- DelegationManager.bondStakeForDelegation(testAgentAddress, 1000L, "BASIC")

      updatedDelegationState = delegationState.copy(
        stakeBonds = List(stakeBond),
        totalStakeBonded = 1000L
      )

      // Validate delegation request
      validationResult <- DelegationManager.validateDelegationRequest(
        updatedDelegationState,
        requestedOperations = List("market", "contract"),
        stakeAmount = 500L,
        sessionDuration = 21600L, // 6 hours
        maxSpendLimit = 1000L
      )

      _ = expect(validationResult.isValid)

      // Create delegation session
      session <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionKey,
        List("market", "contract"),
        500L,
        1000L,
        21600L
      )

      // Verify session validity
      isValid <- DelegationManager.isDelegationValid(session)

      _ = expect.all(
        session.agentAddress == testAgentAddress,
        session.delegatorAddress == testDelegatorAddress,
        session.stakeAmount == 500L,
        session.scopedOperations.contains("market"),
        isValid
      )

    } yield success
  }

  test("delegation management: revocation and slashing") {
    for {
      // Create delegation session
      session <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        testSessionKey,
        List("market"),
        500L,
        1000L,
        3600L // 1 hour
      )

      // Revoke delegation
      revokedSession <- DelegationManager.revokeDelegation(
        session,
        testDelegatorAddress,
        "Security concern"
      )

      _ = expect.all(
        revokedSession.isRevoked,
        revokedSession.revokedBy.contains(testDelegatorAddress),
        revokedSession.revokedReason.contains("Security concern")
      )

      // Verify revoked session is invalid
      isValidAfterRevocation <- DelegationManager.isDelegationValid(revokedSession)
      _ = expect(!isValidAfterRevocation)

      // Test stake slashing
      stakeBond   <- DelegationManager.bondStakeForDelegation(testAgentAddress, 1000L, "BASIC")
      slashedBond <- DelegationManager.slashStake(stakeBond, 0.25) // 25% slash

      _ = expect(slashedBond.slashedAmount == 250L)

    } yield success
  }

  test("identity integration: agent registration and platform linking") {
    for {
      // Register new agent
      agentIdentity <- IdentityIntegration.registerAgent(
        testAgentAddress,
        "public_key_123",
        "TestAgent",
        Some(
          IdentityIntegration.PlatformIdentityLink(
            "github",
            "user123",
            "testuser",
            System.currentTimeMillis() / 1000,
            IdentityIntegration.BasicVerified,
            80.0
          )
        )
      )

      _ = expect.all(
        agentIdentity.address == testAgentAddress,
        agentIdentity.displayName == "TestAgent",
        agentIdentity.registrationStatus == IdentityIntegration.PendingVerification,
        agentIdentity.platformLinks.length == 1,
        agentIdentity.sybilResistanceScore > 0
      )

      // Add additional platform link
      updatedAgentIdentity <- IdentityIntegration.addPlatformLink(
        agentIdentity,
        "telegram",
        "123456789",
        "@testuser",
        "tg_verified_proof"
      )

      _ = expect.all(
        updatedAgentIdentity.platformLinks.length == 2,
        updatedAgentIdentity.sybilResistanceScore > agentIdentity.sybilResistanceScore
      )

    } yield success
  }

  test("sybil resistance: multi-factor verification") {
    for {
      // Create agent with multiple platform links
      platformLinks <- IO.pure(
        List(
          IdentityIntegration
            .PlatformIdentityLink("github", "user1", "dev1", 1000, IdentityIntegration.FullyVerified, 100.0),
          IdentityIntegration
            .PlatformIdentityLink("telegram", "user2", "dev1", 1000, IdentityIntegration.BasicVerified, 80.0),
          IdentityIntegration.PlatformIdentityLink(
            "discord",
            "user3",
            "dev1",
            1000,
            IdentityIntegration.BasicVerified,
            70.0
          )
        )
      )

      // Calculate diversity score
      diversityScore <- IO.pure(IdentityIntegration.calculatePlatformDiversityScore(platformLinks))
      _ = expect(diversityScore == 60.0) // 3/5 platforms = 60%

      // Calculate verification quality score
      qualityScore         <- IO.pure(IdentityIntegration.calculateVerificationQualityScore(platformLinks))
      expectedQualityScore <- IO.pure((100.0 + 80.0 + 70.0) / (3 * 100.0) * 100.0)
      _ = expect(math.abs(qualityScore - expectedQualityScore) < 0.1)

      // Test Sybil resistance requirements
      reputationState <- (1 to 10).toList.foldLeftM(ReputationScoring.initializeAgent(testAgentAddress)) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }

      agentIdentity <- IdentityIntegration.registerAgent(
        testAgentAddress,
        "public_key_123",
        "TestAgent"
      )

      updatedAgentIdentity = agentIdentity.copy(
        platformLinks = platformLinks,
        reputationState = reputationState,
        delegationState = agentIdentity.delegationState.copy(
          totalStakeBonded = 500L,
          reputationState = reputationState
        )
      )

      // Check requirements
      requirementCheck <- IdentityIntegration.checkSybilResistanceRequirements(
        updatedAgentIdentity,
        IdentityIntegration.DefaultSybilResistanceRequirements
      )

      _ = expect.all(
        requirementCheck.isValid,
        requirementCheck.errors.isEmpty
      )

    } yield success
  }

  test("sybil resistance: suspicious pattern detection") {
    for {
      // Create agents with suspicious patterns
      suspiciousAgents <- IO.pure((1 to 6).map { i =>
        val reputationState = ReputationScoring.AgentReputationState(
          address = s"agent_$i",
          baseReputation = 75, // Identical high scores - suspicious
          components = ReputationScoring.ReputationComponents(90.0, 90.0, 90.0, 90.0, 0),
          compositeScore = 75.0,
          delegationCapability = ReputationScoring.DelegationCapability("ADVANCED", 5000L, 43200L, List("market")),
          attestationCount = 10,
          completionCount = 2, // Low completion vs high reputation - suspicious
          violationCount = 0,
          vouchCount = 15, // High vouch count with low completion - gaming
          lastUpdated = System.currentTimeMillis() - 1000 // Very recent - rapid growth
        )
        reputationState
      }.toList)

      // Detect Sybil patterns
      suspiciousAddresses <- ReputationScoring.detectSybilPatterns(suspiciousAgents)

      _ = expect.all(
        suspiciousAddresses.length == 6, // All should be flagged
        suspiciousAddresses.contains("agent_1"),
        suspiciousAddresses.contains("agent_6")
      )

    } yield success
  }

  test("end-to-end: complete delegation workflow") {
    for {
      // 1. Register agent
      agentIdentity <- IdentityIntegration.registerAgent(
        testAgentAddress,
        "public_key_123",
        "WorkflowTestAgent"
      )

      // 2. Add platform verification
      verifiedAgentIdentity <- IdentityIntegration.addPlatformLink(
        agentIdentity,
        "github",
        "workflowtest",
        "workflowtest",
        "github_verified_12345"
      )

      // 3. Build reputation through attestations
      agentWithReputation <- (1 to 8).toList.foldLeftM(verifiedAgentIdentity) { (identity, i) =>
        IdentityIntegration.processAttestation(
          identity,
          if (i % 3 == 0) "BEHAVIORAL" else "COMPLETION",
          "github",
          s"evidence_$i"
        )
      }

      // 4. Bond stake for delegation
      stakeBond <- DelegationManager.bondStakeForDelegation(testAgentAddress, 2000L, "ADVANCED")

      updatedDelegationState = agentWithReputation.delegationState.copy(
        stakeBonds = List(stakeBond),
        totalStakeBonded = 2000L
      )

      finalAgentIdentity = agentWithReputation.copy(delegationState = updatedDelegationState)

      // 5. Validate delegation capabilities
      validationResult <- DelegationManager.validateDelegationRequest(
        finalAgentIdentity.delegationState,
        requestedOperations = List("market", "governance"),
        stakeAmount = 1000L,
        sessionDuration = 21600L,
        maxSpendLimit = 5000L
      )

      _ = expect(validationResult.isValid)

      // 6. Create delegation session
      delegationSession <- DelegationManager.createDelegationSession(
        testAgentAddress,
        testDelegatorAddress,
        "session_key_workflow",
        List("market", "governance"),
        1000L,
        5000L,
        21600L
      )

      // 7. Verify session validity
      isSessionValid <- DelegationManager.isDelegationValid(delegationSession)
      _ = expect(isSessionValid)

      // 8. Complete successful delegation
      updatedAgentState <- DelegationManager.updateDelegationMetrics(
        finalAgentIdentity.delegationState.copy(activeDelegations = List(delegationSession)),
        delegationSession,
        wasSuccessful = true
      )

      // 9. Verify final state
      delegationStats <- DelegationManager.getAgentDelegationStats(updatedAgentState)

      _ = expect.all(
        delegationStats("successful_delegations").asInstanceOf[Int] == 1,
        delegationStats("failed_delegations").asInstanceOf[Int] == 0,
        delegationStats("success_rate").asInstanceOf[Double] == 1.0,
        updatedAgentState.reputationState.compositeScore > finalAgentIdentity.reputationState.compositeScore
      )

    } yield success
  }

  test("security: emergency revocation and slashing") {
    for {
      // Setup agent with active delegations
      reputationState <- (1 to 10).toList.foldLeftM(ReputationScoring.initializeAgent(testAgentAddress)) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
      }

      delegationState = DelegationManager.initializeAgentDelegationState(testAgentAddress, reputationState)

      // Create multiple active delegations
      session1 <- DelegationManager.createDelegationSession(
        testAgentAddress,
        "delegator1",
        "key1",
        List("market"),
        500L,
        1000L,
        3600L
      )
      session2 <- DelegationManager.createDelegationSession(
        testAgentAddress,
        "delegator2",
        "key2",
        List("governance"),
        750L,
        1500L,
        7200L
      )

      agentStateWithDelegations = delegationState.copy(
        activeDelegations = List(session1, session2),
        successfulDelegations = 5
      )

      // Trigger emergency revocation
      emergencyRevokedState <- DelegationManager.emergencyRevokeAllDelegations(
        agentStateWithDelegations,
        "Security breach detected"
      )

      _ = expect.all(
        emergencyRevokedState.activeDelegations.forall(_.isRevoked),
        emergencyRevokedState.activeDelegations.forall(_.revokedBy.contains("SYSTEM")),
        emergencyRevokedState.slashingEvents == 1,
        emergencyRevokedState.failedDelegations == agentStateWithDelegations.failedDelegations + 2,
        emergencyRevokedState.reputationState.compositeScore < agentStateWithDelegations.reputationState.compositeScore
      )

    } yield success
  }

  test("integration: comprehensive agent identity summary") {
    for {
      // Create fully featured agent identity
      reputationState <- (1 to 12).toList.foldLeftM(ReputationScoring.initializeAgent(testAgentAddress)) { (state, i) =>
        val attestationType = if (i % 4 == 0) "VOUCH" else if (i % 3 == 0) "BEHAVIORAL" else "COMPLETION"
        ReputationScoring.updateReputationFromAttestation(state, attestationType, 1.0)
      }

      agentIdentity <- IdentityIntegration.registerAgent(
        testAgentAddress,
        "comprehensive_key",
        "ComprehensiveAgent"
      )

      // Add multiple platform links
      withGithub <- IdentityIntegration.addPlatformLink(
        agentIdentity,
        "github",
        "comp123",
        "comprehensive",
        "github_verified_comp"
      )
      withTelegram <- IdentityIntegration.addPlatformLink(
        withGithub,
        "telegram",
        "987654321",
        "@comprehensive",
        "tg_comp_verified"
      )
      withDiscord <- IdentityIntegration.addPlatformLink(
        withTelegram,
        "discord",
        "comp#1234",
        "comprehensive",
        "discord_comp_verified"
      )

      // Update with reputation and delegation state
      stakeBond <- DelegationManager.bondStakeForDelegation(testAgentAddress, 5000L, "EXPERT")

      finalAgentIdentity = withDiscord.copy(
        reputationState = reputationState,
        delegationState = DelegationManager
          .initializeAgentDelegationState(testAgentAddress, reputationState)
          .copy(
            stakeBonds = List(stakeBond),
            totalStakeBonded = 5000L,
            successfulDelegations = 25,
            failedDelegations = 2
          )
      )

      // Get comprehensive summary
      summary <- IdentityIntegration.getAgentIdentitySummary(finalAgentIdentity)

      _ = expect.all(
        summary("address").asInstanceOf[String] == testAgentAddress,
        summary("display_name").asInstanceOf[String] == "ComprehensiveAgent",
        summary("delegation_level").asInstanceOf[String] == "EXPERT",
        summary("platform_links").asInstanceOf[List[_]].length == 3,
        summary.contains("delegation_stats"),
        summary.contains("sybil_resistance_score")
      )

    } yield success
  }
}
