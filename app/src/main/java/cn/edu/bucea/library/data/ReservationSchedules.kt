package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.ReservationReceipt
import cn.edu.bucea.library.data.models.ReservationSchedule
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** Extracts booking schedules without treating unrelated event timestamps as reservation times. */
object ReservationSchedules {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val fullDate = Regex("""(?<!\d)(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})(?:\s*日)?(?!\d)""")
    private val monthDate = Regex("""(?<![\d./:：-])(\d{1,2})\s*(?:月|[-/])\s*(\d{1,2})(?:\s*日)?(?![\d./:：-])""")
    private val yearLabel = Regex("""(?<!\d)(\d{4})\s*年""")
    private val relativeDate = Regex("后天|明天|今天|昨天|今晚|明晚|昨晚|今日|明日|昨日")
    private val dateField = Regex("""^\s*(?:预约|使用)?日期\s*[:：]""")
    private val timeField = Regex("""^\s*(?:预约|使用)?(?:时间|时段)\s*[:：]""")
    private val unrelatedField = Regex("""^\s*(?:(?:预约)?(?:提交|创建|取消|签到|签退|操作|更新|生成|下单|完成|记录|通知|违约)(?:日期|时间)|凭证号|预约号|记录号|座位|位置|地点|状态)\s*[:：]""")
    private const val timeValue = """(\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?)"""
    private val timeRange = Regex("""(?<![\d:：])$timeValue\s*(?:--?|[—–~～至到])\s*$timeValue(?![\d:：])""")
    private val beginField = Regex("""^\s*(?:预约|使用)?(?:开始时间|起始时间|开始|起始|begin|start)\s*[:：]\s*$timeValue(?![\d:：])""", RegexOption.IGNORE_CASE)
    private val endField = Regex("""^\s*(?:预约|使用)?(?:结束时间|截止时间|结束|截止|end)\s*[:：]\s*$timeValue(?![\d:：])""", RegexOption.IGNORE_CASE)

    fun parse(
        title: String,
        details: List<String>,
        referenceDate: LocalDate = LocalDate.now(shanghai),
    ): ReservationSchedule {
        val fields = details.filterNot { unrelatedField.containsMatchIn(it) }
        val sources = listOf(title) + fields
        // A yearless month/day is only resolved when the source itself supplies one year.
        val sourceYear = sources.flatMap { source -> yearLabel.findAll(source).map { it.groupValues[1].toInt() }.toList() }
            .distinct().singleOrNull()
        val dateSources = fields.filter { dateField.containsMatchIn(it) } + listOf(title) +
            fields.filterNot { dateField.containsMatchIn(it) }
        val date = dateSources.firstNotNullOfOrNull { parseDate(dateField.replaceFirst(it, ""), referenceDate, sourceYear) }
        val timeSources = fields.filter { timeField.containsMatchIn(it) } + listOf(title) +
            fields.filterNot { timeField.containsMatchIn(it) }
        val range = timeSources.firstNotNullOfOrNull { timeRange.find(timeField.replaceFirst(it, "")) }
        val begin = fields.firstNotNullOfOrNull { beginField.find(it)?.groupValues?.get(1) }
            ?: range?.groupValues?.get(1)
        val end = fields.firstNotNullOfOrNull { endField.find(it)?.groupValues?.get(1) }
            ?: range?.groupValues?.get(2)
        return ReservationSchedule(date?.value, normalizeTime(begin), normalizeTime(end, allowEndOfDay = true), date?.label)
    }

    fun fromReceipt(
        receipt: ReservationReceipt,
        referenceDate: LocalDate = LocalDate.now(shanghai),
    ): ReservationSchedule = parse(receipt.onDate, emptyList(), referenceDate).copy(
        begin = normalizeTime(receipt.begin),
        end = normalizeTime(receipt.end, allowEndOfDay = true),
    )

    private data class ParsedDate(val value: String?, val label: String)

    private fun parseDate(source: String, referenceDate: LocalDate, sourceYear: Int?): ParsedDate? {
        val fullDates = fullDate.findAll(source).toList()
        if (fullDates.map { it.groupValues.drop(1).map(String::toInt) }.distinct().size > 1)
            return ParsedDate(null, fullDates.joinToString(" / ") { it.value.trim() })
        fullDates.firstOrNull()?.let { match ->
            return ParsedDate(validDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()), match.value.trim())
        }
        val relativeDates = relativeDate.findAll(source).toList()
        if (relativeDates.map { it.value }.distinct().size > 1)
            return ParsedDate(null, relativeDates.joinToString(" / ") { it.value })
        relativeDates.firstOrNull()?.let { match ->
            val days = when (match.value) {
                "后天" -> 2L
                "明天", "明晚", "明日" -> 1L
                "昨天", "昨晚", "昨日" -> -1L
                else -> 0L
            }
            return ParsedDate(referenceDate.plusDays(days).toString(), match.value)
        }
        val monthDates = monthDate.findAll(source).filter { match ->
            // Whitespace around a clock colon must not turn its minutes/hours into month/day.
            source.substring(0, match.range.first).trimEnd().lastOrNull() !in listOf(':', '：') &&
                source.substring(match.range.last + 1).trimStart().firstOrNull() !in listOf(':', '：')
        }.toList()
        if (monthDates.map { it.groupValues.drop(1).map(String::toInt) }.distinct().size > 1)
            return ParsedDate(null, monthDates.joinToString(" / ") { it.value.trim() })
        monthDates.firstOrNull()?.let { match ->
            val value = sourceYear?.let { validDate(it, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }
            return ParsedDate(value, match.value.trim())
        }
        return null
    }

    private fun validDate(year: Int, month: Int, day: Int): String? {
        if (year !in 1..9999) return null
        return try {
            LocalDate.of(year, month, day).toString()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun normalizeTime(raw: String?, allowEndOfDay: Boolean = false): String? {
        val fields = raw?.replace(Regex("\\s"), "")?.replace('：', ':')?.split(':') ?: return null
        if (fields.size !in 2..3) return null
        val hour = fields[0].toIntOrNull() ?: return null
        val minute = fields[1].toIntOrNull() ?: return null
        val second = if (fields.size == 3) fields[2].toIntOrNull() ?: return null else 0
        if (minute !in 0..59 || second !in 0..59 ||
            (hour !in 0..23 && !(allowEndOfDay && hour == 24 && minute == 0 && second == 0))) return null
        return "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
    }
}
