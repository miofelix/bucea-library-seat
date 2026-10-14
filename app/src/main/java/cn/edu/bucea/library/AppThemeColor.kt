package cn.edu.bucea.library

enum class AppThemeColor(val preferenceValue: String, val label: String, val primaryArgb: Long) {
    BUCEA_PURPLE("bucea_purple", "建大紫", 0xFF6C397F),
    BLUE("blue", "湖蓝", 0xFF315DA8),
    TEAL("teal", "青绿", 0xFF28766C),
    ORANGE("orange", "暖橙", 0xFFA85A20),
    ROSE("rose", "玫瑰", 0xFFAC4567);

    companion object {
        fun fromPreference(value: String?): AppThemeColor =
            entries.firstOrNull { it.preferenceValue == value } ?: BUCEA_PURPLE
    }
}
