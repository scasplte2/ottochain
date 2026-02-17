# OttoChain Delegation E2E Tests - Troubleshooting Guide

This guide helps debug and resolve common issues with the delegation E2E test suite.

## Common Issues and Solutions

### 1. Test Compilation Errors

#### Missing Dependencies

**Error**:
```
[error] object xyz.kd5ujc.shared_data.testkit is not a member of package
```

**Solution**:
Ensure test dependencies are properly configured in `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "com.disneystreaming" %% "weaver-cats" % "0.8.3" % Test,
  "org.typelevel" %% "cats-effect" % "3.5.0" % Test,
  "org.scalameta" %% "munit" % "0.7.29" % Test
)
```

#### Package Structure Issues

**Error**:
```
[error] not found: object TestClusterSetup
```

**Solution**:
Verify the test files are in the correct directory structure:
```
tests/e2e/delegation/
├── DelegationFlowE2ESuite.scala
├── TestClusterSetup.scala
└── TestFixture.scala
```

### 2. Runtime Test Failures

#### Delegation Validation Failures

**Error**:
```
Agent reputation 45 below required 50
```

**Solution**:
The test agent's reputation is insufficient. Check the reputation building logic:

```scala
// Ensure enough attestations are processed
advancedReputation <- (1 to 10).toList.foldLeftM(initialReputation) { (state, _) =>
  ReputationScoring.updateReputationFromAttestation(state, "COMPLETION", 1.0)
}
```

#### Session Expiry Issues

**Error**:
```
delegation expired
```

**Solution**:
Test delegations may be expiring too quickly. Increase session duration:

```scala
// Use longer duration for stable tests
delegationSession <- DelegationManager.createDelegationSession(
  // ... other parameters ...
  21600L // 6 hours instead of very short durations
)
```

#### Spending Limit Violations

**Error**:
```
spending limit exceeded
```

**Solution**:
Transaction amounts may exceed delegation limits. Check the math:

```scala
// Ensure total transaction amounts don't exceed delegation limits
val totalTransactionAmount = transactions.flatMap(_.amount).sum
val delegationLimit = 5000L
assert(totalTransactionAmount <= delegationLimit)
```

### 3. Performance Test Failures

#### Latency Issues

**Error**:
```
Average latency 150ms exceeds maximum 100ms
```

**Diagnosis**:
1. Check system load during test execution
2. Verify mock cluster implementation efficiency
3. Look for blocking operations in test code

**Solution**:
```scala
// Optimize transaction processing
def submitDelegatedTransaction(...): IO[TransactionResult] = {
  // Use non-blocking operations
  for {
    validation <- validateTransactionAsync(...)
    result <- processTransactionAsync(...)
  } yield result
}
```

#### Throughput Problems

**Error**:
```
100 transactions processed in 12 seconds, expected < 10 seconds
```

**Solution**:
- Use parallel processing for independent operations:
```scala
// Process transactions in parallel
results <- transactions.parTraverse { tx =>
  cluster.submitDelegatedTransaction(tx, session, relayer)
}
```

#### Concurrent Test Failures

**Error**:
```
Concurrent operations failed due to race conditions
```

**Solution**:
Ensure proper synchronization in test cluster:
```scala
// Use Ref for thread-safe state management
activeDelegations: Ref[IO, Map[String, DelegationSession]]
```

### 4. Memory and Resource Issues

#### Memory Leaks in Long Tests

**Symptoms**:
- Tests slow down over time
- OutOfMemoryError in long-running tests

**Solution**:
Ensure proper resource cleanup:
```scala
test("long running test") {
  for {
    cluster <- TestClusterSetup.setupLocalCluster()
    // ... test logic ...
    _ <- cluster.shutdown() // Always cleanup
  } yield success
}
```

#### Resource Contention

**Error**:
```
Test timeout: operations taking longer than expected
```

**Solution**:
1. Reduce test parallelism if needed
2. Increase timeouts for resource-intensive operations
3. Use resource pools for shared test infrastructure

### 5. Integration Issues

#### Mock vs Real Cluster Discrepancies

**Problem**: Tests pass with mock cluster but fail with real tessellation cluster

**Diagnosis**:
1. Check if mock cluster behavior matches real cluster
2. Verify network communication and serialization
3. Look for timing differences between mock and real systems

**Solution**:
```scala
// Make mock behavior more realistic
def submitDelegatedTransaction(...): IO[TransactionResult] = {
  for {
    // Add realistic delay
    _ <- IO.sleep(50.milliseconds)
    // Add realistic validation
    validation <- performRealisticValidation(...)
    result <- processWithRealisticTiming(...)
  } yield result
}
```

#### State Synchronization Issues

**Error**:
```
Transaction state not found after confirmation timeout
```

**Solution**:
Ensure proper state propagation in test cluster:
```scala
def waitForTransactionConfirmation(...): IO[TransactionState] = {
  def poll: IO[TransactionState] = {
    for {
      state <- getCurrentTransactionState(transactionHash)
      result <- if (state.isConfirmed) IO.pure(state)
                else IO.sleep(100.milliseconds) >> poll
    } yield result
  }
  
  IO.race(poll, IO.sleep(timeout)).flatMap {
    case Left(state) => IO.pure(state)
    case Right(_) => IO.raiseError(new TimeoutException("Transaction not confirmed"))
  }
}
```

## Debugging Techniques

### 1. Enable Verbose Logging

Add detailed logging to understand test execution:

```scala
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("DelegationE2ETests")

test("delegation flow with logging") {
  for {
    _ <- IO.delay(logger.info("Starting delegation flow test"))
    cluster <- TestClusterSetup.setupLocalCluster()
    _ <- IO.delay(logger.info("Cluster setup complete"))
    
    // ... test steps with logging ...
    
    _ <- IO.delay(logger.info("Test completed successfully"))
  } yield success
}
```

### 2. Inspect Intermediate State

Add assertions for intermediate states to identify where failures occur:

```scala
test("delegation with state inspection") {
  for {
    delegationState <- createDelegationState()
    _ = expect(delegationState.totalStakeBonded > 0) // Verify stake
    
    session <- createDelegationSession(delegationState)
    _ = expect(session.expiresAt > System.currentTimeMillis()) // Verify expiry
    
    result <- submitDelegation(session)
    _ = expect(result.isValid) // Verify submission
    
  } yield success
}
```

### 3. Use Test Fixtures for Reproducible Scenarios

Create specific test scenarios that reproduce issues:

```scala
object DebuggingFixtures {
  def createProblematicDelegation(): IO[DelegationSession] = {
    // Create delegation that reproduces specific issue
    DelegationManager.createDelegationSession(
      agentAddress = "debug_agent",
      delegatorAddress = "debug_delegator",
      sessionPublicKey = "debug_session_key",
      scopedOperations = List("market"),
      stakeAmount = 100L, // Minimal stake for testing edge cases
      maxSpendLimit = 500L,
      durationSeconds = 10L // Short duration for expiry testing
    )
  }
}
```

### 4. Performance Profiling

Profile slow tests to identify bottlenecks:

```scala
test("performance profiling") {
  for {
    startTime <- IO.realTime
    _ <- performTestOperations()
    endTime <- IO.realTime
    duration = endTime - startTime
    
    _ = if (duration > 5.seconds) {
      println(s"SLOW TEST WARNING: took ${duration.toMillis}ms")
      // Add detailed timing for individual operations
    }
  } yield success
}
```

## Environment-Specific Issues

### 1. CI/CD Environment

**Issue**: Tests pass locally but fail in CI

**Common Causes**:
- Different JVM settings
- Network latency variations
- Resource constraints (CPU, memory)
- Parallel test execution interference

**Solutions**:
```yaml
# .github/workflows/test.yml adjustments
- name: Run Delegation Tests
  run: |
    sbt -J-Xmx2g -J-XX:+UseG1GC "testOnly *.DelegationFlowE2ESuite"
  env:
    DELEGATION_TEST_TIMEOUT: 60s
    JAVA_OPTS: "-XX:+UseG1GC -Xmx2g"
```

### 2. Different Operating Systems

**Issue**: Tests behave differently on Windows vs Linux/macOS

**Common Causes**:
- File path differences
- Timing precision variations
- Network stack differences

**Solution**:
Use OS-agnostic code and configuration:
```scala
val testTimeout = sys.env.get("DELEGATION_TEST_TIMEOUT")
  .map(Duration.apply)
  .getOrElse(30.seconds)
```

### 3. JVM Version Differences

**Issue**: Tests fail on different Java versions

**Solution**:
- Use compatible dependencies across JVM versions
- Test with multiple JVM versions in CI
- Use JVM-agnostic timing and threading code

## Test Data Management

### 1. Cleanup Between Tests

Ensure tests don't interfere with each other:

```scala
override def beforeEach(): Unit = {
  // Reset global state
  TestClusterSetup.resetGlobalState()
  
  // Clear any cached data
  DelegationCache.clear()
}
```

### 2. Deterministic Test Data

Use deterministic data generation for reproducible tests:

```scala
// Use fixed seeds for random data
val testRandom = new scala.util.Random(12345)

def generateTestAddress(prefix: String): String = {
  s"${prefix}_${testRandom.nextInt(10000).toString.padTo(4, '0')}"
}
```

## Performance Optimization

### 1. Mock Cluster Performance

Optimize mock cluster for faster test execution:

```scala
class OptimizedTestCluster {
  // Use mutable collections for performance
  private val delegationsRef = new AtomicReference(mutable.Map.empty[String, DelegationSession])
  
  // Batch operations for efficiency
  def submitMultipleDelegations(sessions: List[DelegationSession]): IO[List[DelegationResult]] = {
    IO.delay {
      sessions.map { session =>
        delegationsRef.updateAndGet(_ += (session.delegationId -> session))
        DelegationResult(session.delegationId, isValid = true)
      }
    }
  }
}
```

### 2. Parallel Test Execution

Structure tests for safe parallel execution:

```scala
// Use unique identifiers to avoid conflicts
test("parallel safe delegation test") {
  val testId = UUID.randomUUID().toString.take(8)
  val agentAddress = s"agent_$testId"
  val delegatorAddress = s"delegator_$testId"
  
  // ... rest of test logic ...
}
```

## Monitoring and Alerting

### 1. Test Result Tracking

Track test performance over time:

```scala
object TestMetrics {
  def recordTestExecution(testName: String, duration: Duration, success: Boolean): Unit = {
    // Log metrics for analysis
    println(s"TEST_METRIC: $testName, duration=${duration.toMillis}ms, success=$success")
  }
}
```

### 2. Performance Regression Detection

Set up alerts for performance degradation:

```scala
test("performance regression detection") {
  for {
    startTime <- IO.realTime
    _ <- performanceTestOperations()
    endTime <- IO.realTime
    duration = endTime - startTime
    
    // Alert if performance degrades significantly
    _ = if (duration > 15.seconds) {
      System.err.println(s"PERFORMANCE REGRESSION: Test took ${duration.toSeconds}s, expected < 10s")
    }
  } yield success
}
```

## Getting Help

### 1. Enable Debug Mode

Set environment variables for maximum debugging:

```bash
export DELEGATION_TEST_VERBOSE=true
export DELEGATION_TEST_TIMEOUT=300s
export SBT_OPTS="-XX:+UseG1GC -Xmx4g -verbose:gc"
```

### 2. Collect Debug Information

When reporting issues, include:

1. Full test output with stack traces
2. JVM version and system information
3. Test environment configuration
4. Timing information for failed operations
5. Memory usage patterns

### 3. Community Resources

- **GitHub Issues**: Report bugs with full reproduction steps
- **Documentation**: Check [README.md](README.md) for updated information
- **Team Chat**: Reach out for real-time troubleshooting help

---

If you encounter issues not covered in this guide, please create a GitHub issue with detailed reproduction steps and error messages.