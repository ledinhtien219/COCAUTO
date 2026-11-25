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
    private val numericClusterRegex = Regex("""[0-9OoIl|SsBbZz][0-9OoIl|SsBbZz ,.'`]{0,14}""")

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
                // Ignore header labels or trophy indicators
                if (norm.contains("loot") || norm.contains("chien loi") || norm.contains("troph") || norm.contains("cup")) {
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
     * Groups OCR lines inside the Builder Suggested Upgrades dropdown or Lab list into structured candidates.
     */
    fun parseUpgradeCandidates(
        lines: List<OcrTextLine>,
        currencyResolver: (OcrTextLine) -> CurrencyType = { CurrencyType.UNKNOWN },
    ): List<UpgradeCandidate> {
        if (lines.isEmpty()) return emptyList()

        // Cluster lines belonging to the same horizontal row
        val rows = mutableListOf<MutableList<OcrTextLine>>()
        for (line in lines.sortedBy { it.bounds.centerY }) {
            val existingRow = rows.firstOrNull { row ->
                abs(row.first().bounds.centerY - line.bounds.centerY) <= 0.036f
            }
            if (existingRow != null) {
                existingRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        val candidates = mutableListOf<UpgradeCandidate>()
        var insideNewBuildingSection = false

        for (row in rows) {
            val sortedLeftToRight = row.sortedBy { it.bounds.left }
            val fullNorm = normalizeForMatch(sortedLeftToRight.joinToString(" ") { it.text })

            // Track section headers ("New Buildings" / "Xây mới" vs "Suggested Upgrades" / "Đề xuất nâng cấp")
            if (fullNorm.contains("new building") ||
                fullNorm.contains("xay moi") ||
                fullNorm.contains("cong trinh moi") ||
                fullNorm.contains("chua xay") ||
                fullNorm == "new" ||
                fullNorm == "moi"
            ) {
                insideNewBuildingSection = true
                continue
            }

            if (fullNorm.contains("suggested") ||
                fullNorm.contains("de xuat") ||
                fullNorm.contains("goi y") ||
                fullNorm.contains("in progress") ||
                fullNorm.contains("dang nang") ||
                fullNorm.contains("other upgrades") ||
                fullNorm.contains("nang cap khac")
            ) {
                insideNewBuildingSection = false
                continue
            }

            var costLine: OcrTextLine? = null
            var costValue: Long? = null
            val nameParts = mutableListOf<String>()

            for (item in sortedLeftToRight) {
                val parsedNum = parseResourceNumber(item.text)
                val letters = item.text.count { it.isLetter() }
                if (parsedNum != null && parsedNum >= 100L && letters <= 2) {
                    costLine = item
                    costValue = parsedNum
                } else if (item.text.isNotBlank()) {
                    nameParts.add(item.text.trim())
                }
            }

            val name = nameParts.joinToString(" ").trim()
            if (name.isBlank()) continue

            val normName = normalizeForMatch(name)
            val isWall = normName.contains("wall") || normName.contains("tuong")
            val isNewTagged = insideNewBuildingSection ||
                normName.startsWith("new ") ||
                normName.endsWith(" new") ||
                normName.contains("(new)") ||
                normName.contains("xay moi") ||
                normName.contains("(moi)")
            val resolvedCost = costValue ?: 0L
            val currency = costLine?.let(currencyResolver) ?: CurrencyType.UNKNOWN
            val tapLine = sortedLeftToRight.first()

            candidates.add(
                UpgradeCandidate(
                    name = name,
                    cost = resolvedCost,
                    currency = currency,
                    tapPoint = NormPoint(
                        x = ((sortedLeftToRight.first().bounds.left + sortedLeftToRight.last().bounds.right) * 0.5f)
                            .coerceIn(0.1f, 0.9f),
                        y = tapLine.bounds.centerY,
                    ),
                    isWall = isWall,
                    isNewBuilding = isNewTagged && !isWall,
                )
            )
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
        if (cost <= 0L) return true
        return when (currency) {
            CurrencyType.GOLD -> home.gold >= cost + config.minKeepGold
            CurrencyType.ELIXIR -> home.elixir >= cost + config.minKeepElixir
            CurrencyType.DARK_ELIXIR -> home.darkElixir >= cost + config.minKeepDarkElixir
            CurrencyType.UNKNOWN -> {
                // When currency icon color is uncertain, require either Gold or Elixir to safely cover cost + reserve
                (home.gold >= cost + config.minKeepGold) || (home.elixir >= cost + config.minKeepElixir)
            }
        }
    }

    /**
     * Selects the best non-wall building action (New Building construction OR existing Building Upgrade)
     * respecting enabled toggles, new-building priority, blacklist keywords, reserves, and cost order.
     */
    fun selectBestBuildingUpgrade(
        candidates: List<UpgradeCandidate>,
        home: HomeResources,
        config: AutoUpgradeConfig,
    ): UpgradeCandidate? {
        val eligible = candidates.filter { item ->
            !item.isWall &&
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
            UpgradePriorityOrder.CHEAPEST_FIRST -> pool.minByOrNull { if (it.cost > 0L) it.cost else Long.MAX_VALUE }
            UpgradePriorityOrder.MOST_EXPENSIVE_FIRST -> pool.maxByOrNull { it.cost }
            UpgradePriorityOrder.TOP_OF_LIST -> pool.first()
        }
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
            norm.contains("ve nha") ||
            norm.contains("tro ve") ||
            norm.contains("return to")
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
            norm.contains("xac nhan")
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
            norm.contains("claim") ||
            norm.contains("nhan thuong") ||
            norm.contains("okay") ||
            norm.contains("dong y")
    }
}
