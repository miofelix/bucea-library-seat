package cn.edu.bucea.library

enum class AppThemeColor(
    val preferenceValue: String,
    val label: String,
    val primaryArgb: Long,
    val onPrimaryArgb: Long = 0xFFFFFFFF,
    val readableAccentArgb: Long = primaryArgb,
) {
    BUCEA_PURPLE("bucea_purple", "建大紫", 0xFF6C397F),
    BLUE("blue", "湖蓝", 0xFF315DA8),
    TEAL("teal", "青绿", 0xFF28766C),
    ORANGE("orange", "暖橙", 0xFFA85A20, readableAccentArgb = 0xFF9F551E),
    ROSE("rose", "玫瑰", 0xFFAC4567),
    NAILONG_YELLOW("nailong_yellow", "奶龙黄", 0xFFFFCE32, 0xFF3C2B06, 0xFF735300);

    companion object {
        fun fromPreference(value: String?): AppThemeColor =
            entries.firstOrNull { it.preferenceValue == value } ?: BUCEA_PURPLE
    }
}
