package xyz.kd5ujc.shared_data.examples

import io.constellationnetwork.currency.dataApplication.DataState

import xyz.kd5ujc.schema.{CalculatedState, OnChain}

/**
 * Test helpers and mocks for Delegation Relayer Pattern TDD tests.
 *
 * ⚠️ These are temporary mocks that will be replaced by actual implementations
 * from PR #90 (JLVM Delegation Operators) and this delegation spec.
 */
object DelegationRelayerTestHelpers {

  /**
   * Extension methods for DataState to add delegation support in tests.
   */
  implicit class DataStateExtensions(state: DataState[OnChain, CalculatedState]) {

    def withDelegations(delegations: Map[java.util.UUID, DelegationCredential]): DataState[OnChain, CalculatedState] = {
      // ⚠️ MOCK: Real implementation will store delegations in CalculatedState
      val updatedCalculated = state.calculated.copy(
        // This would actually be: delegations = delegations
        // But CalculatedState doesn't have delegations field yet
      )
      state.copy(calculated = updatedCalculated)
    }
  }

  /**
   * Extension methods for CalculatedState to add delegation access.
   */
  implicit class CalculatedStateExtensions(calculated: CalculatedState) {

    def delegations: Map[java.util.UUID, DelegationCredential] =
      // ⚠️ MOCK: Returns empty map until CalculatedState.delegations field exists
      Map.empty
  }
}

/**
 * Mock RevokeDelegation OttochainMessage.
 * ⚠️ This will be replaced by the actual implementation.
 */
object MockUpdates {

  case class RevokeDelegation(
    delegationId: String,
    reason:       String
  )

  // Add to Updates object in real implementation
  object RevokeDelegation {

    def apply(delegationId: String, reason: String): RevokeDelegation =
      new RevokeDelegation(delegationId, reason)
  }
}

/**
 * Mock delegation-aware ownership validation.
 * ⚠️ This will be implemented in FiberRules.L0 as updateSignedByOwnerOrDelegate.
 */
object MockFiberRules {

  import io.constellationnetwork.schema.address.Address
  import xyz.kd5ujc.schema.Records.StateMachineFiberRecord

  def updateSignedByOwnerOrDelegate(
    record:          StateMachineFiberRecord,
    signerSet:       Set[Address],
    calculatedState: CalculatedState,
    currentOrdinal:  Long
  ): Boolean = {
    // Standard owner check
    val ownerSigned = signerSet.intersect(record.owners).nonEmpty

    // Delegation check (mock implementation)
    val delegateSigned = signerSet.headOption.exists { signer =>
      // In real implementation, this would check CalculatedState.delegations
      // For now, return false to make tests fail as expected
      false
    }

    ownerSigned || delegateSigned
  }
}
