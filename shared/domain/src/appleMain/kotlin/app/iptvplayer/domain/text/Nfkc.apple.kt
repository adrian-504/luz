package app.iptvplayer.domain.text

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

// NOT YET VERIFIED: compiled only when Xcode is installed (iptv.appleTargets). Vectors in IdVectorsTest cover it.
@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkc(input: String): String = (input as NSString).precomposedStringWithCompatibilityMapping
