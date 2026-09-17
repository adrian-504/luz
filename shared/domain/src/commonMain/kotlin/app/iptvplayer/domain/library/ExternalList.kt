package app.iptvplayer.domain.library

import app.iptvplayer.domain.model.ContentType

/**
 * A public list of films or shows read from outside the provider (ADR-0038), matched against the library locally. Where
 * each is read from is the protocol layer's business; screens only name the list.
 */
public enum class ExternalList(public val type: ContentType) {
    TRENDING_MOVIES(ContentType.MOVIE),
    TRENDING_SERIES(ContentType.SERIES),
    POPULAR_MOVIES(ContentType.MOVIE),
    POPULAR_SERIES(ContentType.SERIES),
    TOP_MOVIES(ContentType.MOVIE),
    TOP_SERIES(ContentType.SERIES),
}
