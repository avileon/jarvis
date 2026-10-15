package il.co.vibit.jarvis.core.contacts

import il.co.vibit.jarvis.core.nlu.PhoneType
import il.co.vibit.jarvis.core.text.HebrewText

data class PhoneNumber(
    val number: String,
    val type: PhoneType,
    val label: String? = null,
    val isPrimary: Boolean = false,
) {
    /** Digits only, Israeli +972 prefix folded to 0, so duplicates across accounts collapse. */
    val key: String get() {
        val d = number.filter { it.isDigit() }
        return if (d.startsWith("972") && d.length >= 11) "0" + d.substring(3) else d
    }
}

data class Contact(
    val id: Long,
    val displayName: String,
    val phones: List<PhoneNumber>,
    /** Nicknames and other names saved on the contact ("אמא", "Shelly"). */
    val aliases: List<String> = emptyList(),
) {
    /** Phones with duplicates (same number saved twice / in several accounts) removed. */
    val distinctPhones: List<PhoneNumber> get() =
        phones.groupBy { it.key }.values.map { same -> same.firstOrNull { it.isPrimary } ?: same.first() }
}

enum class MatchKind { EXACT_FULL, EXACT_TOKENS, EXACT_TOKEN, FUZZY, PHONETIC }

data class Candidate(val contact: Contact, val score: Double, val kind: MatchKind)

sealed interface Resolution {
    /** One contact clearly matches — safe to proceed. */
    data class Unique(val candidate: Candidate) : Resolution
    /** Several contacts match equally well ("גל" → גל לוי, גל כהן) — must ask which. */
    data class Ambiguous(val candidates: List<Candidate>) : Resolution
    /** Only approximate matches (likely a transcription error) — must ask for confirmation before dialling. */
    data class Uncertain(val candidates: List<Candidate>) : Resolution
    data object NotFound : Resolution
}

/**
 * Matches a spoken name against the address book.
 *
 * Safety rule: only exact (normalised) name matches can lead to dialling without a question.
 * Anything approximate becomes [Resolution.Uncertain] and the dialog layer asks "התכוונת ל…?".
 */
class ContactMatcher(
    private val strongThreshold: Double = 0.9,
    private val weakThreshold: Double = 0.62,
) {
    private fun norm(s: String) = HebrewText.foldFinals(HebrewText.normalize(s))

    fun score(query: String, contact: Contact): Candidate? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val qTokens = q.split(' ')
        var best: Candidate? = null
        fun offer(score: Double, kind: MatchKind) {
            if (best == null || score > best!!.score) best = Candidate(contact, score, kind)
        }
        for (name in listOf(contact.displayName) + contact.aliases) {
            val n = norm(name)
            if (n.isEmpty()) continue
            val nTokens = n.split(' ')
            when {
                n == q -> offer(1.0, MatchKind.EXACT_FULL)
                qTokens.size > 1 && nTokens.containsAll(qTokens) -> offer(0.97, MatchKind.EXACT_TOKENS)
                qTokens.size == 1 && q in nTokens -> offer(if (nTokens.first() == q) 0.95 else 0.92, MatchKind.EXACT_TOKEN)
            }
            // Approximate: whole name and each token / adjacent token pair.
            val targets = buildList {
                add(n)
                addAll(nTokens)
                nTokens.windowed(2).forEach { add(it.joinToString(" ")) }
            }
            for (target in targets) {
                val sim = HebrewText.similarity(q, target)
                // Short names need to be closer: one wrong letter in "גל" is a different name.
                val minLen = minOf(q.length, target.length)
                val needed = if (minLen <= 3) 0.99 else if (minLen <= 4) 0.74 else weakThreshold
                if (sim >= needed) offer(0.85 * sim, MatchKind.FUZZY)
                val qk = HebrewText.phoneticKey(q)
                val tk = HebrewText.phoneticKey(target)
                if (qk.length >= 2 && qk == tk) offer(0.8, MatchKind.PHONETIC)
            }
        }
        return best?.takeIf { it.score >= weakThreshold * 0.85 }
    }

    fun resolve(nameVariants: List<String>, contacts: List<Contact>): Resolution {
        // Best score per contact across all variants; later variants are slightly penalised.
        val byContact = HashMap<Long, Candidate>()
        nameVariants.forEachIndexed { i, variant ->
            val penalty = 1.0 - 0.02 * i
            for (c in contacts) {
                if (c.phones.isEmpty()) continue
                val cand = score(variant, c) ?: continue
                val adj = cand.copy(score = cand.score * penalty)
                val prev = byContact[c.id]
                if (prev == null || adj.score > prev.score) byContact[c.id] = adj
            }
        }
        val all = byContact.values.sortedByDescending { it.score }
        if (all.isEmpty()) return Resolution.NotFound

        val strong = all.filter { it.score >= strongThreshold * 0.97 && it.kind != MatchKind.FUZZY && it.kind != MatchKind.PHONETIC }
        if (strong.size == 1) return Resolution.Unique(strong.first())
        if (strong.size > 1) {
            // A full-name exact hit beats partial hits only if it is the single full-name hit AND the query had
            // several words ("שלי כהן"). For one-word queries ("גל") every strong hit is a real alternative.
            val full = strong.filter { it.kind == MatchKind.EXACT_FULL || it.kind == MatchKind.EXACT_TOKENS }
            if (full.size == 1 && nameVariants.first().trim().contains(' ')) return Resolution.Unique(full.first())
            return Resolution.Ambiguous(strong.take(4))
        }
        val weak = all.filter { it.score >= weakThreshold * 0.85 }.take(3)
        return if (weak.isEmpty()) Resolution.NotFound else Resolution.Uncertain(weak)
    }
}
