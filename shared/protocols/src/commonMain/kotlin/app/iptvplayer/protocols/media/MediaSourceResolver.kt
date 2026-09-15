package app.iptvplayer.protocols.media

import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.PreferredStreamFormat
import app.iptvplayer.domain.model.Provider
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.security.Redactor
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.protocols.xtream.XtreamCredentials
import app.iptvplayer.protocols.xtream.XtreamEndpoint

/** A concrete, playable source. Lives only in memory for the playback session (docs/PLAYBACK.md §2). */
public class ResolvedMediaSource(
    public val url: SensitiveUrl,
    public val protocol: StreamProtocol,
    public val headers: Map<String, String>,
    public val sensitiveHeaders: Map<String, Secret<String>>,
) {
    override fun toString(): String =
        "ResolvedMediaSource(url=$url, protocol=$protocol, headers=${headers.keys}, sensitive=${Redactor.MARK})"
}

public enum class ResolveFailure { MISSING_CREDENTIALS, MISSING_ENDPOINT, MISSING_HEADER_SECRET, UNSUPPORTED_LOCATOR }

public sealed interface ResolveResult {
    public class Resolved(public val source: ResolvedMediaSource) : ResolveResult

    public data class Failed(public val reason: ResolveFailure) : ResolveResult
}

/**
 * Turns a persisted [MediaSource] into a [ResolvedMediaSource] immediately before `prepare` (ADR-0015): expands credential
 * placeholders, builds Xtream stream URLs and chooses the live output format for this platform.
 */
public object MediaSourceResolver {
    public fun resolve(
        source: MediaSource,
        provider: Provider,
        secrets: SecretBundle?,
        headerSecrets: Map<CredentialRef, Secret<String>>,
        platform: PlatformCapabilities,
        preferred: PreferredStreamFormat,
    ): ResolveResult {
        val sensitive = LinkedHashMap<String, Secret<String>>()
        for ((name, ref) in source.headers.sensitive) {
            sensitive[name] = headerSecrets[ref] ?: return ResolveResult.Failed(ResolveFailure.MISSING_HEADER_SECRET)
        }
        val headers = LinkedHashMap<String, String>()
        source.headers.userAgent?.let { headers["User-Agent"] = it }
        source.headers.referrer?.let { headers["Referer"] = it }
        headers.putAll(source.headers.custom)

        val (url, protocol) = when (val locator = source.locator) {
            is MediaLocator.DirectUrl -> {
                val url = locator.template.expand(secrets?.username, secrets?.password)
                    ?: return ResolveResult.Failed(ResolveFailure.MISSING_CREDENTIALS)
                url to source.protocolHint
            }
            is MediaLocator.XtreamStream -> {
                val endpoint = provider.endpoint?.let { XtreamEndpoint.parse(it.template) }
                    ?: return ResolveResult.Failed(ResolveFailure.MISSING_ENDPOINT)
                val username = secrets?.username ?: return ResolveResult.Failed(ResolveFailure.MISSING_CREDENTIALS)
                val password = secrets.password ?: return ResolveResult.Failed(ResolveFailure.MISSING_CREDENTIALS)
                val (extension, protocol) = when (locator.kind) {
                    XtreamStreamKind.LIVE -> liveOutput(provider.account?.allowedOutputFormats.orEmpty(), platform, preferred)
                    else -> {
                        val ext = locator.extension ?: DEFAULT_VOD_EXTENSION
                        ext to source.protocolHint
                    }
                }
                endpoint.streamUrl(XtreamCredentials(username, password), locator.kind, locator.streamId, extension) to protocol
            }
            is MediaLocator.XtreamTimeshift -> return ResolveResult.Failed(ResolveFailure.UNSUPPORTED_LOCATOR)
        }
        return ResolveResult.Resolved(ResolvedMediaSource(url, protocol, headers, sensitive))
    }

    /**
     * Live output choice (docs/SPEC_REVIEW.md §1.1 mitigation): MPEG-TS when the platform plays it and the account allows
     * it (lower zapping overhead), otherwise HLS; explicit user preference wins when the account allows it.
     * An empty allowed list means the panel did not say, so both are attempted in preference order.
     */
    public fun liveOutput(
        allowed: Set<StreamFormat>,
        platform: PlatformCapabilities,
        preferred: PreferredStreamFormat,
    ): Pair<String, StreamProtocol> {
        val tsAllowed = allowed.isEmpty() || StreamFormat.MPEG_TS in allowed
        val hlsAllowed = allowed.isEmpty() || StreamFormat.HLS in allowed
        val ts = "ts" to StreamProtocol.PROGRESSIVE_TS
        val hls = "m3u8" to StreamProtocol.HLS
        return when (preferred) {
            PreferredStreamFormat.HLS -> if (hlsAllowed) hls else ts
            PreferredStreamFormat.MPEG_TS -> if (tsAllowed) ts else hls
            PreferredStreamFormat.AUTO -> when {
                tsAllowed && platform.protocols[StreamProtocol.PROGRESSIVE_TS] == Support.SUPPORTED -> ts
                hlsAllowed -> hls
                else -> ts
            }
        }
    }

    private const val DEFAULT_VOD_EXTENSION = "mp4"
}
