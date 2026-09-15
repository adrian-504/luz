package app.iptvplayer.domain.text

import java.text.Normalizer

internal actual fun nfkc(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)
