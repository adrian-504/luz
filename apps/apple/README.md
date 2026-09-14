# Apple family

Planned layout (created in Phase 10):

```
apps/apple/
  IPTVPlayer.xcodeproj        one project, tvOS and iOS app targets
  tvOS/                       tvOS-specific SwiftUI (focus engine, guide, player overlay)  — Phase 11
  iOS/                        iOS-specific SwiftUI (touch)                                 — Phase 12
  Packages/ApplePlatform/     Swift package: AVPlayer PlaybackController, KeychainSecretStore,
                              URLSession HttpTransport, signposts, shared-core bridge
```

The shared core arrives as `IPTVCore.xcframework` built by Gradle from `shared:apple-export` (ADR-0011).
Stack and rules: [ARCHITECTURE.md §12](../../docs/ARCHITECTURE.md#12-apple-implementation-architecture-phase-10).

Toolchain status (Phase 0): only Xcode Command Line Tools installed — no Xcode, no iOS/tvOS SDKs or simulators.
