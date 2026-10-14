package cn.edu.bucea.library.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal const val CommunityGroup = "1109482748"

/** Open QQ's group card; membership confirmation remains in QQ. */
internal fun communityGroupIntent(packageName: String = "com.tencent.mobileqq"): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$CommunityGroup&card_type=group&source=qrcode"))
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openCommunityGroup(context: Context) {
    for (packageName in listOf("com.tencent.mobileqq", "com.tencent.tim")) {
        try {
            context.startActivity(communityGroupIntent(packageName))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the other official client without relying on package visibility queries.
        }
    }
    Toast.makeText(context, "未安装 QQ", Toast.LENGTH_SHORT).show()
}

internal fun copyCommunityGroup(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("北建大图书馆选座 QQ 交流群", CommunityGroup))
    Toast.makeText(context, "群号已复制", Toast.LENGTH_SHORT).show()
}
