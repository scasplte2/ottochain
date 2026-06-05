package xyz.kd5ujc.shared_data.lifecycle

import cats.data.ValidatedNec

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError

/**
 * Validation package for the lifecycle module.
 *
 * This package separates validation concerns by:
 * - **Domain**: Fiber operations vs Oracle operations
 * - **Layer**: L1 (structural, pre-consensus) vs L0 (contextual, with proofs)
 *
 * The key distinction:
 * - L1 validations run at Data-L1 during API ingestion (validateUpdate)
 * - L0 validations run at Metagraph-L0 with full state + proofs (validateSignedUpdate)
 */
package object validate {

  /** Standard validation result type using cats Validated with accumulated errors */
  type ValidationResult = ValidatedNec[DataApplicationValidationError, Unit]

  /** Constants for validation limits */
  object Limits {
    // State machine definition limits
    val MaxStates: Int = 100
    val MaxTransitions: Int = 500
    val MaxTransitionsPerState: Int = 20

    // Expression depth limits (prevents stack overflow during evaluation)
    val MaxExpressionDepth: Int = 100

    // Size limits (prevents memory exhaustion)
    val MaxInitialDataBytes: Long = 1048576L // 1MB
    val MaxEventPayloadBytes: Long = 102400L // 100KB
    val MaxRegistryBundleBytes: Long = 4194304L // 4MB (the base64 protobuf FileDescriptorSet)
    val MaxStringLength: Int = 10000
    val MaxArraySize: Int = 1000
    val MaxMapSize: Int = 100

    // Gas limits
    val MinGas: Long = 1L
    val MaxGas: Long = 10000000L
  }
}
