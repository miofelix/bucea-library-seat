package cn.edu.bucea.library

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.CommunityGroupEntry
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.openCommunityGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Intercepts every outgoing intent; these tests never open QQ or change group membership. */
@RunWith(AndroidJUnit4::class)
class CommunityGroupInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun qqCardUsesTheRequestedGroupAndFallsBackToTimWithoutLaunchingExternalApps() {
        val attempted = mutableListOf<Intent>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun startActivity(intent: Intent) {
                attempted += intent
                if (intent.`package` == "com.tencent.mobileqq") throw ActivityNotFoundException("Synthetic QQ absence")
            }
        }
        openCommunityGroup(context)
        assertEquals(listOf("com.tencent.mobileqq", "com.tencent.tim"), attempted.map { it.`package` })
        attempted.forEach { intent ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            val uri = requireNotNull(intent.data)
            assertEquals("mqqapi", uri.scheme)
            assertEquals("card", uri.host)
            assertEquals("/show_pslcard", uri.path)
            assertEquals("1109482748", uri.getQueryParameter("uin"))
            assertEquals("group", uri.getQueryParameter("card_type"))
            assertEquals("qrcode", uri.getQueryParameter("source"))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test fun communityEntryOffersSeparateAccessibleTapAndLongPressActions() {
        val open = AtomicInteger()
        val copy = AtomicInteger()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.NAILONG_YELLOW) {
                        CommunityGroupEntry(onOpen = { open.incrementAndGet() }, onCopy = { copy.incrementAndGet() })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            fun findEntry(): AccessibilityNodeInfo? {
                if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
                fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.packageName?.toString() == instrumentation.targetContext.packageName &&
                        node.isVisibleToUser && node.isClickable && node.isLongClickable &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString() == "复制 QQ 群号" }) return node
                    repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it)?.let { result -> return result } } }
                    return null
                }
                return visit(root)
            }
            fun requireEntry(): AccessibilityNodeInfo {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    findEntry()?.let { return it }
                    SystemClock.sleep(100)
                }
                fail("QQ entry must expose both actions after its Compose accessibility frame is ready")
                throw IllegalStateException("No QQ entry")
            }
            fun waitForCounter(counter: AtomicInteger, expected: Int) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline && counter.get() != expected) SystemClock.sleep(100)
                assertEquals(expected, counter.get())
            }
            val entry = requireEntry()
            assertTrue(entry.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "打开 QQ 加群页面" })
            assertTrue(entry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            waitForCounter(open, 1)
            assertEquals(0, copy.get())
            assertTrue(requireEntry().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, open.get())
            waitForCounter(copy, 1)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
