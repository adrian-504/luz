package app.iptvplayer.domain.live

/**
 * One channel from the copies a provider sends of it (ADR-0052): "beIN Sports 1 FHD", "HD" and "SD" are one family, best
 * copy first. The language prefix stays part of the name, so "AR| …" and "FR| …" stay two; two copies that each carry
 * their own, different guide id stay two as well.
 */
public object ChannelFamilies {
    /** One copy as the provider sent it: its family key ([ChannelNames.familyKey]), guide id, quality and place. */
    public data class Copy(
        public val id: String,
        public val key: String,
        public val tvgId: String?,
        public val quality: String?,
        public val order: Int,
    )

    /** What this TV learned about a copy: chosen by the viewer, and when it last started or failed (epoch ms). */
    public data class Memory(public val chosen: Boolean = false, public val lastOkAt: Long? = null, public val lastFailAt: Long? = null)

    /** Families by id, each with its copies best first, and which family each copy belongs to. */
    public class Families(public val members: Map<String, List<String>>, public val familyOf: Map<String, String>) {
        /**
         * One row per family for a list of copy ids, at the place of its first copy in the list, using the best copy the
         * list holds. Copies not in any family pass through unchanged.
         */
        public fun collapse(ids: List<String>): List<String> {
            val present = ids.toHashSet()
            val seen = HashSet<String>()
            val out = ArrayList<String>(ids.size)
            for (id in ids) {
                val family = familyOf[id]
                if (family == null) {
                    out += id
                } else if (seen.add(family)) {
                    out += members.getValue(family).first { it in present }
                }
            }
            return out
        }
    }

    public fun build(copies: List<Copy>, memory: Map<String, Memory>, now: Long): Families {
        val members = LinkedHashMap<String, List<String>>()
        val familyOf = HashMap<String, String>(copies.size * 2)
        for ((key, sameName) in copies.groupBy { it.key }) {
            // A copy with its own guide id unlike another's is another channel; copies without one join the first.
            val byGuide = LinkedHashMap<String?, MutableList<Copy>>()
            for (copy in sameName) {
                val guide = copy.tvgId?.takeIf { it.isNotBlank() }
                byGuide.getOrPut(guide) { mutableListOf() } += copy
            }
            val untagged = byGuide.remove(null).orEmpty()
            val groups = if (byGuide.isEmpty()) {
                listOf(untagged)
            } else {
                byGuide.values.mapIndexed { index, list -> if (index == 0) list + untagged else list }
            }
            groups.forEachIndexed { index, group ->
                val id = if (index == 0) key else "$key#$index"
                val ordered = group.sortedWith(order(memory, now)).map { it.id }
                members[id] = ordered
                ordered.forEach { familyOf[it] = id }
            }
        }
        return Families(members, familyOf)
    }

    /** Chosen by hand, then played well, then quality (4K, FHD, HD, none, SD), then the provider's order; failing last. */
    private fun order(memory: Map<String, Memory>, now: Long): Comparator<Copy> = compareBy<Copy>(
        { if (memory[it.id]?.chosen == true) 0 else 1 },
        { if (failedLately(memory[it.id], now)) 1 else 0 },
        { if (memory[it.id]?.lastOkAt != null) 0 else 1 },
        { qualityRank(it.quality) },
        { it.order },
    )

    /** A failure in the last week, after its last good start; older failures are forgotten (3.12). */
    private fun failedLately(memory: Memory?, now: Long): Boolean {
        val failed = memory?.lastFailAt ?: return false
        if (now - failed > FORGET_FAILURE_MS) return false
        return failed > (memory.lastOkAt ?: Long.MIN_VALUE)
    }

    private fun qualityRank(quality: String?): Int = when (quality) {
        "8K", "4K" -> 0
        "FHD" -> 1
        "HD" -> 2
        null -> 3
        "SD" -> 4
        else -> 3
    }

    private const val FORGET_FAILURE_MS = 7L * 24 * 60 * 60 * 1000
}
