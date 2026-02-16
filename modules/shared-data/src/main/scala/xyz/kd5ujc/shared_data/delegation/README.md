# Gas Optimization: Batch Operations & Signature Aggregation

## Overview

This module implements gas optimization strategies to reduce delegation overhead from 30% to <20% through:

1. **Batch Operation Processing** (60% gas savings)
2. **BLS Signature Aggregation** (40% signature savings)  
3. **State Coalescing** (25% state savings)
4. **Attack Prevention** (Hard gas limits per operation type)

## Key Components

### Session Key Foundation (Phase 1)
- `SessionKey`: Core delegation authority with expiry and permissions
- `DelegationPermissions`: Operation and contract scoping
- `OperationLimits`: Gas limits, amount limits, time windows

### Batch Operations (Phase 2)
- `BatchOperation`: Individual operation within a batch
- `BatchRequest`: Collection of operations with aggregated signatures
- `BatchResult`: Execution results with gas optimization metrics

### Core Processors
- `BatchProcessor`: Main orchestrator for batch execution
- `SignatureAggregator`: BLS signature aggregation for multi-user batches
- `SessionKeyValidator`: Session key validation and permission checking
- `GasMonitor`: Gas usage tracking and baseline enforcement

### Attack Prevention
- Maximum batch size: 100 operations
- Maximum gas limit: 10M gas per batch
- Operation-specific gas limits (transfer: 100K, governance_vote: 150K, etc.)
- Suspicious pattern detection for spam prevention

## Gas Optimization Achievements

### Target: <20% Overhead vs Direct Transactions

1. **Batch Processing (60% savings)**
   - Single validation for multiple operations
   - Optimized operation ordering
   - Reduced consensus overhead

2. **State Coalescing (25% savings)**
   - Combine repeated operations to same targets
   - Aggregate amounts and parameters
   - Reduce state transition overhead

3. **Signature Aggregation (40% savings)**
   - BLS signatures for multi-user batches
   - Single verification for multiple signatures
   - Reduced cryptographic overhead

## Usage Example

```scala
import xyz.kd5ujc.shared_data.delegation._

// Create session key with permissions
val sessionKey = SessionKey(
  keyId = "agent-session-123",
  publicKey = "0x...",
  owner = agentAddress,
  permissions = DelegationPermissions(
    contracts = Set("market", "governance"),
    operations = Set("transfer", "vote"),
    limits = OperationLimits(
      maxAmount = Some(BigDecimal(1000)),
      timeWindowHours = 24
    )
  ),
  expiryTime = Instant.now().plusHours(24),
  createdAt = Instant.now()
)

// Create batch operations
val operations = List(
  BatchOperation(
    operationId = "transfer-1",
    sessionKeyId = "agent-session-123", 
    operationType = "transfer",
    payload = Json.obj("target" -> Json.fromString("user-123"), "amount" -> Json.fromDouble(100)),
    gasEstimate = 80000L,
    timestamp = Instant.now()
  ),
  // ... more operations
)

// Submit batch
val batchRequest = BatchRequest(
  batchId = "batch-001",
  operations = operations,
  aggregatedSignature = None, // Will be added during processing
  submitter = relayerAddress,
  maxGasLimit = 2000000L,
  deadline = Instant.now().plusMinutes(5)
)

// Process batch
val processor = new BatchProcessor(signatureAggregator, sessionValidator, gasMonitor)
val result = processor.submitBatch(batchRequest)
```

## Security Features

### Session Key Security
- Maximum 24-hour expiry
- Contract and operation scoping
- Amount limits per operation
- Real-time revocation support

### Attack Prevention
- Batch size limits prevent DoS
- Gas limits prevent resource exhaustion  
- Suspicious pattern detection
- Operation type validation

### Validation Pipeline
1. Session key validity check
2. Permission validation (operation, contract, amount)
3. Gas limit enforcement
4. Deadline validation
5. Signature aggregation and verification

## Performance Metrics

The system tracks:
- **Gas optimization savings**: Percentage saved vs individual operations
- **Execution time**: Batch processing latency
- **Success rate**: Operations executed vs failed
- **Coalescing efficiency**: State operations combined

Target performance:
- <20% overhead vs direct transactions
- <5 second batch processing time
- >95% success rate for valid operations
- 25%+ savings from operation coalescing

## Integration Points

### Bridge API Integration
- Bridge validates session keys before batch submission
- Signature aggregation in bridge before metagraph submission
- Gas monitoring integration for fee calculation

### Metagraph Integration  
- Batch operations processed as single metagraph transaction
- State coalescing applied during consensus
- Gas monitoring integrated with tessellation gas model

### Future Enhancements
- Intent layer for semantic validation (Phase 2B)
- Hardware Security Module integration
- Cross-chain delegation support
- Advanced reputation-based gas pricing