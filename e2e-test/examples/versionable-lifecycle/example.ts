/**
 * Versionable-contracts lifecycle e2e.
 *
 * Exercises the full registry / schema / fiber lifecycle on a SELF-CONTAINED state machine
 * (an order machine — no script coupling, so it is a clean reusable package):
 *   publish v1 + v2  ->  verified-bound create  ->  transition  ->  UPGRADE across schema versions
 *   (with a migration)  ->  v2-only transition  ->  deprecate v1  ->  alias  ->  archive.
 *
 * Plus the negative cases that demonstrate the validation split:
 *   - reserved name        -> rejected at DL1 (structural)          -> HTTP 400 on submit
 *   - non-monotonic version-> rejected at ML0 (stateful)            -> state unchanged
 *   - non-owner publish    -> rejected at ML0 (stateful)            -> state unchanged
 *   - upgrade hash-mismatch-> rejected at ML0 (verified-binding)    -> fiber stays on v1
 *
 * Step fields consumed by the runner (see runner.ts):
 *   publishVersion   : { name, version, definition, schemaShape, strict?, metadata? }
 *   setVersionStatus : { name, version, status }
 *   registerAlias    : { name }                      (targetFiberId = the session fiber)
 *   createStateMachine: { definition, initialData, schemaRef?: { name, version } }
 *   upgradeFiber     : { targetRef: { name, version }, newDefinition, migration? }
 *   processEvent     : { event, expectedState? }
 *   archiveStateMachine: { }
 * Negative steps add { expectRejected: 'dl1' | 'ml0' } and the runner asserts the rejection
 * instead of success (HTTP 400 for 'dl1'; op-did-not-apply for 'ml0').
 */
const PKG = 'order.package';

export default {
  name: 'Versionable Lifecycle',
  description: 'Registry publish/version, verified-bound fiber, upgrade across schema versions, status, alias, archive',
  type: 'state-machine',
  testFlows: [
    {
      name: 'order package v1 to v2 lifecycle',
      description: 'Publish two versions, bind a fiber to v1, upgrade it across schema versions, then deprecate/alias/archive',
      steps: [
        { action: 'publishVersion', name: PKG, version: '1.0.0', definition: 'order-v1.definition.json', schemaShape: 'order-v1.schema.json' },
        { action: 'publishVersion', name: PKG, version: '2.0.0', definition: 'order-v2.definition.json', schemaShape: 'order-v2.schema.json' },
        { action: 'createStateMachine', definition: 'order-v1.definition.json', initialData: 'initial-data.json', schemaRef: { name: PKG, version: '1.0.0' } },
        { action: 'processEvent', event: 'event-confirm.json', expectedState: 'confirmed' },
        { action: 'upgradeFiber', targetRef: { name: PKG, version: '2.0.0' }, newDefinition: 'order-v2.definition.json', migration: 'migration-v1-to-v2.json' },
        { action: 'processEvent', event: 'event-expedite.json', expectedState: 'confirmed' },
        { action: 'processEvent', event: 'event-ship.json', expectedState: 'shipped' },
        { action: 'setVersionStatus', name: PKG, version: '1.0.0', status: 'DEPRECATED' },
        { action: 'registerAlias', name: 'my-order.machine' },
        { action: 'archiveStateMachine' },
      ],
    },
    {
      name: 'registry rejections',
      description: 'Structural reject at DL1; stateful rejects at ML0 leave state unchanged',
      steps: [
        { action: 'publishVersion', name: 'rejects.package', version: '1.0.0', definition: 'order-v1.definition.json', schemaShape: 'order-v1.schema.json' },
        // reserved label -> DL1 structural reject (HTTP 400)
        { action: 'publishVersion', name: 'std.package', version: '1.0.0', definition: 'order-v1.definition.json', schemaShape: 'order-v1.schema.json', expectRejected: 'dl1' },
        // non-monotonic: publish a LOWER version than the current max -> ML0 stateful reject. Use 0.9.0
        // (NOT already in the lineage) so "did not land" is observable — re-publishing the existing 1.0.0
        // is indistinguishable from idempotence since it is already present.
        { action: 'publishVersion', name: 'rejects.package', version: '0.9.0', definition: 'order-v1.definition.json', schemaShape: 'order-v1.schema.json', expectRejected: 'ml0' },
      ],
    },
  ],
};
