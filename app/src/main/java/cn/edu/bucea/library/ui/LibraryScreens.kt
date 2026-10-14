package cn.edu.bucea.library.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.AccountSummary
import cn.edu.bucea.library.AppThemeColor
import cn.edu.bucea.library.CampusAccess
import cn.edu.bucea.library.LibraryUiState
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ProjectUrl = "https://github.com/miofelix/bucea-library-seat"
private const val CommunityGroup = "1109482748"

@Composable
internal fun FeatureLoginPrompt(state: LibraryUiState, modifier: Modifier, onLogin: () -> Unit,
    onCheckCampus: () -> Unit, onAccounts: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LibraryPageHeader("欢迎来到图书馆", "为专注，找一个好位置。")
        CampusAccessNotice(state, onCheckCampus)
        LibraryEmptyState("登录后使用图书馆功能", "保存图书馆账号，即可选座和查看预约。", icon = Icons.Outlined.Person)
        Text(state.status, color = Muted, style = MaterialTheme.typography.bodySmall)
        Button(onClick = if (state.accounts.isEmpty()) onAccounts else onLogin,
            enabled = !state.busy && (state.accounts.isEmpty() || state.campusAccess == CampusAccess.REACHABLE),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            Text(if (state.accounts.isEmpty()) "添加账号" else "登录图书馆")
        }
    }
}

@Composable
internal fun Dashboard(state: LibraryUiState, hub: LibraryHubState, modifier: Modifier,
    onLogin: () -> Unit, onRefresh: () -> Unit, onOpenLibrary: () -> Unit,
    onReservations: () -> Unit, onAccounts: () -> Unit, onOpenMap: () -> Unit, onFavorites: () -> Unit) {
    val account = state.accounts.firstOrNull { it.id == state.activeAccountId }
    val name = state.overview?.displayName?.takeIf(String::isNotBlank)
        ?: account?.label?.takeIf(String::isNotBlank) ?: "同学"
    val greeting = when (LocalTime.now().hour) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..17 -> "下午好"; else -> "晚上好" }
    var showOverview by rememberSaveable { mutableStateOf(false) }
    var noticesExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA)), color = Accent,
                    fontSize = 10.sp, letterSpacing = 1.sp)
                Text(if (state.authenticated) "$greeting，$name" else "给专注，留一席。",
                    style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text("今天也给专注留一点时间。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            LibraryIconButton(Icons.Outlined.Person, "管理账号", onAccounts, enabled = !state.busy)
        }
        CampusAccessNotice(state, onRefresh)
        Surface(onClick = { when { state.authenticated -> onOpenLibrary(); account != null -> onLogin(); else -> onAccounts() } },
            enabled = !state.busy && (account == null || state.campusAccess == CampusAccess.REACHABLE),
            shape = RoundedCornerShape(20.dp), color = SoftAccent) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Outlined.AutoStories, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Text("北建大图书馆", fontSize = 11.sp, color = Accent)
                    }
                    Text(when { state.busy -> "正在连接图书馆…"; state.authenticated -> "找一个好座位";
                        account != null -> "登录并同步"; else -> "添加账号，开始使用" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(when { state.campusAccess == CampusAccess.UNREACHABLE -> "暂时无法访问图书馆";
                        state.campusAccess == CampusAccess.CHECKING -> "正在检测连接";
                        state.campusAccess == CampusAccess.UNKNOWN -> "请先连接校园网络";
                        state.overview?.available == null -> "同步后查看可用座位";
                        state.overviewStale -> "${state.overview.available} 席可用 · 上次数据";
                        else -> "${state.overview.available} 席可用" }, color = Muted, fontSize = 12.sp)
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(21.dp), color = Accent, strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Accent, modifier = Modifier.size(21.dp))
            }
        }
        if (state.authenticated && hub.currentStatus.isNotBlank() && hub.currentStatus != "NO_RESERVATION") {
            HubCurrentReservation(hub, onRefresh, onConfirmAction = { onReservations() }, onOpenDetails = onReservations)
        } else if (!state.authenticated && account != null) SessionCard(state, account, onLogin)
        if (state.authenticated) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeShortcut("布局选座", "按位置找", Icons.Outlined.Map, onOpenMap, Modifier.weight(1f), !state.busy)
                HomeShortcut("收藏座位", "你的偏好", Icons.Outlined.BookmarkBorder, onFavorites, Modifier.weight(1f), !state.busy && !hub.loading)
            }
            HomeShortcut("预约记录", "随时查看", Icons.Outlined.AutoStories, onReservations, Modifier.fillMaxWidth(), !state.busy && !hub.loading)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Wifi, null, tint = Muted, modifier = Modifier.size(15.dp))
            Text(when (state.campusAccess) {
                CampusAccess.REACHABLE -> "校园网络已连接"
                CampusAccess.UNREACHABLE -> "等待图书馆连接"
                CampusAccess.CHECKING -> "正在检测连接"
                CampusAccess.UNKNOWN -> "等待连接校园网络"
            },
                color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { showOverview = true }) { Text("馆内概况", fontSize = 11.sp) }
        }
        val notices = state.overview?.notices.orEmpty()
        if (notices.isNotEmpty()) {
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("馆内公告", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { noticesExpanded = !noticesExpanded }) { Text(if (noticesExpanded) "收起" else "查看", fontSize = 12.sp) }
            }
            if (noticesExpanded) notices.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, null, tint = Muted, modifier = Modifier.size(16.dp))
            Text("到馆后请连接图书馆内部 Wi-Fi 签到。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (showOverview) BusinessSelectionSheet(onDismissRequest = { showOverview = false }, title = { Text("馆内概况") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OverviewRow("空闲座位", state.overview?.available)
            OverviewRow("正在使用", state.overview?.inUse)
            OverviewRow("等待签到", state.overview?.awaitingSignIn)
            Text(if (state.overview == null) "同步后查看馆内情况。" else if (state.overviewStale) "上次同步的数据，刷新后查看最新情况。" else "已同步最新数据。", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = { Button(onClick = { showOverview = false; onRefresh() }, enabled = !state.busy,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新图书馆数据") } })
}

@Composable
private fun HomeShortcut(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = Color.White, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.heightIn(min = 68.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(SoftAccent), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OverviewRow(title: String, value: Int?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value?.toString() ?: "待同步", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionCard(state: LibraryUiState, account: AccountSummary, onLogin: () -> Unit) {
    LibraryCard {
        Text(account.label.ifBlank { account.username }, color = Ink, style = MaterialTheme.typography.titleMedium)
        Text(state.status, style = MaterialTheme.typography.bodySmall, color = Muted)
        Button(onClick = onLogin, enabled = !state.busy && state.campusAccess == CampusAccess.REACHABLE,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            if (state.busy) { CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (state.busy) "正在登录…" else "登录图书馆")
        }
    }
}

@Composable
internal fun AccountsScreen(state: LibraryUiState, modifier: Modifier, onSave: (String, String, String) -> Unit,
    onSelect: (String) -> Unit, onDelete: (String) -> Unit, onLogin: () -> Unit,
    onChangePassword: () -> Unit, onLogout: () -> Unit, onCheckCampus: () -> Unit,
    onRename: (String, String) -> Unit, message: String? = null) {
    var editing by remember { mutableStateOf<AccountSummary?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }
    val active = state.accounts.firstOrNull { it.id == state.activeAccountId }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LibraryPageHeader("账号管理", "你的登录信息，仅保存在本机。")
        CampusAccessNotice(state, onCheckCampus)
        if (active != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(active.label.ifBlank { active.username }, 52)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(active.label.ifBlank { "图书馆账号" }, style = MaterialTheme.typography.titleLarge, color = Ink)
                Text("${active.username} · ${if (state.authenticated) "已登录" else "未登录"}", color = Muted, fontSize = 12.sp)
            }
        }
        LibrarySectionTitle("已保存的账号", actionLabel = "添加", onAction = { editing = null; showEditor = true }, actionEnabled = !state.busy)
        if (state.accounts.isEmpty()) LibraryEmptyState("添加第一个账号", "保存图书馆账号，连接校园网后自动登录。", icon = Icons.Outlined.Person)
        state.accounts.forEach { account ->
            val selected = account.id == state.activeAccountId
            Surface(onClick = { onSelect(account.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp), color = if (selected) SoftAccent else Color.White,
                border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .20f) else Line)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(account.label.ifBlank { account.username }, 36)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.label.ifBlank { "图书馆账号" }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(account.username, color = Muted, fontSize = 12.sp)
                        if (selected) Text("当前使用", color = Accent, fontSize = 11.sp)
                    }
                    IconButton(onClick = { editing = account; showEditor = true }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Edit, "修改 ${account.username}", tint = Muted, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = { deleting = account }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "删除 ${account.username}", tint = Muted, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        Button(onClick = { editing = null; showEditor = true }, enabled = !state.busy, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("添加账号")
        }
        if (state.authenticated) SettingsGroup {
            SettingsEntry(Icons.Outlined.Password, "修改图书馆密码", "当前版本暂不支持", onClick = onChangePassword, enabled = !state.busy)
            HorizontalDivider(color = Line)
            SettingsEntry(Icons.Outlined.Logout, "退出登录", "账号仍会保存在本机", onClick = onLogout, enabled = !state.busy)
        } else if (active != null) SessionCard(state, active, onLogin)
        message?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Shield, null, tint = Muted, modifier = Modifier.size(17.dp))
            Text("账号与密码加密保存在本机，验证码在设备上识别。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (showEditor) AccountEditor(editing, state.busy, { showEditor = false }, onRename = { id, label -> onRename(id, label); showEditor = false },
        onSave = { username, password, label -> onSave(username, password, label); showEditor = false })
    deleting?.let { account -> BusinessAlertDialog(onDismissRequest = { deleting = null },
        title = { Text("删除保存的账号") }, text = { Text("将从此设备删除 ${account.username} 的登录信息。") },
        confirmButton = { Button(onClick = { onDelete(account.id); deleting = null }, enabled = !state.busy,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun Avatar(label: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(SoftAccent), contentAlignment = Alignment.Center) {
        Text(label.take(1), color = Accent, fontSize = (size / 2.5).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccountEditor(account: AccountSummary?, busy: Boolean, onDismiss: () -> Unit,
    onRename: (String, String) -> Unit, onSave: (String, String, String) -> Unit) {
    var username by remember(account?.id) { mutableStateOf(account?.username.orEmpty()) }
    var password by remember(account?.id) { mutableStateOf("") }
    var label by remember(account?.id) { mutableStateOf(account?.label.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    BusinessSelectionSheet(onDismissRequest = onDismiss, title = { Text(if (account == null) "添加图书馆账号" else "编辑账号") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (account == null) "使用与图书馆网站相同的账号和密码。" else "备注可直接保存；如需更新密码，请填写新密码。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(username, { username = it.trim() }, label = { Text("学号 / 账号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), readOnly = account != null, enabled = !busy,
                modifier = Modifier.fillMaxWidth().pauseUpdatesWhileFocused(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(password, { password = it }, label = { Text(if (account == null) "密码" else "密码（留空不修改）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (visible) "隐藏密码" else "显示密码") } },
                modifier = Modifier.fillMaxWidth().pauseUpdatesWhileFocused(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(label, { label = it }, label = { Text("备注名（可选）") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().pauseUpdatesWhileFocused(), shape = RoundedCornerShape(12.dp))
        }
    }, confirmButton = {
        Button(onClick = { if (account != null && password.isEmpty()) onRename(account.id, label.trim()) else onSave(username.trim(), password, label.trim()) },
            enabled = !busy && username.isNotBlank() && (account != null || password.isNotBlank()), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (account != null && password.isEmpty()) "保存备注" else "保存账号") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun SettingsScreen(state: LibraryUiState, modifier: Modifier, onAutoLogin: (Boolean) -> Unit,
    onAutoSwitch: (Boolean) -> Unit, onThemeColor: (AppThemeColor) -> Unit, updateContent: @Composable () -> Unit) {
    val context = LocalContext.current
    var showColors by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var updatesExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LibraryPageHeader("设置", "简单一点，刚刚好。")
        SettingsGroup {
            SettingsEntry(Icons.Outlined.Refresh, "应用更新", "检查并管理新版本", onClick = { updatesExpanded = !updatesExpanded })
            if (updatesExpanded) Box(Modifier.padding(vertical = 8.dp)) { updateContent() }
            HorizontalDivider(color = Line)
            SettingsEntry(Icons.Outlined.Palette, "主题颜色", state.themeColor.label, onClick = { showColors = true }, trailing = {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Accent))
            })
        }
        Column {
            LibrarySectionTitle("登录偏好")
            SettingsGroup {
                PreferenceRow("自动恢复登录", "下次打开时恢复会话", state.autoLogin, onAutoLogin)
                HorizontalDivider(color = Line)
                PreferenceRow("失败时自动切换账号", "使用下一个已保存账号", state.autoSwitch, onAutoSwitch)
            }
        }
        SettingsGroup {
            SettingsEntry(Icons.Outlined.Info, "关于这个客户端", "北建大图书馆选座", onClick = { showAbout = true })
            HorizontalDivider(color = Line)
            val uriHandler = LocalUriHandler.current
            SettingsEntry(Icons.Outlined.Edit, "问题与建议", "分享你的使用体验", onClick = { uriHandler.openUri("$ProjectUrl/issues") })
            HorizontalDivider(color = Line)
            SettingsEntry(Icons.Outlined.ContentCopy, "QQ 交流群：$CommunityGroup", "点击复制 QQ 群号", onClick = { copyCommunity(context) })
        }
        Text("BUCEA LIBRARY · 非官方客户端", color = Muted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    if (showColors) BusinessSelectionSheet(onDismissRequest = { showColors = false }, title = { Text("主题颜色") },
        text = { ThemeColorPicker(state.themeColor, onThemeColor) },
        confirmButton = { Button(onClick = { showColors = false }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") } })
    if (showAbout) AboutSheet { showAbout = false }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White,
        border = BorderStroke(1.dp, Line)) { Column(Modifier.padding(horizontal = 16.dp)) { content() } }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, detail: String, onClick: () -> Unit,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 76.dp).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleSmall)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PreferenceRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleSmall)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    BusinessSelectionSheet(onDismissRequest = onDismiss, title = { Text("关于这个客户端") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("为建大同学准备的图书馆入口", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text("更方便地选座、查看地图和管理预约。此应用为第三方客户端。", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("开发作者：樊彧", color = Ink, fontSize = 14.sp)
            Text("欢迎分享使用体验、反馈问题，也欢迎安利给同学。", style = MaterialTheme.typography.bodySmall, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { uriHandler.openUri(ProjectUrl) }) { Text("GitHub 项目") }
                TextButton(onClick = { uriHandler.openUri("$ProjectUrl/issues") }) { Text("提交问题") }
            }
            HorizontalDivider(color = Line)
            Text("QQ 交流群：$CommunityGroup", color = Ink, fontSize = 14.sp)
            TextButton(onClick = { copyCommunity(context) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("复制 QQ 群号") }
            Text("验证码识别 · ddddocr / ONNX", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

private fun copyCommunity(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("北建大图书馆选座 QQ 交流群", CommunityGroup))
    Toast.makeText(context, "已复制 QQ 群号：$CommunityGroup", Toast.LENGTH_SHORT).show()
}

@Composable
private fun CampusAccessNotice(state: LibraryUiState, onCheckCampus: () -> Unit) {
    if (state.campusAccess == CampusAccess.REACHABLE) return
    val context = LocalContext.current
    val unavailable = state.campusAccess == CampusAccess.UNREACHABLE
    val checking = state.campusAccess == CampusAccess.CHECKING
    Surface(modifier = Modifier.fillMaxWidth().semantics { liveRegion = if (unavailable) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        color = if (unavailable) MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f) else SoftAccent,
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.Wifi, null, tint = if (unavailable) MaterialTheme.colorScheme.error else Accent, modifier = Modifier.size(20.dp))
                Text(when { unavailable -> "暂时无法访问图书馆"; checking -> "正在检测校园网"; else -> "登录需要校园网" },
                    color = Ink, style = MaterialTheme.typography.titleSmall)
            }
            Text(if (unavailable) "校园网络或图书馆服务可能暂时无法连通。请重新检测连接，或稍后重试。"
                else "请连接可访问图书馆的校园 Wi-Fi，再检测连接。", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (checking) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                Text("正在检查连接…", color = Muted, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("连接校园 Wi-Fi", fontSize = 12.sp) }
                TextButton(onClick = onCheckCampus, enabled = !state.busy && !checking) { Text("重新检测连接", fontSize = 12.sp) }
            }
        }
    }
}
