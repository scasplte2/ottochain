package xyz.kd5ujc.shared_data.fee

import io.constellationnetwork.schema.address.Address

/**
 * Fee configuration for OttoChain data transactions.
 *
 * When `enabled = false`, fees are not enforced (grace period / testnet mode).
 * Clients may still submit voluntary fees which will be recorded on-chain.
 *
 * When `enabled = true` and `required = true`, every data transaction MUST
 * include a paired FeeTransaction or it will be rejected at DL1/L0.
 *
 * @param enabled          Master switch. false = estimation only, no enforcement.
 * @param required         If true, a FeeTransaction MUST accompany every DataUpdate.
 * @param treasuryAddress  Destination for all fee payments. None when disabled.
 * @param createStateMachineFee  Base fee for CreateStateMachine ops (datum).
 * @param transitionFee          Base fee for TransitionStateMachine ops (datum).
 * @param archiveFee             Base fee for ArchiveStateMachine ops (datum, 0 = free).
 * @param createScriptFee        Base fee for CreateScript ops (datum).
 * @param invokeFee              Base fee for InvokeScript ops (datum).
 * @param baselineSizeBytes      Payload size threshold before surcharge kicks in.
 * @param perKbFee               Additional datum charged per KB over baseline.
 */
case class FeeConfig(
  enabled:               Boolean,
  required:              Boolean,
  treasuryAddress:       Option[Address],
  createStateMachineFee: Long,
  transitionFee:         Long,
  archiveFee:            Long,
  createScriptFee:       Long,
  invokeFee:             Long,
  baselineSizeBytes:     Int,
  perKbFee:              Long
)

object FeeConfig {

  /**
   * Disabled / grace-period configuration.
   * Fees are estimated but never enforced. No treasury address required.
   * This is the default for backward compatibility.
   */
  val disabled: FeeConfig = FeeConfig(
    enabled = false,
    required = false,
    treasuryAddress = None,
    createStateMachineFee = 1_000_000L,
    transitionFee = 100_000L,
    archiveFee = 0L,
    createScriptFee = 1_000_000L,
    invokeFee = 100_000L,
    baselineSizeBytes = 4096,
    perKbFee = 10_000L
  )

  /**
   * Creates an enforcement-mode fee config with the specified treasury address.
   *
   * All fee amounts use the standard schedule unless overridden.
   */
  def enabled(
    treasuryAddress:       Address,
    createStateMachineFee: Long = 1_000_000L,
    transitionFee:         Long = 100_000L,
    archiveFee:            Long = 0L,
    createScriptFee:       Long = 1_000_000L,
    invokeFee:             Long = 100_000L,
    baselineSizeBytes:     Int = 4096,
    perKbFee:              Long = 10_000L
  ): FeeConfig = FeeConfig(
    enabled = true,
    required = true,
    treasuryAddress = Some(treasuryAddress),
    createStateMachineFee = createStateMachineFee,
    transitionFee = transitionFee,
    archiveFee = archiveFee,
    createScriptFee = createScriptFee,
    invokeFee = invokeFee,
    baselineSizeBytes = baselineSizeBytes,
    perKbFee = perKbFee
  )

  /**
   * Validates a config and returns Either[error, FeeConfig].
   * Fails if enabled=true but no treasury address provided.
   */
  def fromConfig(
    enabled:               Boolean,
    required:              Boolean,
    treasuryAddress:       Option[Address],
    createStateMachineFee: Long = 1_000_000L,
    transitionFee:         Long = 100_000L,
    archiveFee:            Long = 0L,
    createScriptFee:       Long = 1_000_000L,
    invokeFee:             Long = 100_000L,
    baselineSizeBytes:     Int = 4096,
    perKbFee:              Long = 10_000L
  ): Either[String, FeeConfig] =
    if (enabled && treasuryAddress.isEmpty)
      Left("treasury address is required when fees are enabled")
    else
      Right(
        FeeConfig(
          enabled,
          required,
          treasuryAddress,
          createStateMachineFee,
          transitionFee,
          archiveFee,
          createScriptFee,
          invokeFee,
          baselineSizeBytes,
          perKbFee
        )
      )
}
