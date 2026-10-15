package cn.edu.bucea.library

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.AccountsScreen
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Native presentation fixtures only: every account callback is a local recorder.
 * MainActivity may make its usual startup reads before its content is replaced;
 * these fixtures never call an account vault, repository or school mutation API.
 */
@RunWith(AndroidJUnit4::class)
class WhitespaceUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName
    private val primaryAccount = AccountSummary("fixture-whitespace-primary", "209999990901", "演示账号一")
    private val secondaryAccount = AccountSummary("fixture-whitespace-secondary", "209999990902", "演示账号二")
    private val focusTrace = mutableListOf<String>()

    @Test fun busyAccountsDisableEveryAddEditAndDeleteEntryUntilTheOperationFinishes() {
        val callbacks = AccountCallbacks()
        val state = mutableStateOf(accountState(busy = true))
        withAccounts("busy-entrypoints", state, callbacks) {
            val disabledEntries = listOf(
                "header add" to { findText("添加") },
                "primary account edit" to { findDescription("修改 ${primaryAccount.username}") },
                "primary account delete" to { findDescription("删除 ${primaryAccount.username}") },
                "secondary account edit" to { findDescription("修改 ${secondaryAccount.username}") },
                "secondary account delete" to { findDescription("删除 ${secondaryAccount.username}") },
                "full-width add" to { findText("添加账号") },
            )
            disabledEntries.forEach { (description, find) ->
                val node = findWithScroll("The busy $description entry must remain discoverable", find = find)
                assertFalse("The busy $description entry must be disabled", effectivelyEnabled(node))
                tap(node)
                instrumentation.waitForIdleSync()
                assertFalse("The busy $description entry must not open the account editor", hasText("添加图书馆账号") || hasText("编辑账号"))
                assertFalse("The busy $description entry must not open deletion", hasText("删除保存的账号"))
            }
            assertEquals("Busy entries must not submit credentials", 0, callbacks.saves.get())
            assertEquals("Busy entries must not rename an account", 0, callbacks.renames.get())
            assertEquals("Busy entries must not delete an account", 0, callbacks.deletes.get())
            assertEquals("Disabled account controls must not switch accounts", 0, callbacks.selections.get())

            instrumentation.runOnMainSync { state.value = state.value.copy(busy = false) }
            findWithScroll("The header Add action must remain discoverable", backwards = true) { findText("添加") }
            val headerAdd = requireNode("The recomposed idle header Add action must become enabled") {
                findText("添加")?.takeIf(::effectivelyEnabled)
            }
            assertTrue("The idle header Add action must be enabled", effectivelyEnabled(headerAdd))
            click(headerAdd)
            waitFor("Header Add must open the native account sheet") { hasText("添加图书馆账号") }
            assertEquals("Opening an account editor must not save anything", 0, callbacks.saves.get())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back must dismiss the new-account sheet without saving") { !hasText("添加图书馆账号") }
            assertEquals(0, callbacks.saves.get())
            assertEquals(0, callbacks.renames.get())
        }
    }

    @Test fun remarkEditingKeepsSaveAboveTheKeyboardAndNeverSubmitsCredentialsOrASuspendedDraft() {
        val callbacks = AccountCallbacks()
        val state = mutableStateOf(accountState())
        withAccounts("remark-keyboard", state, callbacks) {
            openPrimaryEditor()
            assertFalse("An unchanged, empty password must not offer credential submission", hasText("保存账号"))
            focusNote()
            waitFor("Focusing the account note must show the soft keyboard") { imeBounds() != null }
            val newLabel = "留白备注测试"
            setNote(newLabel)

            waitFor("The note-save action must remain enabled and fully above the keyboard") {
                val save = findText("保存备注") ?: return@waitFor false
                val keyboard = imeBounds() ?: return@waitFor false
                val button = bounds(clickableParent(save) ?: save)
                effectivelyEnabled(save) && !button.isEmpty && button.bottom <= keyboard.top
            }
            val save = requireNode("The note-save footer must remain visible while typing") { findText("保存备注") }
            // A real touch verifies that the keyboard does not cover the footer.
            tap(clickableParent(save) ?: save)
            waitFor("Saving a note must invoke only the synthetic rename callback once") {
                callbacks.renames.get() == 1 && !hasText("编辑账号")
            }
            assertEquals(primaryAccount.id to newLabel, callbacks.lastRename.get())
            assertEquals("Note-only editing must never submit credentials", 0, callbacks.saves.get())
            assertEquals("Note editing must not switch or delete an account", 0, callbacks.selections.get() + callbacks.deletes.get())

            openPrimaryEditor()
            setNote("不保存的草稿")
            if (imeBounds() != null) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor("The first Back must hide the editor keyboard") { imeBounds() == null }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back must dismiss the existing-account sheet") { !hasText("编辑账号") }
            assertEquals("Dismissing an edited draft must not rename again", 1, callbacks.renames.get())
            assertEquals("Dismissing an edited draft must not submit credentials", 0, callbacks.saves.get())
            assertEquals(primaryAccount.id to newLabel, callbacks.lastRename.get())
        }
    }

    @Test fun restoringAnEditorKeepsItsAccountAndNoteWithoutSavingCredentials() {
        val callbacks = AccountCallbacks()
        val state = mutableStateOf(accountState())
        val restoration = FixtureRestoration()
        withAccounts("editor-restoration", state, callbacks, restoration = restoration) {
            openPrimaryEditor()
            val password = "fixture-password-never-save-903"
            val passwordField = findWithScroll("The synthetic password input must be reachable") {
                nodes().firstOrNull { it.isEditable && it.isPassword }
            }
            setField(passwordField, password)
            val note = "恢复后继续编辑的备注"
            setNote(note)
            val saved = restoration.saveAndRestore()
            assertFalse("Saved UI state must not contain a plaintext password", saved.toString().contains(password))
            assertFalse("Saved UI state must not contain the account username", saved.toString().contains(primaryAccount.username))
            waitFor("Restoring must keep editing the existing account") { hasText("编辑账号") && !hasText("添加图书馆账号") }
            waitFor("Only the non-sensitive note draft must be restored") { noteField()?.text?.toString() == note }
            val restoredPassword = findWithScroll("The restored password field must remain available", backwards = true) {
                nodes().firstOrNull { it.isEditable && it.isPassword }
            }
            assertTrue("Recreated password input must be empty", restoredPassword.text.isNullOrEmpty())
            click(findWithScroll("The restored editor must offer note-only saving") { findText("保存备注") })
            waitFor("Saving after restoration must rename the original account") { callbacks.renames.get() == 1 }
            assertEquals(primaryAccount.id to note, callbacks.lastRename.get())
            assertEquals("Restoration must not turn a note edit into credential submission", 0, callbacks.saves.get())
            assertEquals(0, callbacks.selections.get() + callbacks.deletes.get())
        }
    }

    @Test fun narrowLargeTextAccountsKeepReadableIdentityAndSeparateTouchTargets() {
        val callbacks = AccountCallbacks()
        val state = mutableStateOf(accountState())
        withAccounts("narrow-large-text", state, callbacks, compact = true) {
            val edit = findWithScroll("Narrow cards must retain account editing") {
                findDescription("修改 ${primaryAccount.username}")
            }
            val delete = requireNode("Narrow cards must retain account deletion beside editing") {
                findDescription("删除 ${primaryAccount.username}")
            }
            val username = requireNode("The complete account username must be readable") {
                nodes().firstOrNull { it.text?.toString() == primaryAccount.username }
            }
            val label = requireNode("The account note must remain readable") { findText(primaryAccount.label) }
            val editBounds = bounds(clickableParent(edit) ?: edit)
            val deleteBounds = bounds(clickableParent(delete) ?: delete)
            val minimumTouch = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("Editing must keep its 48 dp touch height", editBounds.height() >= minimumTouch)
            assertTrue("Deletion must keep its 48 dp touch height", deleteBounds.height() >= minimumTouch)
            assertFalse("Editing and deletion touch areas must not overlap", Rect.intersects(editBounds, deleteBounds))
            assertTrue("Narrow cards must move actions below the account identity", editBounds.top >= bounds(username).bottom)
            assertTrue("The account label must occupy its own visible line", bounds(label).bottom <= bounds(username).top)
            click(edit)
            waitFor("The independent edit control must open the existing account") { hasText("编辑账号") }
            assertEquals("Editing must not select or delete the account", 0, callbacks.selections.get() + callbacks.deletes.get())
        }
    }

    @Test fun updateDisclosureAnnouncesAndRestoresItsExpandedState() {
        val restoration = FixtureRestoration()
        withFixture("update-disclosure", restoration = restoration, content = {
            SettingsScreen(accountState(), Modifier, {}, {}, {}, updateContent = {
                androidx.compose.material3.Text("合成更新面板")
            })
        }) {
            val update = requireNode("Update disclosure must be present") { findText("应用更新") }
            if (Build.VERSION.SDK_INT >= 30) assertEquals("已收起", clickableParent(update)?.stateDescription?.toString())
            click(update)
            waitFor("Expanding updates must expose its content") { hasText("合成更新面板") }
            if (Build.VERSION.SDK_INT >= 30) {
                val expanded = requireNode("Expanded updates must expose a state description") {
                    findText("应用更新")?.let(::clickableParent)?.takeIf { it.stateDescription?.toString() == "已展开" }
                }
                assertEquals("已展开", expanded.stateDescription?.toString())
            }
            restoration.saveAndRestore()
            waitFor("Restoring settings must preserve the open update panel") { hasText("合成更新面板") }
            click(requireNode("Restored updates must still be collapsible") { findText("应用更新") })
            waitFor("Collapsing updates must hide their content") { !hasText("合成更新面板") }
        }
    }

    private fun accountState(busy: Boolean = false) = LibraryUiState(
        accounts = listOf(primaryAccount, secondaryAccount),
        activeAccountId = primaryAccount.id,
        authenticated = true,
        campusAccess = CampusAccess.REACHABLE,
        busy = busy,
        status = "本地界面测试",
    )

    private class AccountCallbacks {
        val saves = AtomicInteger()
        val renames = AtomicInteger()
        val selections = AtomicInteger()
        val deletes = AtomicInteger()
        val lastRename = AtomicReference<Pair<String, String>?>()
    }

    private class FixtureRestoration {
        val visible = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))

        fun saveAndRestore(): Map<String, List<Any?>> {
            var saved: Map<String, List<Any?>> = emptyMap()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                saved = registry.value.performSave()
                visible.value = false
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                registry.value = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
                visible.value = true
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return saved
        }
    }

    private fun withAccounts(caseName: String, state: MutableState<LibraryUiState>, callbacks: AccountCallbacks,
        restoration: FixtureRestoration? = null, compact: Boolean = false, assertions: () -> Unit) {
        withFixture(caseName, restoration, compact, content = {
            AccountsScreen(
                state = state.value,
                modifier = Modifier,
                onSave = { _, _, _ -> callbacks.saves.incrementAndGet() },
                onSelect = { callbacks.selections.incrementAndGet() },
                onDelete = { callbacks.deletes.incrementAndGet() },
                onLogin = {}, onChangePassword = {}, onLogout = {}, onCheckCampus = {},
                onRename = { id, label -> callbacks.lastRename.set(id to label); callbacks.renames.incrementAndGet() },
            )
        }, assertions = assertions)
    }

    private fun withFixture(caseName: String, restoration: FixtureRestoration? = null, compact: Boolean = false,
        content: @Composable () -> Unit, assertions: () -> Unit) {
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.BUCEA_PURPLE) {
                        val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                            if (compact) { screenWidthDp = 320; fontScale = 1.6f }
                        }
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalConfiguration provides configuration,
                            LocalDensity provides if (compact) Density(density.density, 1.6f) else density) {
                            Box(if (compact) Modifier.width(320.dp) else Modifier) {
                                if (restoration == null) content() else if (restoration.visible.value) {
                                    CompositionLocalProvider(LocalSaveableStateRegistry provides restoration.registry.value) { content() }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("The synthetic screen must be in the foreground") { hasText("账号管理") || hasText("设置") }
            assertions()
        } catch (failure: Throwable) {
            try { captureFailureArtifacts(caseName, failure) }
            catch (artifactFailure: Throwable) { failure.addSuppressed(artifactFailure) }
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun openPrimaryEditor() {
        click(findWithScroll("The synthetic account must expose its editor", backwards = true) {
            findDescription("修改 ${primaryAccount.username}")
        })
        waitFor("Editing a saved account must open its native sheet") { hasText("编辑账号") }
    }

    private fun setNote(value: String) {
        val note = findWithScroll("The note editor must be reachable without entering a password") { noteField() }
        assertFalse("The fixture must edit only a non-password field", note.isPassword)
        setField(note, value)
        waitFor("The edited note must appear in the native field") { noteField()?.text?.toString() == value }
    }

    private fun setField(field: AccessibilityNodeInfo, value: String) {
        val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("The synthetic editor must accept text", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
        instrumentation.waitForIdleSync()
    }

    private fun focusNote() {
        val initial = findWithScroll("The account note must be editable") { noteField() }
        assertTrue("Only the real editable note may be focused", initial.isEditable && !initial.isPassword)
        focusTrace += "before bring-into-view: ${describeNode(initial)}"
        val shown = initial.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        focusTrace += "ACTION_SHOW_ON_SCREEN=$shown"

        // Bring-into-view may scroll the sheet asynchronously. Use a fresh editor
        // node after its visible bounds settle, rather than a clipped label node.
        var previous: Rect? = null
        var stableSamples = 0
        val field = requireNode("The note editor must be visibly settled inside its scroll viewport") {
            val candidate = noteField() ?: return@requireNode null
            val area = visibleBounds(candidate)
            if (!area.isEmpty && area == previous) stableSamples++ else stableSamples = 0
            previous = Rect(area)
            candidate.takeIf { it.isEditable && !it.isPassword && area.height() >= 32 * instrumentation.targetContext.resources.displayMetrics.density && stableSamples >= 2 }
        }
        focusTrace += "before touch: ${describeNode(field)}; visible=${visibleBounds(field)}"
        tap(field)
        SystemClock.sleep(200)
        var fresh = requireNode("The note editor must remain present after touch") { noteField() }
        focusTrace += "after touch: ${describeNode(fresh)}; ime=${imeBounds()}"
        if (!fresh.isFocused) {
            val focused = fresh.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focusTrace += "ACTION_FOCUS=$focused"
        }
        // Compose's editable onClick also requests the IME. This is the same
        // native field action used by accessibility users, without forcing an IME.
        fresh = requireNode("The note editor must be available for its native click action") { noteField() }
        val clicked = fresh.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        focusTrace += "editable ACTION_CLICK=$clicked"
        waitFor("The actual note editor must receive input focus") { noteField()?.isFocused == true }
        focusTrace += "after field actions: ${noteField()?.let(::describeNode)}; ime=${imeBounds()}"
    }

    private fun noteField(): AccessibilityNodeInfo? {
        val all = nodes()
        val caption = all.firstOrNull { node ->
            node.text?.toString()?.contains("备注名（可选）") == true || node.hintText?.toString() == "备注名（可选）"
        } ?: return null
        var current: AccessibilityNodeInfo? = caption
        repeat(6) {
            current?.let { if (it.isEditable && !it.isPassword) return it }
            current = current?.parent
        }
        return all.filter { it.isEditable && !it.isPassword && it.isEnabled }
            .maxByOrNull { bounds(it).bottom }
    }

    private fun nodes(visibleOnly: Boolean = true): List<AccessibilityNodeInfo> {
        // Some Android builds retain old Compose text/enabled nodes after a visual update.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && (!visibleOnly || node.isVisibleToUser)) found += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        val roots = automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == appPackage }
        if (roots.isEmpty()) automation.rootInActiveWindow?.let(::visit) else roots.forEach(::visit)
        return found
    }

    private fun findText(value: String) = nodes().firstOrNull { node -> node.text?.toString().orEmpty().lines().any { it.trim() == value } }
    private fun findDescription(value: String) = nodes().firstOrNull { it.contentDescription?.toString() == value }
    private fun hasText(value: String) = findText(value) != null
    private fun bounds(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }

    private fun visibleBounds(node: AccessibilityNodeInfo): Rect {
        val area = bounds(node)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        if (!area.intersect(Rect(0, 0, metrics.widthPixels, metrics.heightPixels))) return Rect()
        var ancestor = node.parent
        repeat(12) {
            val parent = ancestor ?: return area
            if (parent.isScrollable) {
                val viewport = bounds(parent)
                if (!viewport.isEmpty && !area.intersect(viewport)) return Rect()
            }
            ancestor = parent.parent
        }
        return area
    }

    private fun imeBounds(): Rect? = automation.windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().apply { it.getBoundsInScreen(this) } }
        ?.takeUnless { it.isEmpty }

    private fun effectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            current?.let { if (!it.isEnabled) return false }
            current = current?.parent
        }
        return true
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue("The synthetic control must be enabled", effectivelyEnabled(node))
        val clickable = clickableParent(node)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        tap(node)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        assertEquals("Only the synthetic app content may receive touches", appPackage, node.packageName?.toString())
        val area = visibleBounds(node)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("The synthetic target must intersect the display", area.intersect(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)) && !area.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, area.exactCenterX(), area.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The fixture touch must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String = buildString {
        append("class=${node.className} text=")
        append(if (node.isPassword) "<redacted>" else node.text?.toString()?.replace('\n', '|'))
        append(" hint=${node.hintText} description=${node.contentDescription}")
        append(" editable=${node.isEditable} focused=${node.isFocused} accessibilityFocused=${node.isAccessibilityFocused}")
        append(" enabled=${node.isEnabled} visible=${node.isVisibleToUser} clickable=${node.isClickable} scrollable=${node.isScrollable}")
        append(" window=${node.windowId} bounds=${bounds(node)} visibleBounds=${visibleBounds(node)}")
        append(" actions=${node.actionList.joinToString { it.id.toString() }}")
    }

    private fun captureFailureArtifacts(caseName: String, failure: Throwable) {
        val prefix = "whitespace-account-failure-$caseName"
        val directory = instrumentation.targetContext.filesDir
        val metadata = buildString {
            appendLine("Synthetic account fixture: $caseName")
            appendLine("Failure: ${failure.javaClass.name}: ${failure.message}")
            appendLine("Focus trace:")
            focusTrace.forEach { appendLine(it) }
            appendLine("Windows:")
            automation.windows.forEach { window ->
                val area = Rect().apply { window.getBoundsInScreen(this) }
                appendLine("id=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused} bounds=$area")
            }
            appendLine("Synthetic application nodes (password text redacted):")
            nodes(visibleOnly = false).forEach { appendLine(describeNode(it)) }
        }
        File(directory, "$prefix.txt").writeText(metadata)
        automation.takeScreenshot()?.let { screenshot ->
            try {
                File(directory, "$prefix.png").outputStream().use { output ->
                    assertTrue("The failure screenshot must be written", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { screenshot.recycle() }
        }
    }

    private fun findWithScroll(message: String, backwards: Boolean = false, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(10) {
            find()?.let { return it }
            nodes().firstOrNull { it.isScrollable && it.isEnabled }?.performAction(
                if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            SystemClock.sleep(150)
        }
        return requireNode(message, find)
    }

    private fun requireNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(message) { result = find(); result != null }
        return requireNotNull(result)
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
}
