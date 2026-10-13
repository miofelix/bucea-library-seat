package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local note edit through native UI; credentials/session remain unchanged and note is restored. */
@RunWith(AndroidJUnit4::class)
class LiveNativeAccountLabelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val app = "cn.edu.bucea.library"

    @Test fun editsOnlyTheLocalNoteWithAnEmptyPasswordAndRestoresIt() {
        assumeTrue("Native account label editing is opt-in", InstrumentationRegistry.getArguments().getString("live_account_label") == "true")
        val store = AccountStore(instrumentation.targetContext)
        val account = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assertNotNull("An active saved account is required", account)
        val original = requireNotNull(account)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click(waitForNode("Account navigation must be enabled after startup") { nodes().firstOrNull { it.text?.toString() == "账号" && it.isEnabled } })
            findScrolling("The saved account must be authenticated before editing its note") { nodes().firstOrNull { it.text?.toString() == "已登录" } }
            val before = store.accounts()
            val cookies = store.cookies(original.id)
            val signedOut = store.isSignedOut(original.id)
            val active = store.activeAccountId
            val description = "修改 ${original.username}"
            val editor = findScrolling("The active account's edit control must be present", backward = true) {
                nodes().firstOrNull { it.contentDescription?.toString() == description && it.isEnabled }
            }
            click(editor)
            waitForNode("An empty password must allow saving the note") { nodes().firstOrNull { it.text?.toString() == "保存备注" && it.isEnabled } }
            assertFalse("Editing only the note must not offer credential submission", nodes().any { it.text?.toString() == "保存账号" })
            val field = waitForNode("The editable note field must be present") { noteField() }
            val temporary = "QA-label-${SystemClock.uptimeMillis()}"
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, temporary) }
            assertTrue("Native note editing must accept text", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            click(waitForNode("Saving the note must remain enabled") { nodes().firstOrNull { it.text?.toString() == "保存备注" && it.isEnabled } })
            waitForNode("The native account card must display its new note") { nodes().firstOrNull { it.text?.toString() == temporary } }
            val after = store.accounts()
            val updated = after.firstOrNull { it.id == original.id }
            assertTrue("The local note must be stored", updated?.label == temporary)
            assertTrue("The account password must remain unchanged", updated?.password == original.password)
            assertTrue("The account identity must remain unchanged", updated?.username == original.username && updated?.id == original.id)
            assertTrue("All other saved accounts must remain unchanged", after.filterNot { it.id == original.id } == before.filterNot { it.id == original.id })
            assertTrue("Editing the note must retain the session", store.cookies(original.id) == cookies)
            assertTrue("Editing the note must retain sign-out preference", store.isSignedOut(original.id) == signedOut)
            assertTrue("Editing the note must retain selected account", store.activeAccountId == active)
            findScrolling("The account must remain logged in after the local edit") { nodes().firstOrNull { it.text?.toString() == "已登录" } }
        } finally {
            try {
                store.updateLabel(original.id, original.label)
                assertTrue("The original local note must be restored", store.accounts().firstOrNull { it.id == original.id }?.label == original.label)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == app && node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun noteField(): AccessibilityNodeInfo? {
        val all = nodes()
        val caption = all.firstOrNull { it.text?.toString()?.contains("备注名（可选）") == true || it.hintText?.toString() == "备注名（可选）" } ?: return null
        var node: AccessibilityNodeInfo? = caption
        repeat(6) {
            val current = node
            if (current?.isEditable == true && !current.isPassword) return current
            node = current?.parent
        }
        // Compose may merge the caption beside, rather than inside, the text
        // editor. The note is the bottom non-password editor in this verified form.
        return all.filter { it.isEditable && !it.isPassword && it.isEnabled }.maxByOrNull { candidate -> Rect().apply { candidate.getBoundsInScreen(this) }.bottom }
    }

    private fun waitForNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { find()?.let { return it }; SystemClock.sleep(200) }
        error(message)
    }

    private fun findScrolling(message: String, backward: Boolean = false, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(6) {
            find()?.let { return it }
            val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            nodes().firstOrNull { it.isScrollable }?.performAction(action)
            SystemClock.sleep(200)
        }
        return waitForNode(message, find)
    }

    private fun click(node: AccessibilityNodeInfo) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        assertTrue("Only account navigation, its editor or note-save control may be clicked", text in setOf("账号", "保存备注") || desc.startsWith("修改 "))
        assertTrue("The verified control must be native, visible and enabled", node.packageName?.toString() == app && node.isVisibleToUser && node.isEnabled)
        var candidate: AccessibilityNodeInfo? = node
        repeat(3) {
            val current = candidate
            val rect = Rect().apply { current?.getBoundsInScreen(this) }
            val compact = rect.height() <= instrumentation.targetContext.resources.displayMetrics.density * 80
            if (current?.isClickable == true && current.isEnabled && compact && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = current?.parent
        }
        val area = Rect().apply { node.getBoundsInScreen(this) }
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("The verified control must intersect the display", area.intersect(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)) && !area.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, area.exactCenterX(), area.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The verified native touch must succeed", automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}
