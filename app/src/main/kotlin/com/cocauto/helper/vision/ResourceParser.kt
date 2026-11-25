package com.cocauto.helper.vision

import com.cocauto.helper.model.AutoUpgradeConfig
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.FullStorageCondition
import com.cocauto.helper.model.HomeFarmConfig
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LootMatchMode
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.model.StorageFillLevels
import com.cocauto.helper.model.UpgradeCandidate
import com.cocauto.helper.model.UpgradePriorityOrder
import com.cocauto.helper.model.WallCurrencyMode
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pure Kotlin OCR line representation mapped to full-screen normalized coordinates.
 */
data class OcrTextLine(
    val text: String,
    val bounds: NormRect,
) {
    val center: NormPoint get() = bounds.centerPoint()
}

/**
 * Pure Kotlin parsers and decision helpers for Clash of Clans OCR outputs (English & Vietnamese).
 */
object ResourceParser {
    private const val MAX_PLAUSIBLE_RESOURCE = 50_000_000L

    private val diacriticRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val builderRatioRegex = Regex("""([0-9OoIl|])\s*/\s*([1-7OoIl|])""")
    private val armyCampRatioRegex = Regex("""([0-9OoIl|]{1,3})\s*/\s*([0-9OoIl|]{2,3})""")
    private val numericClusterRegex = Regex("""[0-9OoIl|SsBbZz][0-9OoIl|SsBbZz ,.'`]{0,14}""")
    private val durationUnitRegex = Regex(
        """(\d+)\s*(ngay|ng|days?|d|gio|hours?|hrs?|h|phut|ph|mins?|m|giay|gi|secs?|s)\b""",
    )
    private val ocrLeadingOneBeforeUnitRegex = Regex(
        """(?<![a-z0-9])[il|](?=\s*(?:ngay|ng|days?|d\b|gio|hours?|hrs?|h\b|phut|ph|mins?|m\b|giay|gi|secs?|s\b))""",
    )
    private val subLabelOnlyRegex = Regex("""^(moi|new|cap\s*\d+|level\s*\d+|lv\.?\s*\d+)$""")

    /**
     * Strips Vietnamese diacritics and lowercases text for reliable keyword matching.
     */
    fun normalizeForMatch(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return diacriticRegex.replace(decomposed, "")
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Parses a Clash of Clans resource number from OCR text (e.g. "1 250 400", "450,200", "O25 000").
     */
    fun parseResourceNumber(raw: String): Long? {
        if (raw.isBlank()) return null

        // Extract the best numeric-looking cluster first to avoid mixing words into digits
        val candidates = numericClusterRegex.findAll(raw)
            .mapNotNull { match -> cleanNumericCluster(match.value) }
            .filter { it in 1L..MAX_PLAUSIBLE_RESOURCE }
            .toList()

        if (candidates.isNotEmpty()) {
            return candidates.maxOrNull()
        }

        // Handle explicit zero when reading 0 Dark Elixir or 0 resources
        val trimmed = raw.trim()
        if (trimmed == "0" || trimmed.equals("O", ignoreCase = true)) {
            return 0L
        }
        return null
    }

    private fun cleanNumericCluster(cluster: String): Long? {
        val digitLikeCount = cluster.count { it.isDigit() }
        // Reject purely alphabetical words like "BOSS" or "SOLO"
        if (digitLikeCount == 0) return null

        val sb = StringBuilder(cluster.length)
        for (ch in cluster) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                'O', 'o' -> sb.append('0')
                'I', 'l', '|' -> sb.append('1')
                'S', 's' -> sb.append('5')
                'B' -> sb.append('8')
                'Z', 'z' -> sb.append('2')
                ' ', ',', '.', '\'', '`' -> Unit // CoC thousands separators
                else -> Unit
            }
        }
        if (sb.isEmpty() || sb.length > 9) return null
        return sb.toString().toLongOrNull()
    }

    /**
     * Parses top-to-bottom enemy loot lines (Gold, Elixir, optional Dark Elixir).
     */
    fun parseEnemyLootLines(lines: List<OcrTextLine>): LootReading {
        val sortedNumeric = lines
            .sortedBy { it.bounds.centerY }
            .mapNotNull { line ->
                val norm = normalizeForMatch(line.text)
                val letterCount = line.text.count { it.isLetter() }
                // Ignore header labels, trophy indicators, and enemy usernames containing digits (e.g. "Neptunium_237")
                if (norm.contains("loot") ||
                    norm.contains("chien loi") ||
                    norm.contains("troph") ||
                    norm.contains("cup") ||
                    letterCount >= 3
                ) {
                    null
                } else {
                    parseResourceNumber(line.text)?.let { value -> line.bounds.centerY to value }
                }
            }

        val gold = sortedNumeric.getOrNull(0)?.second ?: 0L
        val elixir = sortedNumeric.getOrNull(1)?.second ?: 0L
        val darkElixir = sortedNumeric.getOrNull(2)?.second?.takeIf { it <= 50_000L } ?: 0L
        val rawJoined = lines.joinToString(" | ") { it.text.trim() }

        return LootReading(
            gold = gold,
            elixir = elixir,
            darkElixir = darkElixir,
            rawText = rawJoined,
        )
    }

    /**
     * Parses actual loot earned from the post-battle Victory / Defeat summary screen
     * ("Bạn nhận được:" / "You got:" centered resource rows, ignoring % bonus, trophies & troop counts).
     */
    fun parseBattleSummaryLoot(lines: List<OcrTextLine>): LootReading {
        val centerRows = lines
            .filter { line ->
                line.bounds.centerX in 0.30f..0.72f && line.bounds.centerY in 0.30f..0.74f
            }
            .sortedBy { it.bounds.centerY }

        val values = mutableListOf<Long>()
        for (line in centerRows) {
            val trimmed = line.text.trim()
            val norm = normalizeForMatch(trimmed)
            if (trimmed.contains("%") ||
                trimmed.startsWith("+") ||
                trimmed.startsWith("-") ||
                norm.contains("thuong") ||
                norm.contains("bonus") ||
                norm.contains("sao") ||
                norm.contains("star") ||
                norm.contains("cup") ||
                norm.contains("troph") ||
                norm.contains("binh linh") ||
                norm.contains("troops") ||
                norm.contains("nhan duoc") ||
                norm.contains("you got") ||
                trimmed.count { it.isLetter() } >= 3
            ) {
                continue
            }
            val num = parseResourceNumber(trimmed) ?: continue
            if (num in 50L..5_000_000L) {
                values.add(num)
            }
        }

        val gold = values.getOrNull(0) ?: 0L
        val elixir = values.getOrNull(1) ?: 0L
        val darkElixir = values.getOrNull(2)?.takeIf { it <= 50_000L } ?: 0L
        return LootReading(
            gold = gold,
            elixir = elixir,
            darkElixir = darkElixir,
            rawText = centerRows.joinToString(" | ") { it.text.trim() },
        )
    }

    /**
     * Parses free/total builder badge text like "2/5" or "0 / 6".
     */
    fun parseBuilderStatus(raw: String): BuilderStatus? {
        val match = builderRatioRegex.find(raw) ?: return null
        val free = fixSingleDigit(match.groupValues[1]) ?: return null
        val total = fixSingleDigit(match.groupValues[2]) ?: return null
        val status = BuilderStatus(freeBuilders = free, totalBuilders = total)
        return status.takeIf { it.isValid }
    }

    /**
     * Strictly extracts the Builder ratio ("0/2", "1/5") from OCR lines while avoiding
     * confusion with the adjacent Laboratory ratio ("1/1") to the right in the 2026 CoC HUD.
     * Always prefers the leftmost valid fraction.
     */
    fun parseStrictBuilderStatus(lines: List<OcrTextLine>): BuilderStatus? {
        if (lines.isEmpty()) return null
        val ordered = lines.sortedBy { it.bounds.left }
        for (line in ordered) {
            val matches = builderRatioRegex.findAll(line.text).toList()
            if (matches.isEmpty()) continue
            // Always take the leftmost fraction inside a merged line like "0/2 1/1"
            val firstMatch = matches.first()
            val free = fixSingleDigit(firstMatch.groupValues[1]) ?: continue
            val total = fixSingleDigit(firstMatch.groupValues[2]) ?: continue
            val status = BuilderStatus(freeBuilders = free, totalBuilders = total)
            if (status.isValid) return status
        }
        return null
    }

    private fun fixSingleDigit(token: String): Int? = when (token) {
        "O", "o" -> 0
        "I", "l", "|" -> 1
        else -> token.toIntOrNull()
    }

    /**
     * Evaluates whether an enemy base meets the user's configured loot thresholds.
     */
    fun matchesLootFilter(loot: LootReading, config: HomeFarmConfig): Boolean {
        if (!loot.isValid) return false
        return when (config.lootMatchMode) {
            LootMatchMode.ALL_MATCH -> {
                loot.gold >= config.minGold &&
                    loot.elixir >= config.minElixir &&
                    loot.darkElixir >= config.minDarkElixir
            }

            LootMatchMode.ANY_MATCH -> {
                val goldOk = config.minGold > 0L && loot.gold >= config.minGold
                val elixirOk = config.minElixir > 0L && loot.elixir >= config.minElixir
                val darkOk = config.minDarkElixir > 0L && loot.darkElixir >= config.minDarkElixir
                val allThresholdsZero = config.minGold <= 0L && config.minElixir <= 0L && config.minDarkElixir <= 0L
                goldOk || elixirOk || darkOk || allThresholdsZero
            }

            LootMatchMode.COMBINED_SUM -> {
                val combinedOk = loot.combinedGoldElixir >= config.minCombinedGoldElixir
                val darkOk = config.minDarkElixir <= 0L || loot.darkElixir >= config.minDarkElixir
                combinedOk && darkOk
            }
        }
    }

    /**
     * Parses an Army Camp capacity ratio like "115/115", "0/135", or "200 / 200" from the Army Confirm
     * ("Quân đội của tôi") screen while ignoring Builder ("0/3") and Lab ("0/1") badges (max < 20).
     */
    fun parseArmyCampCapacity(lines: List<OcrTextLine>): Pair<Int, Int>? {
        for (line in lines) {
            for (match in armyCampRatioRegex.findAll(line.text)) {
                val cur = cleanSmallDigits(match.groupValues[1]) ?: continue
                val max = cleanSmallDigits(match.groupValues[2]) ?: continue
                if (max in 20..400 && cur in 0..max) {
                    return cur to max
                }
            }
        }
        return null
    }

    private fun cleanSmallDigits(raw: String): Int? {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                'O', 'o' -> sb.append('0')
                'I', 'l', '|' -> sb.append('1')
            }
        }
        return sb.toString().toIntOrNull()
    }

    /**
     * Parses a Clash of Clans upgrade duration string (Vietnamese & English) into total seconds.
     * Examples: "15ph" -> 900, "1giờ" -> 3600, "2giờ 30ph" -> 9000, "1ng 12giờ" -> 129600,
     * "45s" -> 45, "1d 6h" -> 108000, "Tức thì" / "Instant" -> 0.
     */
    fun parseUpgradeDurationSeconds(raw: String): Long? {
        val norm = normalizeForMatch(raw)
        if (norm.isBlank()) return null
        if (norm.contains("tuc thi") || norm.contains("instant")) return 0L

        val cleaned = ocrLeadingOneBeforeUnitRegex.replace(norm, "1")
        val matches = durationUnitRegex.findAll(cleaned).toList()
        if (matches.isEmpty()) return null

        var totalSeconds = 0L
        for (m in matches) {
            val amount = m.groupValues[1].toLongOrNull() ?: continue
            val multiplier = when (m.groupValues[2]) {
                "ngay", "ng", "day", "days", "d" -> 86_400L
                "gio", "hour", "hours", "hr", "hrs", "h" -> 3_600L
                "phut", "ph", "min", "mins", "m" -> 60L
                "giay", "gi", "sec", "secs", "s" -> 1L
                else -> 0L
            }
            totalSeconds += amount * multiplier
        }
        return totalSeconds.takeIf { it >= 0L }
    }

    fun formatDurationShort(seconds: Long): String {
        if (seconds !in 0L..(365L * 86_400L)) return "?"
        if (seconds == 0L) return "tức thì"
        val days = seconds / 86_400L
        val hours = (seconds % 86_400L) / 3_600L
        val mins = (seconds % 3_600L) / 60L
        val secs = seconds % 60L
        return when {
            days > 0L && hours > 0L -> "${days}ng ${hours}giờ"
            days > 0L -> "${days}ng"
            hours > 0L && mins > 0L -> "${hours}giờ ${mins}ph"
            hours > 0L -> "${hours}giờ"
            mins > 0L -> "${mins}ph"
            else -> "${secs}gi"
        }
    }

    private fun stripDurationTokens(raw: String): String {
        val norm = normalizeForMatch(raw)
        if (norm.contains("tuc thi") || norm.contains("instant")) return ""
        val cleaned = ocrLeadingOneBeforeUnitRegex.replace(norm, "1")
        return if (durationUnitRegex.containsMatchIn(cleaned)) {
            val stripped = durationUnitRegex.replace(cleaned, "").trim()
            if (stripped.isBlank()) "" else raw.replace(
                Regex("""(?i)\b[0-9Il|]+\s*(ngày|ng|days?|d|giờ|gio|hours?|hrs?|h|phút|phut|ph|mins?|m|giây|giay|gi|secs?|s)\b"""),
                "",
            ).replace(Regex("\\s+"), " ").trim()
        } else {
            raw.trim()
        }
    }

    private data class ParsedDrawerRow(
        val items: List<OcrTextLine>,
        val centerY: Float,
        val fullNorm: String,
        val cleanName: String,
        val costItem: OcrTextLine?,
        val costValue: Long?,
        val durationSec: Long?,
        val isSubLabelOnly: Boolean,
        val isNewTag: Boolean,
        val isSectionNew: Boolean,
        val isSectionOther: Boolean,
    )

    /**
     * Groups OCR lines inside the 2026 Builder "Công trình và Tường" right-hand sidebar (2-line cards:
     * Name + Duration on row 1, Mới/Cấp X + Cost on row 2) OR classic 1-line Builder/Lab lists into
     * structured [UpgradeCandidate]s, automatically skipping standalone in-progress timer rows & descriptions.
     */
    fun parseUpgradeCandidates(
        lines: List<OcrTextLine>,
        currencyResolver: (OcrTextLine) -> CurrencyType = { CurrencyType.UNKNOWN },
        redCostResolver: (OcrTextLine) -> Boolean = { false },
    ): List<UpgradeCandidate> {
        if (lines.isEmpty()) return emptyList()

        // Cluster lines belonging to the same horizontal row (0.022f separates 2-line 2026 cards cleanly!)
        val rawRows = mutableListOf<MutableList<OcrTextLine>>()
        for (line in lines.sortedBy { it.bounds.centerY }) {
            val existingRow = rawRows.firstOrNull { row ->
                abs(row.first().bounds.centerY - line.bounds.centerY) <= 0.022f
            }
            if (existingRow != null) {
                existingRow.add(line)
            } else {
                rawRows.add(mutableListOf(line))
            }
        }

        val parsedRows = rawRows.map { row ->
            val sorted = row.sortedBy { it.bounds.left }
            val fullText = sorted.joinToString(" ") { it.text.trim() }
            val fullNorm = normalizeForMatch(fullText)

            val isSectionNew = fullNorm.contains("new building") ||
                fullNorm.contains("xay moi") ||
                fullNorm.contains("cong trinh moi") ||
                fullNorm.contains("chua xay")
            val isSectionOther = fullNorm.contains("suggested") ||
                fullNorm.contains("de xuat") ||
                fullNorm.contains("goi y") ||
                fullNorm.contains("in progress") ||
                fullNorm.contains("dang nang") ||
                fullNorm.contains("other upgrades") ||
                fullNorm.contains("nang cap khac") ||
                fullNorm.contains("da loc") ||
                fullNorm.contains("cong trinh va tuong") ||
                fullNorm.contains("buildings and walls")

            var costItem: OcrTextLine? = null
            var costValue: Long? = null
            var durationSec: Long? = null
            val nameParts = mutableListOf<String>()

            for (item in sorted) {
                val itemDur = parseUpgradeDurationSeconds(item.text)
                if (itemDur != null) {
                    durationSec = itemDur
                    val remainder = stripDurationTokens(item.text)
                    if (remainder.isNotBlank()) {
                        nameParts.add(remainder)
                    }
                    continue
                }
                val parsedNum = parseResourceNumber(item.text)
                val letters = item.text.count { it.isLetter() }
                if (parsedNum != null && parsedNum >= 100L && letters <= 2) {
                    costItem = item
                    costValue = parsedNum
                } else if (item.text.isNotBlank()) {
                    nameParts.add(item.text.trim())
                }
            }

            val cleanName = nameParts.joinToString(" ").trim()
            val normName = normalizeForMatch(cleanName)
            val isSubLabelOnly = cleanName.isBlank() || subLabelOnlyRegex.matches(normName)
            val isNewTag = normName == "moi" ||
                normName == "new" ||
                normName.startsWith("new ") ||
                normName.endsWith(" new") ||
                normName.contains("(new)") ||
                normName.contains("xay moi") ||
                normName.contains("(moi)")

            ParsedDrawerRow(
                items = sorted,
                centerY = sorted.map { it.bounds.centerY }.average().toFloat(),
                fullNorm = fullNorm,
                cleanName = cleanName,
                costItem = costItem,
                costValue = costValue,
                durationSec = durationSec,
                isSubLabelOnly = isSubLabelOnly,
                isNewTag = isNewTag,
                isSectionNew = isSectionNew,
                isSectionOther = isSectionOther,
            )
        }

        val candidates = mutableListOf<UpgradeCandidate>()
        var insideNewBuildingSection = false
        var idx = 0

        while (idx < parsedRows.size) {
            val row = parsedRows[idx]
            if (row.isSectionNew) {
                insideNewBuildingSection = true
                idx++
                continue
            }
            if (row.isSectionOther) {
                insideNewBuildingSection = false
                idx++
                continue
            }

            // Skip bare "Mới"/"New" section header ONLY when it has no cost attached (classic layout)
            if (row.costItem == null && (row.fullNorm == "new" || row.fullNorm == "moi")) {
                insideNewBuildingSection = true
                idx++
                continue
            }

            // Case 1: Classic 1-line row containing BOTH building name AND cost on the same line
            if (!row.isSubLabelOnly && row.cleanName.isNotBlank() && row.costItem != null && row.costValue != null) {
                val normName = normalizeForMatch(row.cleanName)
                val isWall = normName.contains("wall") || normName.contains("tuong")
                val isNew = (insideNewBuildingSection || row.isNewTag) && !isWall
                val currency = currencyResolver(row.costItem)
                val isRed = redCostResolver(row.costItem)
                val dur = row.durationSec ?: if (isWall) 0L else Long.MAX_VALUE
                candidates.add(
                    UpgradeCandidate(
                        name = row.cleanName,
                        cost = row.costValue,
                        currency = currency,
                        tapPoint = NormPoint(
                            x = ((row.items.first().bounds.left + row.items.last().bounds.right) * 0.5f)
                                .coerceIn(0.10f, 0.93f),
                            y = row.centerY,
                        ),
                        isWall = isWall,
                        isNewBuilding = isNew,
                        durationSeconds = dur,
                        isUnaffordableRed = isRed,
                    ),
                )
                idx++
                continue
            }

            // Case 2: 2026 2-line sidebar card — Row i = Name (+ Duration), Row i+1 = Mới/Cấp X + Cost
            if (!row.isSubLabelOnly && row.cleanName.isNotBlank() && row.costItem == null) {
                // Skip prose description lines on expanded cards (e.g. "Thổi bay những kẻ tấn công...")
                val looksLikeDescription = row.cleanName.length > 42 || row.cleanName.endsWith(".")
                val nextRow = parsedRows.getOrNull(idx + 1)
                val canMergeWithNext = !looksLikeDescription &&
                    nextRow != null &&
                    !nextRow.isSectionNew &&
                    !nextRow.isSectionOther &&
                    (nextRow.centerY - row.centerY) in 0.015f..0.072f &&
                    nextRow.costItem != null &&
                    nextRow.costValue != null &&
                    nextRow.isSubLabelOnly

                if (canMergeWithNext) {
                    val mergedNext = nextRow
                    val nextCostItem = mergedNext.costItem
                    val nextCostValue = mergedNext.costValue
                    val normName = normalizeForMatch(row.cleanName)
                    val isWall = normName.contains("wall") || normName.contains("tuong")
                    val isNew = (insideNewBuildingSection || row.isNewTag || mergedNext.isNewTag) && !isWall
                    val currency = currencyResolver(nextCostItem)
                    val isRed = redCostResolver(nextCostItem)
                    val dur = row.durationSec ?: mergedNext.durationSec ?: if (isWall) 0L else Long.MAX_VALUE
                    val minLeft = minOf(row.items.first().bounds.left, mergedNext.items.first().bounds.left)
                    val maxRight = maxOf(row.items.last().bounds.right, mergedNext.items.last().bounds.right)
                    candidates.add(
                        UpgradeCandidate(
                            name = row.cleanName,
                            cost = nextCostValue,
                            currency = currency,
                            tapPoint = NormPoint(
                                x = ((minLeft + maxRight) * 0.5f).coerceIn(0.10f, 0.93f),
                                y = ((row.centerY + mergedNext.centerY) * 0.5f).coerceIn(0.08f, 0.88f),
                            ),
                            isWall = isWall,
                            isNewBuilding = isNew,
                            durationSeconds = dur,
                            isUnaffordableRed = isRed,
                        ),
                    )
                    idx += 2
                    continue
                }
            }

            // Otherwise: standalone in-progress countdown row (no cost below) or description prose -> skip!
            idx++
        }
        return candidates
    }

    /**
     * Checks whether a building or lab unit name matches any configured avoid/blacklist keyword.
     */
    fun isSkippedByKeywords(name: String, skipKeywords: List<String>): Boolean {
        if (skipKeywords.isEmpty()) return false
        val normalizedName = normalizeForMatch(name)
        return skipKeywords.any { rawKeyword ->
            val key = normalizeForMatch(rawKeyword)
            key.isNotBlank() && normalizedName.contains(key)
        }
    }

    /**
     * Checks whether spending [cost] of [currency] preserves the user's minimum kept resources.
     */
    fun respectsResourceReserve(
        home: HomeResources,
        cost: Long,
        currency: CurrencyType,
        config: AutoUpgradeConfig,
    ): Boolean {
        if (currency == CurrencyType.GEMS) return false
        if (cost <= 0L) return true
        return when (currency) {
            CurrencyType.GOLD -> home.gold >= cost + config.minKeepGold
            CurrencyType.ELIXIR -> home.elixir >= cost + config.minKeepElixir
            CurrencyType.DARK_ELIXIR -> home.darkElixir >= cost + config.minKeepDarkElixir
            CurrencyType.GEMS -> false
            CurrencyType.UNKNOWN -> {
                // When currency icon color is uncertain, require either Gold or Elixir to safely cover cost + reserve
                (home.gold >= cost + config.minKeepGold) || (home.elixir >= cost + config.minKeepElixir)
            }
        }
    }

    /**
     * Selects the best non-wall building action (New Building construction OR existing Building Upgrade)
     * respecting enabled toggles, Gems/red-unaffordable rejection, blacklist keywords, reserves, and priority order.
     * In [UpgradePriorityOrder.SHORTEST_TIME_FIRST], shortest duration buildings win first (tie-broken by cheapest cost),
     * followed by remaining affordable buildings.
     */
    fun selectBestBuildingUpgrade(
        candidates: List<UpgradeCandidate>,
        home: HomeResources,
        config: AutoUpgradeConfig,
    ): UpgradeCandidate? {
        val eligible = candidates.filter { item ->
            !item.isWall &&
                item.currency != CurrencyType.GEMS &&
                !item.isUnaffordableRed &&
                item.cost >= 100L &&
                ((item.isNewBuilding && config.autoBuildNewBuildings) ||
                    (!item.isNewBuilding && config.autoUpgradeBuildings)) &&
                !isSkippedByKeywords(item.name, config.skipBuildingKeywords) &&
                respectsResourceReserve(home, item.cost, item.currency, config)
        }
        if (eligible.isEmpty()) return null

        val pool = if (config.autoBuildNewBuildings && config.prioritizeNewBuildingsFirst) {
            eligible.filter { it.isNewBuilding }.ifEmpty { eligible }
        } else {
            eligible
        }

        return when (config.upgradePriority) {
            UpgradePriorityOrder.SHORTEST_TIME_FIRST -> pool.minWithOrNull(
                compareBy<UpgradeCandidate>(
                    { if (it.hasKnownDuration) it.durationSeconds else Long.MAX_VALUE },
                    { if (it.cost > 0L) it.cost else Long.MAX_VALUE },
                ),
            )
            UpgradePriorityOrder.CHEAPEST_FIRST -> pool.minByOrNull { if (it.cost > 0L) it.cost else Long.MAX_VALUE }
            UpgradePriorityOrder.MOST_EXPENSIVE_FIRST -> pool.maxByOrNull { it.cost }
            UpgradePriorityOrder.TOP_OF_LIST -> pool.first()
        }
    }

    /**
     * Selects an affordable Wall ("Tường") candidate from the Builder drawer fitting current Gold/Elixir,
     * configured thresholds, and reserves — run LAST after all eligible buildings have been upgraded.
     */
    fun selectBestWallCandidate(
        candidates: List<UpgradeCandidate>,
        home: HomeResources,
        builderStatus: BuilderStatus,
        config: AutoUpgradeConfig,
    ): Pair<UpgradeCandidate, CurrencyType>? {
        if (!config.autoUpgradeWalls || builderStatus.freeBuilders < 1) return null
        val wallCandidates = candidates.filter {
            it.isWall && !it.isUnaffordableRed && it.currency != CurrencyType.GEMS
        }
        for (wall in wallCandidates) {
            val chosenCurrency = when (wall.currency) {
                CurrencyType.GOLD -> {
                    val allowed = config.wallCurrencyMode == WallCurrencyMode.GOLD_ONLY ||
                        config.wallCurrencyMode == WallCurrencyMode.BOTH
                    if (allowed &&
                        home.gold >= config.wallGoldThreshold &&
                        respectsResourceReserve(home, wall.cost, CurrencyType.GOLD, config)
                    ) {
                        CurrencyType.GOLD
                    } else {
                        null
                    }
                }
                CurrencyType.ELIXIR -> {
                    val allowed = config.wallCurrencyMode == WallCurrencyMode.ELIXIR_ONLY ||
                        config.wallCurrencyMode == WallCurrencyMode.BOTH
                    if (allowed &&
                        home.elixir >= config.wallElixirThreshold &&
                        respectsResourceReserve(home, wall.cost, CurrencyType.ELIXIR, config)
                    ) {
                        CurrencyType.ELIXIR
                    } else {
                        null
                    }
                }
                else -> chooseWallUpgradeCurrency(home, builderStatus, config, estimatedWallCost = wall.cost)
            }
            if (chosenCurrency != null) {
                return wall to chosenCurrency
            }
        }
        return null
    }

    /**
     * Selects the best Laboratory research candidate respecting blacklist, reserves, and DE preference.
     */
    fun selectBestLabUpgrade(
        candidates: List<UpgradeCandidate>,
        home: HomeResources,
        config: AutoUpgradeConfig,
    ): UpgradeCandidate? {
        val eligible = candidates.filter { item ->
            item.cost >= 100L &&
                item.currency != CurrencyType.GEMS &&
                !item.isUnaffordableRed &&
                !isSkippedByKeywords(item.name, config.skipLabKeywords) &&
                respectsResourceReserve(home, item.cost, item.currency, config)
        }
        if (eligible.isEmpty()) return null

        val prioritized = if (config.labPreferDarkElixir) {
            eligible.filter { it.currency == CurrencyType.DARK_ELIXIR }.ifEmpty { eligible }
        } else {
            eligible
        }

        return when (config.upgradePriority) {
            UpgradePriorityOrder.SHORTEST_TIME_FIRST -> prioritized.minWithOrNull(
                compareBy<UpgradeCandidate>(
                    { if (it.hasKnownDuration) it.durationSeconds else Long.MAX_VALUE },
                    { if (it.cost > 0L) it.cost else Long.MAX_VALUE },
                ),
            )
            UpgradePriorityOrder.CHEAPEST_FIRST -> prioritized.minByOrNull { if (it.cost > 0L) it.cost else Long.MAX_VALUE }
            UpgradePriorityOrder.MOST_EXPENSIVE_FIRST -> prioritized.maxByOrNull { it.cost }
            UpgradePriorityOrder.TOP_OF_LIST -> prioritized.first()
        }
    }

    /**
     * Computes how many builders may be used for building upgrades after keeping reserved wall builders.
     */
    fun usableBuildingBuilders(status: BuilderStatus, reserveForWalls: Int): Int {
        if (!status.isValid) return 0
        return (status.freeBuilders - reserveForWalls.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * Decides which currency (GOLD or ELIXIR) should be used for an automatic wall upgrade right now,
     * or null if wall upgrade thresholds / builders / reserves are not satisfied.
     */
    fun chooseWallUpgradeCurrency(
        home: HomeResources,
        builderStatus: BuilderStatus,
        config: AutoUpgradeConfig,
        estimatedWallCost: Long = 0L,
    ): CurrencyType? {
        if (!config.autoUpgradeWalls) return null
        if (builderStatus.freeBuilders < 1) return null

        val goldEligible = (config.wallCurrencyMode == WallCurrencyMode.GOLD_ONLY ||
            config.wallCurrencyMode == WallCurrencyMode.BOTH) &&
            home.gold >= config.wallGoldThreshold &&
            home.gold - estimatedWallCost >= config.minKeepGold

        val elixirEligible = (config.wallCurrencyMode == WallCurrencyMode.ELIXIR_ONLY ||
            config.wallCurrencyMode == WallCurrencyMode.BOTH) &&
            home.elixir >= config.wallElixirThreshold &&
            home.elixir - estimatedWallCost >= config.minKeepElixir

        return when {
            goldEligible && elixirEligible -> if (home.gold >= home.elixir) CurrencyType.GOLD else CurrencyType.ELIXIR
            goldEligible -> CurrencyType.GOLD
            elixirEligible -> CurrencyType.ELIXIR
            else -> null
        }
    }

    /**
     * Evaluates whether Home Village storages are full based on automatic visual bar fill %
     * and/or optional explicit numeric capacity caps.
     */
    fun isStorageFull(
        home: HomeResources,
        fill: StorageFillLevels,
        config: HomeFarmConfig,
    ): Boolean {
        if (!config.stopWhenStorageFull) return false
        val pctThreshold = config.fullStorageFillPercent.coerceIn(50, 100)

        val goldFull = (config.fullStorageGoldCap > 0L && home.gold >= config.fullStorageGoldCap) ||
            fill.goldPercent >= pctThreshold
        val elixirFull = (config.fullStorageElixirCap > 0L && home.elixir >= config.fullStorageElixirCap) ||
            fill.elixirPercent >= pctThreshold
        val darkFull = (config.fullStorageDarkElixirCap > 0L && home.darkElixir >= config.fullStorageDarkElixirCap) ||
            fill.darkElixirPercent >= pctThreshold

        return when (config.fullStorageCondition) {
            FullStorageCondition.GOLD_AND_ELIXIR -> goldFull && elixirFull
            FullStorageCondition.GOLD_OR_ELIXIR -> goldFull || elixirFull
            FullStorageCondition.ALL_INCLUDING_DE -> goldFull && elixirFull && darkFull
        }
    }

    // --- Screen state bilingual keyword helpers ---

    fun isDisconnectDialogText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("reload") ||
            norm.contains("try again") ||
            norm.contains("connection lost") ||
            norm.contains("client and server") ||
            norm.contains("tai lai") ||
            norm.contains("thu lai") ||
            norm.contains("mat ket noi") ||
            norm.contains("ket noi lai") ||
            norm.contains("out of sync")
    }

    fun isPersonalBreakText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("take a break") ||
            norm.contains("another device") ||
            norm.contains("villagers need a rest") ||
            norm.contains("thiet bi khac") ||
            norm.contains("nghi ngoi") ||
            norm.contains("bao tri") ||
            norm.contains("maintenance")
    }

    fun isReturnHomeText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("return home") ||
            norm.contains("ve lang") ||
            norm.contains("ve nha") ||
            norm.contains("tro ve") ||
            norm.contains("return to")
    }

    /**
     * Detects the post-battle Victory / Defeat summary screen reliably even if stylized
     * font OCR slightly garbles the short "Về làng" button label.
     */
    fun isBattleSummaryScreenText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return isReturnHomeText(norm) ||
            norm.contains("chien thang") ||
            norm.contains("bai tran") ||
            norm.contains("victory") ||
            norm.contains("defeat") ||
            norm.contains("ban nhan duoc") ||
            norm.contains("you got") ||
            norm.contains("so binh linh da su dung") ||
            norm.contains("troops used") ||
            norm.contains("100% thuong")
    }

    fun isNextButtonText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("next") ||
            norm.contains("tiep") ||
            norm.contains("tiep theo")
    }

    fun isScoutBannerText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("available loot") ||
            norm.contains("chien loi pham") ||
            norm.contains("battle starts") ||
            norm.contains("tran dau bat dau") ||
            norm.contains("bat dau sau")
    }

    fun isShieldWarningDialogText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("shield") ||
            norm.contains("khien") ||
            norm.contains("bao ve") ||
            norm.contains("disable your shield") ||
            norm.contains("giam thoi gian")
    }

    fun isEndBattleText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("end battle") ||
            norm.contains("surrender") ||
            norm.contains("ket thuc") ||
            norm.contains("dau hang")
    }

    fun isAttackMenuText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        if (norm.contains("choi don") || norm.contains("single player") ||
            norm.contains("luyen tap") || norm.contains("practice") ||
            norm.contains("chinh doi") || norm.contains("edit army") ||
            norm.contains("nhat ky") || norm.contains("lich su") ||
            norm.contains("battle log") || norm.contains("huan luyen") ||
            norm.contains("train") || norm.contains("su kien")
        ) {
            return false
        }
        return norm.contains("find a match") ||
            norm.contains("find now") ||
            norm.contains("tim tran") ||
            norm.contains("tim doi thu") ||
            norm.contains("tim kiem") ||
            norm.contains("battle!") ||
            norm.contains("attack") ||
            norm.contains("tan cong") ||
            norm.contains("chien dau")
    }

    fun isConfirmDialogButtonText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm == "ok" ||
            norm == "okay" ||
            norm == "yes" ||
            norm == "co" ||
            norm.contains("dong y") ||
            norm.contains("xac nhan") ||
            norm.contains("tiep tuc") ||
            norm == "continue"
    }

    fun isHomeAttackButtonText(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("chien dau") ||
            norm.contains("attack") ||
            norm.contains("tan cong") ||
            norm == "battle"
    }

    fun isStarBonusOrRewardPopup(raw: String): Boolean {
        val norm = normalizeForMatch(raw)
        return norm.contains("star bonus") ||
            norm.contains("phan thuong sao") ||
            norm.contains("chao mung") ||
            norm.contains("welcome back") ||
            norm.contains("vang mat") ||
            norm.contains("nang cap da hoan tat") ||
            norm.contains("kho chua tam thoi") ||
            norm.contains("vat pham da duoc") ||
            norm.contains("tiep tuc") ||
            norm.contains("claim") ||
            norm.contains("nhan thuong") ||
            norm.contains("okay") ||
            norm.contains("dong y")
    }
}
