package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasCost

/**
 * Fiber-specific gas costs for orchestration operations.
 *
 * These costs are separate from the JsonLogic VM's GasConfig, which handles
 * expression evaluation costs. FiberGasConfig covers the overhead of
 * fiber engine operations like trigger dispatch and spawn processing.
 *
 * @param triggerEvent Cost to dispatch a trigger to another fiber
 * @param spawnDirective Cost to spawn a new child fiber
 * @param contextBuild Cost to build trigger/spawn context
 * @param dependencyMutation Cost per `_addDependency` / `_setDependencyActive` directive applied
 */
final case class FiberGasConfig(
  triggerEvent:       GasCost = GasCost(5),
  spawnDirective:     GasCost = GasCost(50),
  contextBuild:       GasCost = GasCost(10),
  dependencyMutation: GasCost = GasCost(20)
)

object FiberGasConfig {

  /** Default costs for development and testing */
  val Default: FiberGasConfig = FiberGasConfig()

  /** Production costs with higher fees */
  val Mainnet: FiberGasConfig = FiberGasConfig(
    triggerEvent = GasCost(10),
    spawnDirective = GasCost(100),
    contextBuild = GasCost(20),
    dependencyMutation = GasCost(40)
  )

  /** Minimal costs for testing gas exhaustion scenarios */
  val Minimal: FiberGasConfig = FiberGasConfig(
    triggerEvent = GasCost(1),
    spawnDirective = GasCost(1),
    contextBuild = GasCost(1),
    dependencyMutation = GasCost(1)
  )
}
