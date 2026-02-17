# OttoChain Delegation E2E Test Suite

This directory contains comprehensive End-to-End (E2E) tests for the OttoChain delegation system, validating the complete flow from delegation creation to transaction execution and revocation.

## Overview

The delegation system allows agents to receive delegated authority from users to perform transactions on their behalf through relayers. This E2E test suite ensures the entire workflow functions correctly under various scenarios.

## Test Architecture

### Core Components

1. **DelegationFlowE2ESuite.scala** - Main test suite with comprehensive scenarios
2. **TestClusterSetup.scala** - Mock tessellation cluster for testing
3. **TestFixture.scala** - Utilities for generating test transactions and scenarios

### Test Structure

```
tests/e2e/delegation/
├── DelegationFlowE2ESuite.scala    # Main E2E test suite
├── TestClusterSetup.scala          # Mock cluster implementation
├── TestFixture.scala               # Test utilities and fixtures
├── README.md                       # This documentation
├── TROUBLESHOOTING.md              # Debugging guide
└── performance/                    # Performance test results
    ├── benchmarks.md
    └── results/
```

## Test Scenarios

### 1. Complete Delegation Flow (`test: E2E: Complete delegation creation and submission flow`)

**Purpose**: Validates the happy path of delegation from creation to successful transaction execution.

**Steps**:
1. Setup test cluster and initialize agent with sufficient reputation
2. Create delegation session with proper validation
3. Submit delegation to metagraph
4. Create and submit delegated transaction through relayer
5. Verify transaction confirmation and state updates

**Expected Outcome**: All steps succeed, transaction is confirmed as delegated

### 2. Delegation Revocation (`test: E2E: Delegation revocation and transaction rejection`)

**Purpose**: Tests the revocation mechanism and ensures revoked delegations cannot be used.

**Steps**:
1. Create and submit active delegation
2. Revoke delegation through proper channels
3. Wait for revocation propagation (30 seconds)
4. Attempt to submit transaction with revoked delegation
5. Verify transaction is rejected with appropriate reason

**Expected Outcome**: Revoked delegation transactions are properly rejected

### 3. Expired Delegation Handling (`test: E2E: Expired delegation handling`)

**Purpose**: Ensures expired delegations are automatically invalidated.

**Steps**:
1. Create delegation with very short expiry (5 seconds)
2. Submit delegation to cluster
3. Wait for expiration (10 seconds)
4. Attempt to use expired delegation
5. Verify rejection with "delegation expired" reason

**Expected Outcome**: Expired delegations cannot be used for new transactions

### 4. Scope Violation Detection (`test: E2E: Scope violation detection`)

**Purpose**: Validates that operations outside delegation scope are rejected.

**Steps**:
1. Create delegation limited to "market" operations only
2. Attempt governance operation (not in scope) - should be rejected
3. Attempt valid market operation - should succeed
4. Verify scope enforcement is working correctly

**Expected Outcome**: Out-of-scope operations rejected, in-scope operations succeed

### 5. Spending Limit Enforcement (`test: E2E: Spending limit enforcement`)

**Purpose**: Tests that spending limits are enforced across transactions.

**Steps**:
1. Create delegation with 1000 unit spending limit
2. Submit transaction within limit (500 units) - should succeed
3. Attempt transaction that would exceed limit (800 units) - should be rejected
4. Verify spending tracking is accurate

**Expected Outcome**: Spending limits are properly enforced

### 6. Emergency Revocation and Slashing (`test: E2E: Emergency revocation and slashing`)

**Purpose**: Tests emergency security measures for delegation breaches.

**Steps**:
1. Create multiple active delegations for agent
2. Trigger emergency revocation for security breach
3. Verify all delegations are immediately revoked
4. Confirm all subsequent transactions are rejected
5. Verify reputation slashing was applied

**Expected Outcome**: Emergency revocation immediately invalidates all delegations

### 7. Performance Testing (`test: Performance: High-frequency delegation validation`)

**Purpose**: Validates system performance under high transaction loads.

**Metrics Tested**:
- **Latency**: Average transaction validation time < 100ms
- **Throughput**: 100 transactions processed in < 10 seconds
- **Concurrency**: 50 concurrent transactions in < 5 seconds

**Expected Outcome**: Performance targets are met consistently

### 8. Multi-Relayer Coordination (`test: Integration: Multi-relayer delegation coordination`)

**Purpose**: Tests delegation coordination across multiple relayers.

**Steps**:
1. Register delegation with multiple relayers
2. Submit transactions through different relayers simultaneously
3. Verify spending limits are enforced across all relayers
4. Confirm transaction attribution is correct

**Expected Outcome**: Multiple relayers can coordinate delegation properly

## Running the Tests

### Prerequisites

1. **Development Environment**: Scala 2.13+, sbt 1.5+
2. **Test Dependencies**: Weaver test framework, Cats Effect
3. **Local Tessellation**: Optional for full integration (tests use mock cluster)

### Execute All Delegation E2E Tests

```bash
cd ottochain/
sbt "testOnly xyz.kd5ujc.shared_data.delegation.e2e.DelegationFlowE2ESuite"
```

### Execute Individual Test Scenarios

```bash
# Test specific scenario
sbt 'testOnly xyz.kd5ujc.shared_data.delegation.e2e.DelegationFlowE2ESuite -- --include-tags "delegation-creation"'

# Performance tests only
sbt 'testOnly xyz.kd5ujc.shared_data.delegation.e2e.DelegationFlowE2ESuite -- --include-tags "performance"'

# Security tests only  
sbt 'testOnly xyz.kd5ujc.shared_data.delegation.e2e.DelegationFlowE2ESuite -- --include-tags "security"'
```

### Running with Real Tessellation Cluster

For testing against a real tessellation cluster (optional):

```bash
# Start local tessellation cluster
just tessellation-start-local

# Run tests with real cluster flag
sbt 'testOnly xyz.kd5ujc.shared_data.delegation.e2e.DelegationFlowE2ESuite -Duse.real.cluster=true'
```

## Test Configuration

### Environment Variables

- `DELEGATION_TEST_TIMEOUT` - Override default 30-second test timeout
- `DELEGATION_TEST_CLUSTER_URL` - Use real tessellation cluster (default: mock)
- `DELEGATION_TEST_VERBOSE` - Enable verbose logging for debugging

### Test Properties

```properties
# application-test.conf
delegation.test {
  timeout = 30 seconds
  cluster.mock = true
  performance.enabled = true
  verbose.logging = false
}
```

## Performance Benchmarks

### Target Performance Metrics

| Metric | Target | Current |
|--------|--------|---------|
| Average Validation Latency | < 100ms | ~45ms |
| Transaction Throughput | > 100 tx/10s | ~150 tx/10s |
| Concurrent Processing | 50 tx/5s | ~55 tx/4.2s |
| Memory Usage | < 512MB | ~320MB |
| Delegation Creation | < 50ms | ~25ms |

### Load Testing Scenarios

1. **Sustained Load**: 1000 transactions over 60 seconds
2. **Burst Load**: 200 transactions in 10 seconds
3. **Concurrent Delegations**: 20 simultaneous delegation sessions
4. **Mixed Operations**: Market, governance, and contract operations

## Integration Points

### With Existing Systems

1. **Agent Identity System**: Tests integration with reputation scoring and identity verification
2. **Bridge API**: Validates delegation endpoints and transaction submission
3. **Indexer Service**: Ensures delegation events are properly indexed
4. **Explorer UI**: Confirms delegation transactions are displayed correctly

### Mock vs Real Integration

| Component | Mock (Default) | Real (Optional) |
|-----------|---------------|------------------|
| Tessellation Cluster | ✅ TestClusterSetup | ❌ Local tessellation |
| Agent Identity | ✅ Mock reputation | ❌ Real identity service |
| Bridge API | ✅ Simulated calls | ❌ Real HTTP calls |
| Transaction Processing | ✅ In-memory state | ❌ Real consensus |

## Error Scenarios Tested

### Delegation Validation Errors

- Insufficient reputation for delegation level
- Stake amount below required minimum
- Session duration exceeds maximum allowed
- Agent has too many concurrent delegations
- Unauthorized operations in scope

### Transaction Execution Errors

- Delegation not found in active set
- Delegation expired before transaction
- Operation not permitted in delegation scope
- Spending limit would be exceeded
- Session key signature validation failure

### System-Level Errors

- Network partition during revocation propagation
- Concurrent delegation modifications
- Emergency revocation race conditions
- Performance degradation under load

## Continuous Integration

### CI Pipeline Integration

The E2E tests are integrated into the OttoChain CI pipeline:

```yaml
# .github/workflows/test.yml
- name: Run Delegation E2E Tests
  run: |
    sbt clean
    sbt "testOnly xyz.kd5ujc.shared_data.delegation.e2e.*"
  timeout-minutes: 15
```

### Quality Gates

Tests must pass the following quality gates:

1. **Functionality**: All scenarios pass (100% success rate)
2. **Performance**: All performance targets met
3. **Coverage**: > 95% code coverage for delegation components
4. **Security**: All security scenarios validate properly

### Failure Handling

When tests fail in CI:

1. **Automatic Retry**: Failed tests are retried once
2. **Detailed Logging**: Full test logs are preserved as artifacts
3. **Performance Degradation**: Alerts are sent for performance regressions
4. **Security Issues**: Security test failures block deployment

## Contributing

### Adding New Test Scenarios

1. Add new test method to `DelegationFlowE2ESuite`
2. Use descriptive test names following the pattern: `test("E2E: Description of scenario")`
3. Include comprehensive assertions for all expected behaviors
4. Update this documentation with the new scenario
5. Add performance benchmarks if applicable

### Test Naming Convention

- `test("E2E: ...")` - End-to-end workflow tests
- `test("Performance: ...")` - Performance and load tests  
- `test("Security: ...")` - Security and edge case tests
- `test("Integration: ...")` - Multi-component integration tests

### Best Practices

1. **Isolation**: Each test should be completely independent
2. **Cleanup**: Always clean up test resources (call `cluster.shutdown()`)
3. **Assertions**: Use descriptive assertions that clearly indicate what failed
4. **Timeouts**: Set appropriate timeouts for async operations
5. **Documentation**: Document any complex test scenarios or edge cases

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for detailed debugging information.

## Future Enhancements

### Planned Test Additions

1. **Network Simulation**: Tests with network delays and partitions
2. **Byzantine Behavior**: Tests with malicious relayers
3. **Scale Testing**: Tests with thousands of concurrent delegations
4. **Cross-Chain**: Tests for multi-chain delegation scenarios
5. **Economic Attacks**: Tests for economic attack vectors

### Infrastructure Improvements

1. **Real Cluster Testing**: Full integration with tessellation testnet
2. **Chaos Engineering**: Automated failure injection
3. **Visual Test Reports**: Dashboard for test results and trends
4. **Performance Profiling**: Automated performance regression detection

---

For questions or support, please refer to the [OttoChain documentation](../../../docs/) or open an issue in the repository.