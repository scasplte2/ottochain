# Delegation E2E Test Suite

## Overview

This directory contains comprehensive end-to-end tests for the complete delegated signing flow in OttoChain. The tests validate the entire delegation lifecycle from grant to revocation, including error conditions and security validations.

**Card:** 🧪 E2E Test: Full delegated signing flow (#699621c0b4a2e85a1c6e8068)  
**Epic:** Delegated Signing / Relayer Pattern  
**Status:** TDD Red Phase - All tests currently FAIL awaiting implementation

## Test Structure

### Test Files

1. **`example.json`** - Master test definition with 8 comprehensive test flows
2. **`delegation-definition.json`** - State machine definition for delegation lifecycle
3. **`delegation-initial-data.json`** - Initial state data for delegation tests
4. **Event Files:**
   - `event-grant-delegation.ts` - Principal grants delegation to agent
   - `event-create-fiber-delegated.ts` - Agent creates fiber using delegation
   - `event-transition-fiber-delegated.ts` - Agent transitions fiber using delegation
   - `event-revoke-delegation.ts` - Principal revokes delegation
   - `event-attempt-after-revocation.ts` - Failed attempt after revocation
   - `event-expire-delegation.ts` - Natural delegation expiration
   - `event-attempt-after-expiry.ts` - Failed attempt after expiration
   - `event-unauthorized-attempt.ts` - Unauthorized delegation attempt

### Test Actors

- **Principal** (`DAG123abc...`) - The user who grants delegation authority
- **Agent** (`DAG789def...`) - The relayer authorized to act on behalf of principal
- **Unauthorized** (`DAG999una...`) - Actor without delegation permissions

## Test Flows Covered

### 1. Complete Delegation Lifecycle - Happy Path
**Scenarios:** Grant → Create Fiber → Transition → Revoke  
**Validates:** Full delegation workflow with successful operations

### 2. Revocation Enforcement
**Scenarios:** Verify operations fail after delegation is revoked  
**Validates:** Security enforcement of revoked delegations

### 3. Expiration Enforcement  
**Scenarios:** Verify operations fail after delegation expires  
**Validates:** Time-based delegation validity enforcement

### 4. Authorization Validation
**Scenarios:** Verify unauthorized actors cannot use delegations  
**Validates:** Access control and authorization checks

### 5. Scope Enforcement
**Scenarios:** Verify operations outside scope are rejected  
**Validates:** Permission scope restrictions

### 6. Gas Limit Enforcement
**Scenarios:** Verify gas limits in delegation are enforced  
**Validates:** Resource usage restrictions

### 7. Session Key Management
**Scenarios:** Create, use, and manage session keys  
**Validates:** Session-based delegation approach

### 8. Batch Operations
**Scenarios:** Multiple operations in batch using delegation  
**Validates:** Efficiency and consistency of batch processing

## Expected Implementation Components

### Metagraph Layer
- **DelegationCredential** state machine with 4 states: pending → active → [revoked|expired]
- **Delegation context injection** in JLVM for transaction validation
- **Ownership bypass** in FiberRules.L0 for delegated operations
- **Automatic expiration** processing at ordinal boundaries

### Bridge Layer  
- **POST /delegation/submit** endpoint for delegated transaction submission
- **Delegation validation** middleware with signature verification
- **Error handling** with specific error codes (REVOKED, EXPIRED, NOT_FOUND, etc.)

### SDK Integration
- **DelegationManager** for lifecycle management
- **RelayerClient** for agent-side operations  
- **Session key support** with temporary signing authorities
- **Scope validation** utilities

## Validation Points

### State Validation
- Delegation records are properly stored in CalculatedState
- State transitions follow defined rules (pending→active→[revoked|expired])
- Usage statistics are tracked accurately
- Session keys are properly linked to parent delegations

### Security Validation
- Only principals can grant/revoke their delegations
- Revoked delegations cannot be used for new operations
- Expired delegations are automatically cleaned up
- Unauthorized actors are rejected with proper error codes

### Operation Validation
- Delegated operations are signed by agent but executed with principal permissions
- Fiber owners remain principals despite agent performing operations
- Gas limits and scope restrictions are enforced
- Operation history includes delegation context

## Error Codes Tested

| Code | Scenario | Expected Behavior |
|------|----------|-------------------|
| `DELEGATION_REVOKED` | Operation after revocation | Reject with revocation details |
| `DELEGATION_EXPIRED` | Operation after expiration | Reject with expiration details |
| `DELEGATION_NOT_FOUND` | Unauthorized delegation | Reject with available delegations |
| `INSUFFICIENT_PERMISSIONS` | Out-of-scope operation | Reject with scope details |
| `GAS_LIMIT_EXCEEDED` | Over gas allowance | Reject with usage details |
| `SESSION_EXPIRED` | Expired session key | Reject with session details |
| `INVALID_SIGNATURE` | Bad cryptographic signature | Reject with signature error |

## Running the Tests

```bash
# Navigate to e2e test directory
cd e2e-test

# Run specific delegation test
npm run test:delegation

# Run with verbose output
npm run test:delegation -- --verbose

# Run specific test flow
npm run test:delegation -- --flow="Complete Delegation Lifecycle"

# Run with state validation
npm run test:delegation -- --validate-state
```

## Integration Dependencies

### Required Components (Implementation Needed)
1. **Metagraph delegation validation** in ML0
2. **Bridge delegation endpoints** with proper validation
3. **SDK delegation methods** for client integration
4. **Session key management** system
5. **JLVM delegation context** variables
6. **Automatic expiration cleanup** processes

### Test Infrastructure  
- E2E test runner with delegation support
- State validation utilities
- Error assertion framework
- Multi-actor transaction simulation
- Ordinal-based time progression

## Expected Results

**Current Status:** ❌ All tests FAIL (TDD Red phase)

**After Implementation:** ✅ All test flows PASS demonstrating:
- Complete delegation lifecycle functionality
- Robust error handling and security enforcement
- Integration between metagraph, bridge, and SDK layers
- Production-ready delegation system

## Success Criteria

1. **Happy Path:** Complete lifecycle executes without errors
2. **Security:** All unauthorized attempts are properly rejected  
3. **Enforcement:** Revocation and expiration are strictly enforced
4. **Scope:** Permission and resource limits are respected
5. **Integration:** All components work together seamlessly
6. **Cleanup:** Expired delegations are automatically cleaned up

---

**Next Steps:** Implementation team should use these failing tests to guide development and ensure all delegation requirements are met! 🚀

All tests currently **FAIL** (TDD Red phase) - awaiting implementation to achieve the Green phase.