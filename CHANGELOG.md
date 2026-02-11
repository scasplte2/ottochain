# Changelog

## [0.6.4](https://github.com/scasplte2/ottochain/compare/v0.6.3...v0.6.4) (2026-02-11)


### Bug Fixes

* **ci:** trigger docker build on GitHub release published event ([#59](https://github.com/scasplte2/ottochain/issues/59)) ([c0464e0](https://github.com/scasplte2/ottochain/commit/c0464e00dc1e083ecb2db4f71ab55a6308401c32))

## [0.6.3](https://github.com/scasplte2/ottochain/compare/v0.6.2...v0.6.3) (2026-02-10)


### Bug Fixes

* **ci:** strip whitespace from sbt version output ([ed8492f](https://github.com/scasplte2/ottochain/commit/ed8492fd82133523d92afcd741ee91f4e7888127))
* **ci:** trigger release on GitHub release published event ([#58](https://github.com/scasplte2/ottochain/issues/58)) ([361de4c](https://github.com/scasplte2/ottochain/commit/361de4cdbe7d2a470e215b208e8b029798a597ac))

## [0.6.2](https://github.com/scasplte2/ottochain/compare/v0.6.1...v0.6.2) (2026-02-10)


### Bug Fixes

* **ci:** add workflow_dispatch trigger to release.yml ([#55](https://github.com/scasplte2/ottochain/issues/55)) ([3a1f9ab](https://github.com/scasplte2/ottochain/commit/3a1f9abfade20d32350846e7fe1c2ec5b5eab776))

## [0.6.1](https://github.com/scasplte2/ottochain/compare/v0.6.0...v0.6.1) (2026-02-10)


### Features

* add /version endpoint to ML0 and DL1 custom routes ([#38](https://github.com/scasplte2/ottochain/issues/38)) ([cad5d9c](https://github.com/scasplte2/ottochain/commit/cad5d9c3bd749ad9879ea45bf4dd6df430dbc21c))
* add Docker build for full metagraph stack ([#39](https://github.com/scasplte2/ottochain/issues/39)) ([6abc9d4](https://github.com/scasplte2/ottochain/commit/6abc9d42da666109346616e2fe7b03104abf8ef1))
* add FiberOrdinal as sequence number ([0e6c81d](https://github.com/scasplte2/ottochain/commit/0e6c81df5260470bc3a230e95cbfb23a2d5217f1))
* add ML0 rejection notification dispatch ([#33](https://github.com/scasplte2/ottochain/issues/33)) ([6e35e1b](https://github.com/scasplte2/ottochain/commit/6e35e1b9566af886b6afbb93997b4517f77ffb20))
* add replay protection via targeted sequence number ([4b60215](https://github.com/scasplte2/ottochain/commit/4b60215f90ba393e863e7177147885c10b1f846f))
* Add ScalaPB protobuf definitions for OttoChain types ([#10](https://github.com/scasplte2/ottochain/issues/10)) ([f3e9a19](https://github.com/scasplte2/ottochain/commit/f3e9a198a4396b97abecb46529fba6ca0bcce4ee))
* add scoverage test coverage reporting ([#43](https://github.com/scasplte2/ottochain/issues/43)) ([80c68a2](https://github.com/scasplte2/ottochain/commit/80c68a221d40c0953185964f32b1e51139e26306))
* add sdk lib and update e2e tests ([4b60215](https://github.com/scasplte2/ottochain/commit/4b60215f90ba393e863e7177147885c10b1f846f))
* add Sequenced trait for type-level ordering of OttochainMessage ([#44](https://github.com/scasplte2/ottochain/issues/44)) ([6b054fb](https://github.com/scasplte2/ottochain/commit/6b054fb3d00c1942dd2e6d59f8c569abe660368d))
* add Token Escrow state machine example ([#6](https://github.com/scasplte2/ottochain/issues/6)) ([b5e8625](https://github.com/scasplte2/ottochain/commit/b5e862585bd3b512fcc81ff77d1ba11f530b11bc))
* automate JAR build pipeline ([#46](https://github.com/scasplte2/ottochain/issues/46)) ([f7022ec](https://github.com/scasplte2/ottochain/commit/f7022ec0ae4fd43274c713de50986e975d7d6a3c))
* **ci:** add release-please for automated releases ([#53](https://github.com/scasplte2/ottochain/issues/53)) ([5944817](https://github.com/scasplte2/ottochain/commit/5944817d26b2c3b1861918afc00e7f5ebf346147))
* **e2e:** ML0 stall detection and failure diagnostics ([#40](https://github.com/scasplte2/ottochain/issues/40)) ([2304aba](https://github.com/scasplte2/ottochain/commit/2304aba7c886ad61f85eef0c4d80255e8070616f))
* **e2e:** ordinal-based confirmation with auto-resubmit ([#41](https://github.com/scasplte2/ottochain/issues/41)) ([3accc3a](https://github.com/scasplte2/ottochain/commit/3accc3a8d268a80da82b6cd7e8e27d4847f8a445))
* initial state machine architecture and examples ([58fd206](https://github.com/scasplte2/ottochain/commit/58fd20654e168b091772994ca6b88f9a2050375f))
* integrate JLVM gas metering into fiber orchestration ([5995913](https://github.com/scasplte2/ottochain/commit/5995913de2e691351bd706cac22c1ff460342f60))
* **ml0:** Add webhook notifications for snapshot consensus ([#11](https://github.com/scasplte2/ottochain/issues/11)) ([3d791a6](https://github.com/scasplte2/ottochain/commit/3d791a60c09eb28c74f758ebf4670a5c640c05f3))
* notify deploy repo on release ([#42](https://github.com/scasplte2/ottochain/issues/42)) ([55d4158](https://github.com/scasplte2/ottochain/commit/55d41588b748a346310bbbeebf872d2be393ba7b))
* track ephemeral fiber log entries in onchain state ([33aac15](https://github.com/scasplte2/ottochain/commit/33aac1549d1ed1b7d2720ec52217bb969b1ca4e3))
* **validation:** reject reserved JSON Logic operators as field names ([#14](https://github.com/scasplte2/ottochain/issues/14)) ([57f6392](https://github.com/scasplte2/ottochain/commit/57f6392d40c28a1ab79672a11ee534ebb5c3f8c4))


### Bug Fixes

* address issues in unit tests with new gas metering ([d890d65](https://github.com/scasplte2/ottochain/commit/d890d6585ede4f1a735a35f210f8c8cf507863ca))
* **ci:** remove secrets from if condition in docker.yml ([#48](https://github.com/scasplte2/ottochain/issues/48)) ([05f7650](https://github.com/scasplte2/ottochain/commit/05f7650d467b787e47c52af740c2e08f30ff742e))
* **docker:** remove .sbt and .cache copying between stages ([#49](https://github.com/scasplte2/ottochain/issues/49)) ([52cc58f](https://github.com/scasplte2/ottochain/commit/52cc58f820b8ba994e6f1802f3257704f078b2bb))
* **docker:** use scasplte2 org for container registry ([#51](https://github.com/scasplte2/ottochain/issues/51)) ([ff7aec5](https://github.com/scasplte2/ottochain/commit/ff7aec5d64ed0b7e9c676acd01a140c9d645935b))
* relax L1 sequence validation to &gt;= for batching support ([#45](https://github.com/scasplte2/ottochain/issues/45)) ([05b0ac8](https://github.com/scasplte2/ottochain/commit/05b0ac8b6d2a9ac741e26bcc527dbef06135a9e3))
* update e2e-test package-lock.json for SDK transitive deps ([#22](https://github.com/scasplte2/ottochain/issues/22)) ([dfb6cd1](https://github.com/scasplte2/ottochain/commit/dfb6cd1443a787ac5582f09d1a6cd73d68d89ec4))


### Code Refactoring

* cid -&gt; fiberId ([33aac15](https://github.com/scasplte2/ottochain/commit/33aac1549d1ed1b7d2720ec52217bb969b1ca4e3))
* cid to fiberId ([c4618fb](https://github.com/scasplte2/ottochain/commit/c4618fb559d7a3f69421d81a09b57c297396abc2))
* consolidate fiberT across engine sub-components ([a4259f9](https://github.com/scasplte2/ottochain/commit/a4259f9864ff22b05116a839725edaf900461886))
* **e2e:** run test flows in parallel instead of sequentially ([#4](https://github.com/scasplte2/ottochain/issues/4)) ([199d636](https://github.com/scasplte2/ottochain/commit/199d63685dcd2abb53411555d7ee2c2f61e42a21))
* Extract shared test utilities and migrate example tests ([#3](https://github.com/scasplte2/ottochain/issues/3)) ([a89de8b](https://github.com/scasplte2/ottochain/commit/a89de8bc40d5f95dc5891e022d787cf0089c3ee9))
* fiber engine to use monad transformers via StateT & ReaderT ([1ce3616](https://github.com/scasplte2/ottochain/commit/1ce3616a597967d0297747d5e221d791379acb9e))
* modify app config class names ([248cb6d](https://github.com/scasplte2/ottochain/commit/248cb6d6aad11527780ebc4258341faad606b902))
* modularize fiber engine and lifecycle functions ([fec8200](https://github.com/scasplte2/ottochain/commit/fec82007ade79e5fc2b694ac8a2fa90cb35d3535))
* remove event type to bare string ([99065f4](https://github.com/scasplte2/ottochain/commit/99065f434aa21caff2ba223e84fe9be322e1969e))
* rename ScriptOracle to Script ([#7](https://github.com/scasplte2/ottochain/issues/7)) ([b7e5af6](https://github.com/scasplte2/ottochain/commit/b7e5af682f24d7ee6b05caee7259e2eb759eb90d))
* reorganize e2e terminal app and fix broken examples ([eb2e8a8](https://github.com/scasplte2/ottochain/commit/eb2e8a8922ab1a97b805063d1f5e422ec0aa0435))
* separate fiber engine into sub-components ([ddf5cc2](https://github.com/scasplte2/ottochain/commit/ddf5cc245bc6787034f1af8b2e1a2c6c78b025f5))
* structured outputs to emitted events ([99065f4](https://github.com/scasplte2/ottochain/commit/99065f434aa21caff2ba223e84fe9be322e1969e))
* update event receipts for state machines ([4e85c34](https://github.com/scasplte2/ottochain/commit/4e85c34111de643d3162487186c7ab9cf574dba3))
* use lenses and sorted map ([33aac15](https://github.com/scasplte2/ottochain/commit/33aac1549d1ed1b7d2720ec52217bb969b1ca4e3))
* use UPPERCASE state names to match SDK protobuf enums ([#18](https://github.com/scasplte2/ottochain/issues/18)) ([326d192](https://github.com/scasplte2/ottochain/commit/326d192145c22a12e350d3b53885c261077bf887))


### Documentation

* add API reference for tessellation framework and OttoChain endpoints ([#19](https://github.com/scasplte2/ottochain/issues/19)) ([ee7680d](https://github.com/scasplte2/ottochain/commit/ee7680d3e98bd85316a1e7a98cfbd7db061e157b))
* add corporate governance domain (10 state machines) ([#30](https://github.com/scasplte2/ottochain/issues/30)) ([7d3f900](https://github.com/scasplte2/ottochain/commit/7d3f900631ba216e4b7fb0bddbd086a4eeae8c89))
* add governance domain state machine definitions ([#29](https://github.com/scasplte2/ottochain/issues/29)) ([05a569c](https://github.com/scasplte2/ottochain/commit/05a569cdd95028b928b2f24fb180810addd98cc0))
* add Trust Graph architecture and state machine definitions ([#21](https://github.com/scasplte2/ottochain/issues/21)) ([3dec460](https://github.com/scasplte2/ottochain/commit/3dec460b583fe84d3a0f88b4cfed72f6069ef50d))
* Agent Identity Protocol whitepaper draft ([#9](https://github.com/scasplte2/ottochain/issues/9)) ([98f5eed](https://github.com/scasplte2/ottochain/commit/98f5eed903e164cca5ce319e9d247e15cf3618e6))
* Comprehensive documentation overhaul ([#1](https://github.com/scasplte2/ottochain/issues/1)) ([3369e51](https://github.com/scasplte2/ottochain/commit/3369e511ccca5549fb0fd85f985a863108936f28))
* fix ML0 genesis flow and DL1 cluster join ([#15](https://github.com/scasplte2/ottochain/issues/15)) ([fc8c35f](https://github.com/scasplte2/ottochain/commit/fc8c35f17b29ebd7820e49d97cfe2c1d6939a881))
* update docs with MTL architecture ([812ffe9](https://github.com/scasplte2/ottochain/commit/812ffe9d28ab2bda511a0f9f1572a19e7d065aca))
