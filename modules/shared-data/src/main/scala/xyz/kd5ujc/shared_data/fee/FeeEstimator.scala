package xyz.kd5ujc.shared_data.fee

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.Updates.OttochainMessage

import io.circe.syntax._

/**
 * Estimates the fee for an OttochainMessage based on the fee schedule.
 *
 * Fee = baseFee(operationType) + payloadSurcharge(serializedBytes)
 *
 * Payload surcharge applies for messages exceeding `config.baselineSizeBytes`:
 *   surcharge = ceil((payloadBytes - baseline) / 1024) * perKbFee
 *
 * This is a pure, synchronous computation — no effects needed.
 * Use this in HTTP estimation endpoints and pre-flight client checks.
 */
object FeeEstimator {

  /**
   * Estimates the fee for the given update using the provided config.
   *
   * The payload size is measured from the canonical JSON serialization
   * (UTF-8 bytes of the compact JSON string).
   *
   * @param config  FeeConfig with per-operation base fees and surcharge params.
   * @param update  The OttochainMessage to estimate a fee for.
   * @return        Estimated fee in datum (smallest unit; 1 DAG = 100,000,000 datum).
   */
  def estimateFee(config: FeeConfig)(update: OttochainMessage): Long = {
    import Updates.OttochainMessage._ // bring messageEncoder into scope

    val baseFee: Long = update match {
      case _: Updates.CreateStateMachine     => config.createStateMachineFee
      case _: Updates.TransitionStateMachine => config.transitionFee
      case _: Updates.ArchiveStateMachine    => config.archiveFee
      case _: Updates.CreateScript           => config.createScriptFee
      case _: Updates.InvokeScript           => config.invokeFee
    }

    val payloadBytes: Long =
      update.asJson.noSpaces.getBytes("UTF-8").length.toLong

    val surcharge: Long =
      if (payloadBytes > config.baselineSizeBytes)
        math
          .ceil(
            (payloadBytes - config.baselineSizeBytes).toDouble / 1024.0
          )
          .toLong * config.perKbFee
      else 0L

    baseFee + surcharge
  }
}
