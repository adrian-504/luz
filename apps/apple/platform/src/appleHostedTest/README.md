# Tests that need a host app

The Keychain exists only for real apps: a plain simulator test binary gets `errSecNotAvailable` (-25291). The tests
here run in the iPhone app's test target (Phase 10 step 5), not in `iosSimulatorArm64Test`.

- `KeychainSecretStoreTest.kt.pending` — the Keychain store's behaviour (whole login stored and read back, replaced,
  references kept apart, deleted). **NOT YET VERIFIED** until the app-hosted test target runs it.
