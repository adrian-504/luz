package app.iptvplayer.protocols.xtream

import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.ports.DiagnosticLocation
import app.iptvplayer.domain.ports.DiagnosticSeverity
import app.iptvplayer.domain.ports.DiagnosticSink
import app.iptvplayer.domain.ports.ImportDiagnostic

/** Stable diagnostic codes for Xtream import (docs/IPTV_PROTOCOLS.md §4.6). */
public object XtreamDiagnosticCodes {
    public const val FIELD_TYPE_MISMATCH: String = "XTREAM_FIELD_TYPE_MISMATCH"
    public const val MISSING_ID: String = "XTREAM_MISSING_ID"
    public const val NO_USABLE_NAME: String = "XTREAM_NO_USABLE_NAME"
    public const val UNKNOWN_CATEGORY: String = "XTREAM_UNKNOWN_CATEGORY"
    public const val NOT_AN_OBJECT: String = "XTREAM_ELEMENT_NOT_AN_OBJECT"
    public const val INVALID_ARTWORK_URL: String = "XTREAM_INVALID_ARTWORK_URL"
    public const val INVALID_BASE64: String = "XTREAM_INVALID_BASE64"
    public const val INVALID_PROGRAMME_TIME: String = "XTREAM_INVALID_PROGRAMME_TIME"
    public const val SERVER_HOST_MISMATCH: String = "XTREAM_SERVER_HOST_MISMATCH"
    public const val HTTPS_AVAILABLE: String = "XTREAM_HTTPS_AVAILABLE"
    public const val EXPIRY_IN_PAST: String = "XTREAM_EXPIRY_IN_PAST"
    public const val CONNECTION_LIMIT_REACHED: String = "XTREAM_CONNECTION_LIMIT_REACHED"
    public const val CAPABILITY_PROBE_FAILED: String = "XTREAM_CAPABILITY_PROBE_FAILED"
    public const val DUPLICATE_ID: String = "XTREAM_DUPLICATE_ID"
}

/** Reports diagnostics with a fixed unit; messages are static text and never contain response values. */
internal class XtreamReporter(private val unit: ImportUnit?, private val sink: DiagnosticSink) {
    var index: Long? = null

    fun info(code: String, message: String) = report(DiagnosticSeverity.INFO, code, message)

    fun warning(code: String, message: String) = report(DiagnosticSeverity.WARNING, code, message)

    /** Field-level coercion failure: the field name is recorded, the value never is. */
    fun mismatch(field: String) = info(XtreamDiagnosticCodes.FIELD_TYPE_MISMATCH, "Field '$field' has an unexpected type or value")

    private fun report(severity: DiagnosticSeverity, code: String, message: String) {
        val location = index?.let { DiagnosticLocation(path = "[$it]") }
        sink.report(ImportDiagnostic(unit, severity, code, location, message, sampleRedacted = null))
    }
}
