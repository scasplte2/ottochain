package xyz.kd5ujc.shared_test

import xyz.kd5ujc.shared_data.testkit.TestClusterSetup.TestTransaction
import java.util.UUID

/**
 * Test fixtures and utilities for delegation E2E testing
 */
object TestFixture {

  /**
   * Create a market transaction for testing
   */
  def createMarketTransaction(
    delegatorAddress: String,
    sessionKey: String,
    operation: String,
    amount: Option[Long] = None,
    transactionId: Option[String] = None
  ): TestTransaction = {
    TestTransaction(
      delegatorAddress = delegatorAddress,
      sessionKey = sessionKey,
      operationType = "market",
      operation = operation,
      amount = amount,
      transactionId = transactionId.getOrElse(UUID.randomUUID().toString)
    )
  }

  /**
   * Create a governance transaction for testing
   */
  def createGovernanceTransaction(
    delegatorAddress: String,
    sessionKey: String,
    operation: String,
    amount: Option[Long] = None,
    transactionId: Option[String] = None
  ): TestTransaction = {
    TestTransaction(
      delegatorAddress = delegatorAddress,
      sessionKey = sessionKey,
      operationType = "governance",
      operation = operation,
      amount = amount,
      transactionId = transactionId.getOrElse(UUID.randomUUID().toString)
    )
  }

  /**
   * Create a contract transaction for testing
   */
  def createContractTransaction(
    delegatorAddress: String,
    sessionKey: String,
    operation: String,
    amount: Option[Long] = None,
    transactionId: Option[String] = None
  ): TestTransaction = {
    TestTransaction(
      delegatorAddress = delegatorAddress,
      sessionKey = sessionKey,
      operationType = "contract",
      operation = operation,
      amount = amount,
      transactionId = transactionId.getOrElse(UUID.randomUUID().toString)
    )
  }

  /**
   * Generate test agent addresses
   */
  def generateAgentAddress(suffix: String = ""): String = {
    val uuid = UUID.randomUUID().toString.take(8)
    if (suffix.nonEmpty) s"agent_${suffix}_$uuid" else s"agent_$uuid"
  }

  /**
   * Generate test session keys
   */
  def generateSessionKey(suffix: String = ""): String = {
    val uuid = UUID.randomUUID().toString.take(8)
    if (suffix.nonEmpty) s"session_${suffix}_$uuid" else s"session_$uuid"
  }

  /**
   * Generate test delegator addresses
   */
  def generateDelegatorAddress(suffix: String = ""): String = {
    val uuid = UUID.randomUUID().toString.take(8)
    if (suffix.nonEmpty) s"delegator_${suffix}_$uuid" else s"delegator_$uuid"
  }

  /**
   * Generate test relayer addresses
   */
  def generateRelayerAddress(suffix: String = ""): String = {
    val uuid = UUID.randomUUID().toString.take(8)
    if (suffix.nonEmpty) s"relayer_${suffix}_$uuid" else s"relayer_$uuid"
  }

  /**
   * Create test data for bulk transaction testing
   */
  def createBulkTestTransactions(
    count: Int,
    delegatorAddress: String,
    sessionKey: String,
    operationType: String = "market",
    baseAmount: Long = 100L
  ): List[TestTransaction] = {
    (1 to count).map { i =>
      TestTransaction(
        delegatorAddress = delegatorAddress,
        sessionKey = sessionKey,
        operationType = operationType,
        operation = s"${operationType}_operation_$i",
        amount = Some(baseAmount + i),
        transactionId = Some(s"bulk_tx_$i")
      )
    }.toList
  }

  /**
   * Create realistic test scenarios
   */
  object Scenarios {

    /**
     * Market maker delegation scenario
     */
    def marketMakerDelegation(
      agentAddress: String,
      delegatorAddress: String,
      sessionKey: String
    ): (List[TestTransaction], Long, List[String]) = {
      val transactions = List(
        createMarketTransaction(delegatorAddress, sessionKey, "create_market", Some(1000L)),
        createMarketTransaction(delegatorAddress, sessionKey, "add_liquidity", Some(5000L)),
        createMarketTransaction(delegatorAddress, sessionKey, "adjust_spread", Some(0L)),
        createMarketTransaction(delegatorAddress, sessionKey, "withdraw_fees", Some(200L))
      )
      val totalSpending = 6200L
      val requiredOperations = List("market")
      
      (transactions, totalSpending, requiredOperations)
    }

    /**
     * DAO governance delegation scenario
     */
    def daoGovernanceDelegation(
      agentAddress: String,
      delegatorAddress: String,
      sessionKey: String
    ): (List[TestTransaction], Long, List[String]) = {
      val transactions = List(
        createGovernanceTransaction(delegatorAddress, sessionKey, "create_proposal", Some(100L)),
        createGovernanceTransaction(delegatorAddress, sessionKey, "vote", Some(0L)),
        createGovernanceTransaction(delegatorAddress, sessionKey, "delegate_votes", Some(0L)),
        createGovernanceTransaction(delegatorAddress, sessionKey, "execute_proposal", Some(50L))
      )
      val totalSpending = 150L
      val requiredOperations = List("governance")
      
      (transactions, totalSpending, requiredOperations)
    }

    /**
     * Multi-domain delegation scenario
     */
    def multiDomainDelegation(
      agentAddress: String,
      delegatorAddress: String,
      sessionKey: String
    ): (List[TestTransaction], Long, List[String]) = {
      val transactions = List(
        createMarketTransaction(delegatorAddress, sessionKey, "create_market", Some(1000L)),
        createGovernanceTransaction(delegatorAddress, sessionKey, "vote", Some(0L)),
        createContractTransaction(delegatorAddress, sessionKey, "deploy_contract", Some(500L)),
        createMarketTransaction(delegatorAddress, sessionKey, "close_position", Some(300L)),
        createContractTransaction(delegatorAddress, sessionKey, "call_function", Some(100L))
      )
      val totalSpending = 1900L
      val requiredOperations = List("market", "governance", "contract")
      
      (transactions, totalSpending, requiredOperations)
    }

    /**
     * High-frequency trading delegation scenario
     */
    def highFrequencyTradingDelegation(
      agentAddress: String,
      delegatorAddress: String,
      sessionKey: String,
      transactionCount: Int = 50
    ): (List[TestTransaction], Long, List[String]) = {
      val baseAmount = 10L
      val transactions = (1 to transactionCount).map { i =>
        val operation = if (i % 2 == 0) "buy_order" else "sell_order"
        createMarketTransaction(
          delegatorAddress, 
          sessionKey, 
          operation, 
          Some(baseAmount + (i % 10)), // Vary amounts slightly
          Some(s"hft_$i")
        )
      }.toList
      
      val totalSpending = transactions.flatMap(_.amount).sum
      val requiredOperations = List("market")
      
      (transactions, totalSpending, requiredOperations)
    }

    /**
     * Error-inducing scenario for negative testing
     */
    def errorScenarios(
      agentAddress: String,
      delegatorAddress: String,
      sessionKey: String
    ): List[(TestTransaction, String)] = {
      List(
        // Unauthorized operation
        (createGovernanceTransaction(delegatorAddress, sessionKey, "admin_action"), "operation not in scope"),
        
        // Excessive spending
        (createMarketTransaction(delegatorAddress, sessionKey, "large_trade", Some(1000000L)), "spending limit exceeded"),
        
        // Invalid operation format
        (TestTransaction(
          delegatorAddress, 
          sessionKey, 
          "invalid_type", 
          "malformed_operation"
        ), "invalid operation type"),
        
        // Empty transaction
        (TestTransaction(
          delegatorAddress, 
          sessionKey, 
          "", 
          ""
        ), "empty operation")
      )
    }
  }

  /**
   * Performance testing utilities
   */
  object Performance {
    
    /**
     * Generate transactions for latency testing
     */
    def generateLatencyTestTransactions(
      count: Int,
      delegatorAddress: String,
      sessionKey: String
    ): List[TestTransaction] = {
      (1 to count).map { i =>
        createMarketTransaction(
          delegatorAddress,
          sessionKey,
          "latency_test",
          Some(1L),
          Some(s"latency_test_$i")
        )
      }.toList
    }

    /**
     * Generate transactions for throughput testing
     */
    def generateThroughputTestTransactions(
      count: Int,
      delegatorAddress: String,
      sessionKey: String
    ): List[TestTransaction] = {
      (1 to count).map { i =>
        val operationType = List("market", "governance", "contract")(i % 3)
        TestTransaction(
          delegatorAddress = delegatorAddress,
          sessionKey = sessionKey,
          operationType = operationType,
          operation = s"throughput_test_$operationType",
          amount = Some((i % 100).toLong + 1),
          transactionId = Some(s"throughput_$i")
        )
      }.toList
    }

    /**
     * Generate concurrent test batches
     */
    def generateConcurrentTestBatches(
      batchCount: Int,
      transactionsPerBatch: Int,
      delegatorAddress: String,
      sessionKey: String
    ): List[List[TestTransaction]] = {
      (1 to batchCount).map { batchId =>
        (1 to transactionsPerBatch).map { txId =>
          createMarketTransaction(
            delegatorAddress,
            sessionKey,
            s"concurrent_batch_${batchId}_tx_$txId",
            Some(10L),
            Some(s"concurrent_${batchId}_$txId")
          )
        }.toList
      }.toList
    }
  }

  /**
   * Validation utilities for test results
   */
  object Validation {

    /**
     * Validate transaction results against expected outcomes
     */
    def validateTransactionResults(
      actualResults: List[TestClusterSetup.TransactionResult],
      expectedSuccesses: Int,
      expectedFailures: Int
    ): Boolean = {
      val successes = actualResults.count(_.isAccepted)
      val failures = actualResults.count(_.isRejected)
      
      successes == expectedSuccesses && failures == expectedFailures
    }

    /**
     * Validate performance metrics
     */
    def validatePerformanceMetrics(
      totalTime: scala.concurrent.duration.Duration,
      transactionCount: Int,
      maxAvgLatencyMs: Double,
      maxTotalTimeSeconds: Double
    ): Boolean = {
      val avgLatencyMs = totalTime.toMillis.toDouble / transactionCount
      val totalTimeSeconds = totalTime.toSeconds.toDouble
      
      avgLatencyMs <= maxAvgLatencyMs && totalTimeSeconds <= maxTotalTimeSeconds
    }

    /**
     * Validate delegation state consistency
     */
    def validateDelegationStateConsistency(
      delegationSession: xyz.kd5ujc.shared_data.identity.agent.DelegationManager.DelegationSession,
      transactionResults: List[TestClusterSetup.TransactionResult],
      expectedSpentAmount: Long
    ): Boolean = {
      val actualSpent = transactionResults
        .filter(_.isAccepted)
        .flatMap(_.amount)
        .sum
        
      val allWithinScope = transactionResults
        .filter(_.isAccepted)
        .forall(result => 
          // This would need to be implemented based on the actual transaction structure
          true // Simplified for now
        )
        
      actualSpent == expectedSpentAmount && 
      actualSpent <= delegationSession.maxSpendLimit &&
      allWithinScope
    }
  }
}