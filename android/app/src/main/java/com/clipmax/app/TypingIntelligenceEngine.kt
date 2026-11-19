package com.clipmax.app

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Local-only offline word autocomplete, contextual bigram prediction, and high-confidence
 * spelling correction engine. Zero network access; deterministic and JVM unit-testable.
 */
internal class TypingIntelligenceEngine(wordsInFrequencyOrder: List<String>) {

    data class WordAnalysis(
        val originalWord: String,
        val isKnownWord: Boolean,
        val autoCorrection: String?,
        val suggestions: List<String>
    )

    companion object {
        private val BUILTIN_CORE_WORDS = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not",
            "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from",
            "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would",
            "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which",
            "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know",
            "take", "people", "into", "year", "your", "good", "some", "could", "them", "see",
            "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
            "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "is", "are", "was", "were", "been", "being", "am", "has", "had", "did", "does",
            "hello", "thanks", "thank", "please", "sorry", "yes", "no", "okay", "today",
            "tomorrow", "yesterday", "morning", "evening", "meeting", "message", "received",
            "receive", "separate", "definitely", "friend", "believe", "achieve", "calendar",
            "address", "because", "government", "environment", "immediately", "independent",
            "necessary", "noticeable", "writing", "coming", "really", "truly", "weird",
            "knowledge", "misspell", "surprise", "successful", "temperature", "different",
            "important", "schedule", "example", "project", "keyboard", "clipboard", "settings",
            "don't", "can't", "won't", "didn't", "isn't", "aren't", "i'm", "you're", "we're",
            "they're", "it's", "that's", "i've", "i'll", "i'd"
        )

        /**
         * Curated high-confidence common misspellings where the source token is never a standard English word.
         */
        private val HIGH_CONFIDENCE_TYPOS = mapOf(
            "teh" to "the",
            "hte" to "the",
            "taht" to "that",
            "thsi" to "this",
            "tihs" to "this",
            "adn" to "and",
            "nad" to "and",
            "waht" to "what",
            "whta" to "what",
            "wich" to "which",
            "whcih" to "which",
            "thier" to "their",
            "tehir" to "their",
            "yuo" to "you",
            "yuor" to "your",
            "youre" to "you're",
            "recieved" to "received",
            "recieve" to "receive",
            "recieving" to "receiving",
            "mesage" to "message",
            "mesages" to "messages",
            "messsage" to "message",
            "seperate" to "separate",
            "seperated" to "separated",
            "seperately" to "separately",
            "definately" to "definitely",
            "definatly" to "definitely",
            "definitly" to "definitely",
            "occured" to "occurred",
            "occurence" to "occurrence",
            "occassion" to "occasion",
            "untill" to "until",
            "wierd" to "weird",
            "acheive" to "achieve",
            "acheived" to "achieved",
            "adress" to "address",
            "adres" to "address",
            "becuase" to "because",
            "beacuse" to "because",
            "becasue" to "because",
            "beleive" to "believe",
            "beleived" to "believed",
            "calender" to "calendar",
            "comming" to "coming",
            "completly" to "completely",
            "enviroment" to "environment",
            "existance" to "existence",
            "foward" to "forward",
            "freind" to "friend",
            "freinds" to "friends",
            "goverment" to "government",
            "grammer" to "grammar",
            "happend" to "happened",
            "immediatly" to "immediately",
            "independant" to "independent",
            "knowlege" to "knowledge",
            "libary" to "library",
            "maintainance" to "maintenance",
            "mispell" to "misspell",
            "neccessary" to "necessary",
            "necesary" to "necessary",
            "noticable" to "noticeable",
            "persistant" to "persistent",
            "posession" to "possession",
            "prefered" to "preferred",
            "priviledge" to "privilege",
            "publically" to "publicly",
            "realy" to "really",
            "recomend" to "recommend",
            "recomended" to "recommended",
            "refered" to "referred",
            "relevent" to "relevant",
            "rememeber" to "remember",
            "remeber" to "remember",
            "succesful" to "successful",
            "sucessful" to "successful",
            "suprise" to "surprise",
            "suprised" to "surprised",
            "temperture" to "temperature",
            "tommorrow" to "tomorrow",
            "tomorow" to "tomorrow",
            "truely" to "truly",
            "vaccum" to "vacuum",
            "writting" to "writing",
            "writen" to "written",
            "arguement" to "argument",
            "embarass" to "embarrass",
            "foriegn" to "foreign",
            "schedual" to "schedule",
            "shedule" to "schedule",
            "helo" to "hello",
            "helllo" to "hello",
            "thansk" to "thanks",
            "tahnks" to "thanks",
            "thnaks" to "thanks",
            "plase" to "please",
            "pleas" to "please",
            "plesae" to "please",
            "meetng" to "meeting",
            "meating" to "meeting",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "havent" to "haven't",
            "hasnt" to "hasn't",
            "couldnt" to "couldn't",
            "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't",
            "theyre" to "they're",
            "theres" to "there's",
            "whats" to "what's",
            "thats" to "that's",
            "jsut" to "just",
            "ahve" to "have",
            "hvae" to "have",
            "todya" to "today",
            "tdoay" to "today",
            "mornign" to "morning",
            "evenign" to "evening",
            "keybaord" to "keyboard",
            "clipbaord" to "clipboard"
        )

        private val NEXT_WORD_BIGRAMS = mapOf(
            "i" to listOf("am", "have", "will", "think"),
            "i'm" to listOf("going", "sure", "here", "sorry"),
            "you" to listOf("are", "can", "have", "want"),
            "we" to listOf("are", "can", "will", "have"),
            "they" to listOf("are", "were", "will", "have"),
            "it" to listOf("is", "was", "will", "works"),
            "it's" to listOf("a", "not", "ready", "great"),
            "this" to listOf("is", "will", "works", "morning"),
            "that" to listOf("is", "was", "would", "sounds"),
            "thank" to listOf("you", "goodness"),
            "thanks" to listOf("for", "again", "very"),
            "please" to listOf("let", "check", "send", "find"),
            "how" to listOf("are", "to", "much", "about"),
            "what" to listOf("is", "are", "time", "about"),
            "when" to listOf("is", "are", "will", "you"),
            "where" to listOf("is", "are", "can", "did"),
            "see" to listOf("you", "the", "if"),
            "good" to listOf("morning", "evening", "luck", "idea"),
            "have" to listOf("a", "to", "you", "been"),
            "let" to listOf("me", "us", "you"),
            "let's" to listOf("meet", "go", "check", "talk"),
            "meeting" to listOf("tomorrow", "today", "at", "with"),
            "received" to listOf("your", "the", "this", "a"),
            "in" to listOf("the", "a", "this", "touch"),
            "on" to listOf("the", "my", "time", "monday"),
            "at" to listOf("the", "home", "work", "all"),
            "for" to listOf("the", "your", "a", "this"),
            "with" to listOf("the", "you", "a", "my"),
            "to" to listOf("the", "be", "see", "get"),
            "looking" to listOf("forward", "for", "at"),
            "forward" to listOf("to"),
            "sounds" to listOf("good", "great"),
            "no" to listOf("problem", "worries", "thanks"),
            "very" to listOf("much", "good", "well")
        )

        fun createFallback(): TypingIntelligenceEngine = TypingIntelligenceEngine(BUILTIN_CORE_WORDS)

        fun fromStream(input: InputStream?): TypingIntelligenceEngine {
            if (input == null) return createFallback()
            val loaded = ArrayList<String>(5200)
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val clean = line.trim().lowercase(Locale.ROOT)
                    if (clean.isNotEmpty()) {
                        loaded.add(clean)
                    }
                }
            }
            return if (loaded.isNotEmpty()) TypingIntelligenceEngine(loaded) else createFallback()
        }

        /**
         * Optimal String Alignment (restricted Damerau-Levenshtein) distance with early threshold pruning.
         */
        fun osaDistance(a: String, b: String, maxDist: Int = 2): Int {
            val lenA = a.length
            val lenB = b.length
            if (kotlin.math.abs(lenA - lenB) > maxDist) return maxDist + 1
            if (a == b) return 0
            if (lenA == 0) return lenB
            if (lenB == 0) return lenA

            var prevPrev = IntArray(lenB + 1)
            var prev = IntArray(lenB + 1) { it }
            var curr = IntArray(lenB + 1)

            for (i in 1..lenA) {
                curr[0] = i
                var rowMin = i
                val ca = a[i - 1]
                for (j in 1..lenB) {
                    val cb = b[j - 1]
                    val cost = if (ca == cb) 0 else 1
                    var best = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                    if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                        best = minOf(best, prevPrev[j - 2] + 1)
                    }
                    curr[j] = best
                    if (best < rowMin) rowMin = best
                }
                if (rowMin > maxDist) return maxDist + 1
                val tmp = prevPrev
                prevPrev = prev
                prev = curr
                curr = tmp
            }
            return prev[lenB]
        }

        fun applyWordCasing(original: String, targetLower: String): String {
            if (original.isEmpty() || targetLower.isEmpty()) return targetLower
            if (targetLower == "i") return "I"
            if (targetLower.startsWith("i'")) {
                return "I" + targetLower.substring(1)
            }
            if (original.length >= 2 && original.all { !it.isLetter() || it.isUpperCase() }) {
                return targetLower.uppercase(Locale.ROOT)
            }
            if (original[0].isUpperCase()) {
                return targetLower.replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
            return targetLower
        }

        fun containsArabicScript(text: CharSequence?): Boolean {
            if (text.isNullOrEmpty()) return false
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                if (cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF) {
                    return true
                }
                i += Character.charCount(cp)
            }
            return false
        }

        private fun isLatinAsciiWord(text: CharSequence?): Boolean {
            if (text.isNullOrEmpty()) return false
            for (ch in text) {
                if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch != '\'') return false
            }
            return true
        }
    }

    private val orderedWords: List<String>
    private val wordRank = HashMap<String, Int>(wordsInFrequencyOrder.size * 2)
    private val wordsByFirstChar = HashMap<Char, MutableList<String>>(32)
    private val wordsByLength = HashMap<Int, MutableList<String>>(24)

    init {
        val unique = ArrayList<String>(wordsInFrequencyOrder.size + 32)
        fun addWord(raw: String) {
            val w = raw.trim().lowercase(Locale.ROOT)
            if (w.isEmpty() || wordRank.containsKey(w)) return
            val rank = unique.size
            unique.add(w)
            wordRank[w] = rank
            wordsByFirstChar.getOrPut(w[0]) { ArrayList() }.add(w)
            wordsByLength.getOrPut(w.length) { ArrayList() }.add(w)
        }
        for (raw in wordsInFrequencyOrder) {
            addWord(raw)
        }
        for (target in HIGH_CONFIDENCE_TYPOS.values) {
            addWord(target)
        }
        orderedWords = unique
    }

    fun dictionarySize(): Int = orderedWords.size

    fun isKnownWord(token: String): Boolean {
        if (token.isEmpty()) return false
        val lower = token.lowercase(Locale.ROOT)
        if (wordRank.containsKey(lower)) return true
        // Recognize regular possessive "'s" if base is known
        if (lower.endsWith("'s") && lower.length > 2) {
            return wordRank.containsKey(lower.dropLast(2))
        }
        return false
    }

    /**
     * Determines if the token should never be auto-corrected (URLs, emails, paths, code,
     * numbers, acronyms, camelCase, or mid-sentence capitalized proper names).
     */
    fun shouldProtectFromAutocorrect(
        composingWord: String,
        fullTokenBeforeCursor: String,
        isSentenceStart: Boolean
    ): Boolean {
        if (composingWord.length < 2) return true
        for (ch in fullTokenBeforeCursor) {
            if (ch.isDigit() || ch == '@' || ch == '/' || ch == ':' || ch == '.' ||
                ch == '_' || ch == '+' || ch == '#' || ch == '-' || ch == '='
            ) {
                return true
            }
        }
        for (ch in composingWord) {
            if (!ch.isLetter() && ch != '\'') return true
        }
        // ALL CAPS acronym e.g. API, SQL, APK
        val lettersOnly = composingWord.filter { it.isLetter() }
        if (lettersOnly.length >= 2 && lettersOnly.all { it.isUpperCase() }) {
            return true
        }
        // Mixed camelCase / PascalCase e.g. ClipMax, iOS, myVar
        if (lettersOnly.length >= 2 && lettersOnly.drop(1).any { it.isUpperCase() }) {
            return true
        }
        // Capitalized proper name mid-sentence (e.g., "Ahmad", "London")
        if (!isSentenceStart && composingWord[0].isUpperCase()) {
            return true
        }
        return false
    }

    fun analyzeWord(
        composingWord: String,
        fullTokenBeforeCursor: String,
        isSentenceStart: Boolean,
        autocompleteEnabled: Boolean,
        autoCorrectEnabled: Boolean,
        suppressedWordsLower: Set<String> = emptySet(),
        maxSuggestions: Int = 4
    ): WordAnalysis {
        if (composingWord.isEmpty() || containsArabicScript(composingWord) || containsArabicScript(fullTokenBeforeCursor) || !isLatinAsciiWord(composingWord)) {
            return WordAnalysis(composingWord, isKnownWord = false, autoCorrection = null, suggestions = emptyList())
        }
        val lower = composingWord.lowercase(Locale.ROOT)
        val known = isKnownWord(composingWord)
        val protectedToken = shouldProtectFromAutocorrect(composingWord, fullTokenBeforeCursor, isSentenceStart)

        // 1. Determine high-confidence auto-correction if eligible
        var autoCorrection: String? = null
        val correctionCandidates = if (!known && !protectedToken) {
            findCorrectionCandidates(lower, limit = 3)
        } else {
            emptyList()
        }

        if (autoCorrectEnabled &&
            !known &&
            !protectedToken &&
            lower !in suppressedWordsLower &&
            correctionCandidates.isNotEmpty()
        ) {
            val best = correctionCandidates.first()
            if (best.highConfidence) {
                autoCorrection = applyWordCasing(composingWord, best.word)
            }
        }

        // 2. Build suggestions list cleanly according to independent toggles
        val suggestions = ArrayList<String>(maxSuggestions)
        val seenLower = HashSet<String>()

        fun addSuggestion(rawLower: String) {
            if (suggestions.size >= maxSuggestions) return
            val cased = applyWordCasing(composingWord, rawLower)
            val key = cased.lowercase(Locale.ROOT)
            if (seenLower.add(key)) {
                suggestions.add(cased)
            }
        }

        // Include correction candidates if either autocomplete or autocorrect is enabled
        if (!known && (autoCorrectEnabled || autocompleteEnabled)) {
            for (cand in correctionCandidates) {
                addSuggestion(cand.word)
            }
        }

        // Include exact valid word + prefix completions when autocomplete is enabled
        if (autocompleteEnabled && !hasNonWordSymbols(fullTokenBeforeCursor)) {
            val firstChar = lower[0]
            val bucket = wordsByFirstChar[firstChar].orEmpty()
            if (known && suggestions.size < maxSuggestions) {
                addSuggestion(lower)
            }
            for (dictWord in bucket) {
                if (suggestions.size >= maxSuggestions) break
                if (dictWord.length > lower.length && dictWord.startsWith(lower)) {
                    addSuggestion(dictWord)
                }
            }
        }

        return WordAnalysis(
            originalWord = composingWord,
            isKnownWord = known,
            autoCorrection = autoCorrection,
            suggestions = suggestions
        )
    }

    fun nextWordSuggestions(
        previousWord: String?,
        autocompleteEnabled: Boolean,
        maxSuggestions: Int = 4
    ): List<String> {
        if (!autocompleteEnabled || previousWord.isNullOrBlank() || containsArabicScript(previousWord) || !isLatinAsciiWord(previousWord.trim())) return emptyList()
        val key = previousWord.trim().lowercase(Locale.ROOT)
        return NEXT_WORD_BIGRAMS[key]?.take(maxSuggestions).orEmpty()
    }

    private fun hasNonWordSymbols(token: String): Boolean {
        for (ch in token) {
            if (!ch.isLetter() && ch != '\'') return true
        }
        return false
    }

    private data class CandidateMatch(
        val word: String,
        val distance: Int,
        val rank: Int,
        val highConfidence: Boolean
    )

    private fun findCorrectionCandidates(lower: String, limit: Int): List<CandidateMatch> {
        val exactTypo = HIGH_CONFIDENCE_TYPOS[lower]
        if (exactTypo != null) {
            val rank = wordRank[exactTypo] ?: 0
            return listOf(CandidateMatch(exactTypo, distance = 1, rank = rank, highConfidence = true))
        }
        if (lower.length < 3) return emptyList()

        val maxDist = if (lower.length >= 6) 2 else 1
        val matches = ArrayList<CandidateMatch>()
        val targetLen = lower.length

        for (len in (targetLen - maxDist).coerceAtLeast(2)..(targetLen + maxDist)) {
            val candidates = wordsByLength[len] ?: continue
            for (cand in candidates) {
                // Quick positional similarity prune for distance 2
                if (maxDist == 2 && cand[0] != lower[0] && cand[0] != lower.getOrNull(1)) continue
                val dist = osaDistance(lower, cand, maxDist)
                if (dist <= maxDist) {
                    val rank = wordRank[cand] ?: Int.MAX_VALUE
                    matches.add(CandidateMatch(cand, dist, rank, highConfidence = false))
                }
            }
        }

        if (matches.isEmpty()) return emptyList()
        matches.sortWith(compareBy<CandidateMatch> { it.distance }.thenBy { it.rank })

        val best = matches[0]
        val second = matches.getOrNull(1)
        val sameFirst = best.word[0] == lower[0]
        val veryCommon = best.rank < 1200
        val reasonablyCommon = best.rank < 2500
        val isActivePrefixOfCommonWord = hasHigherRankedPrefix(lower, best.rank)

        // Conservative high-confidence auto-correction criteria:
        // Never overwrite an active prefix of a more common valid word (e.g. "tomor" -> "tomorrow").
        val isHighConfidence = when {
            isActivePrefixOfCommonWord -> false
            best.distance == 1 && lower.length >= 5 && ((sameFirst && reasonablyCommon) || veryCommon) &&
                (second == null || second.distance > 1 || second.rank > best.rank * 3) -> true
            best.distance == 1 && lower.length == 4 && sameFirst && best.rank < 600 &&
                (second == null || second.distance > 1 || second.rank > best.rank * 5) -> true
            best.distance == 2 && lower.length >= 6 && sameFirst && best.rank < 800 &&
                (second == null || (second.distance >= 2 && second.rank > best.rank * 6)) -> true
            else -> false
        }

        return matches.take(limit).mapIndexed { index, item ->
            if (index == 0) item.copy(highConfidence = isHighConfidence) else item
        }
    }

    private fun hasHigherRankedPrefix(prefix: String, candidateRank: Int): Boolean {
        val bucket = wordsByFirstChar[prefix[0]] ?: return false
        for (w in bucket) {
            if (w.length > prefix.length && w.startsWith(prefix)) {
                val r = wordRank[w] ?: Int.MAX_VALUE
                if (r < candidateRank || r < 2000) return true
            }
        }
        return false
    }
}
