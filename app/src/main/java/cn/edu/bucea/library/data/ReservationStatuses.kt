package cn.edu.bucea.library.data

/** Classifies the school's displayed labels without translating or inferring server status codes. */
internal object ReservationStatuses {
    val knownLabels = setOf(
        "已预约", "预约", "待签到", "已签到", "使用中", "暂离",
        "已履约", "履约", "失约", "已失约", "已取消", "取消",
        "已完成", "已违约", "违约", "已结束", "结束使用",
    )

    fun isInactive(status: String): Boolean =
        listOf("取消", "违约", "完成", "结束", "已履约", "失约", "CANCELED", "CANCELLED", "FINISHED", "STOPPED")
            .any { status.contains(it, ignoreCase = true) } || status.trim() == "履约"
}
