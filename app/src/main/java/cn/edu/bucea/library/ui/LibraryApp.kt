package cn.edu.bucea.library.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.bucea.library.AccountSummary
import cn.edu.bucea.library.LibraryUiState
import cn.edu.bucea.library.MainViewModel

private val Ink = Color(0xFF183334)
private val Muted = Color(0xFF778583)
private val Teal = Color(0xFF28766C)
private val Paper = Color(0xFFF6F7F2)
private val SoftGreen = Color(0xFFE5EFE8)
private val Line = Color(0xFFE2E8E1)
private val Warm = Color(0xFFD4B87D)

@Composable
fun LibraryApp(viewModel: MainViewModel, onOpenSeats: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val destinations = listOf("首页" to Icons.Outlined.Home, "账号" to Icons.Outlined.Person, "设置" to Icons.Outlined.Settings)

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal,
            onPrimary = Color.White,
            secondary = Ink,
            background = Paper,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink,
            surfaceVariant = SoftGreen,
            onSurfaceVariant = Muted,
            outline = Line
        ),
        typography = Typography()
    ) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    destinations.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp)) },
                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal,
                                selectedTextColor = Teal,
                                indicatorColor = SoftGreen,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> Dashboard(
                    state = state,
                    modifier = Modifier.padding(padding),
                    onLogin = viewModel::login,
                    onRefresh = viewModel::refresh,
                    onOpenLibrary = onOpenSeats,
                    onAccounts = { tab = 1 }
                )
                1 -> AccountsScreen(
                    state = state,
                    modifier = Modifier.padding(padding),
                    onSave = viewModel::saveAccount,
                    onSelect = viewModel::selectAccount,
                    onDelete = viewModel::deleteAccount,
                    onLogin = viewModel::login
                )
                else -> SettingsScreen(
                    state = state,
                    modifier = Modifier.padding(padding),
                    onAutoLogin = viewModel::setAutoLogin,
                    onAutoSwitch = viewModel::setAutoSwitch
                )
            }
        }
        state.captcha?.let { bytes ->
            CaptchaDialog(bytes, state.busy, state.status, viewModel::submitCaptcha, viewModel::dismissCaptcha)
        }
    }
}

@Composable
private fun Dashboard(
    state: LibraryUiState,
    modifier: Modifier,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLibrary: () -> Unit,
    onAccounts: () -> Unit
) {
    val account = state.accounts.firstOrNull { it.id == state.activeAccountId }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("建大 · 图书馆", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("BUCEA LIBRARY", fontSize = 9.sp, letterSpacing = 1.8.sp, color = Muted)
            }
            IconButton(onClick = onRefresh, enabled = !state.busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新图书馆数据", tint = if (state.busy) Muted else Teal)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Teal))
                Text("一席之间，专注当下", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text("开始今天的\n专注时光。", fontSize = 33.sp, lineHeight = 44.sp, color = Ink, fontWeight = FontWeight.Bold)
            Text("${state.overview?.displayName?.takeIf { it.isNotBlank() } ?: account?.label?.takeIf { it.isNotBlank() } ?: "同学"}，欢迎来到图书馆", color = Muted, fontSize = 14.sp)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = CardDefaults.cardColors(containerColor = Ink)
        ) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Ink, Color(0xFF235049)))).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("空闲座位", fontSize = 14.sp, color = Color.White.copy(alpha = 0.82f), modifier = Modifier.weight(1f))
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                        Text(when { state.busy -> "更新中"; state.overview?.available != null -> "已同步"; else -> "待同步" }, color = Color.White.copy(alpha = 0.86f), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(state.overview?.available?.toString() ?: "—", fontSize = 60.sp, lineHeight = 62.sp, color = Color.White, fontWeight = FontWeight.Light)
                    Text("席", fontSize = 14.sp, color = Color.White.copy(alpha = 0.56f), modifier = Modifier.padding(bottom = 9.dp))
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
                Row(Modifier.fillMaxWidth()) {
                    StatItem("正在使用", state.overview?.inUse, Modifier.weight(1f))
                    StatItem("等待签到", state.overview?.awaitingSignIn, Modifier.weight(1f))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    when {
                        state.authenticated -> onOpenLibrary()
                        account != null -> onLogin()
                        else -> onAccounts()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(if (state.authenticated) Icons.Outlined.GridView else Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(when { state.busy -> "正在连接图书馆…"; state.authenticated -> "进入图书馆"; account != null -> "登录并同步"; else -> "添加账号，开始使用" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!state.busy) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
                }
            }
            Text("完整功能中心 · 在手机上使用网站的全部功能", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        SessionCard(state, account, onAccounts, onLogin)

        state.overview?.reservationText?.takeIf { it.isNotBlank() }?.let { reservation ->
            ContentCard("我的预约", Icons.Outlined.AutoStories) { Text(reservation, fontSize = 14.sp, lineHeight = 22.sp, color = Ink) }
        }
        state.overview?.notices?.takeIf { it.isNotEmpty() }?.let { notices ->
            ContentCard("馆内公告", Icons.Outlined.Info) {
                notices.take(5).forEachIndexed { index, notice ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
                    Text(notice, fontSize = 13.sp, lineHeight = 21.sp, color = Ink)
                }
            }
        }
        CampusNotice()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatItem(label: String, value: Int?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value?.toString() ?: "—", fontSize = 21.sp, fontWeight = FontWeight.Medium, color = Warm)
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.58f))
    }
}

@Composable
private fun SessionCard(state: LibraryUiState, account: AccountSummary?, onAccounts: (() -> Unit)?, onLogin: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.busy) CircularProgressIndicator(Modifier.size(19.dp), color = Teal, strokeWidth = 2.dp)
                else Icon(if (state.authenticated) Icons.Outlined.CheckCircle else Icons.Outlined.Person, contentDescription = null, tint = Teal, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text(if (state.authenticated) "已登录" else "登录状态", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onAccounts != null) {
                    TextButton(onClick = onAccounts, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.height(28.dp)) {
                        Text(if (account == null) "管理账号" else "切换账号", fontSize = 11.sp)
                    }
                }
            }
            if (account != null) {
                Text(account.label.takeIf { it.isNotBlank() } ?: account.username, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(state.status.ifBlank { "添加账号后，可自动识别验证码并登录。" }, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            if (!state.authenticated && !state.busy && account != null) {
                TextButton(onClick = onLogin, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.height(28.dp)) {
                    Text("重新登录", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AccountsScreen(
    state: LibraryUiState,
    modifier: Modifier,
    onSave: (String, String, String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onLogin: () -> Unit
) {
    var editing by remember { mutableStateOf<AccountSummary?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ScreenHeading("账号管理", "一次保存，下次直接开始。", Icons.Outlined.Person)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已保存的账号", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${state.accounts.size} 个", color = Muted, fontSize = 12.sp)
        }
        if (state.accounts.isEmpty()) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.Person, 58)
                    Text("添加第一个账号", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("保存图书馆账号后，即可自动识别\n验证码并恢复登录。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        state.accounts.forEach { account ->
            val selected = account.id == state.activeAccountId
            Card(
                shape = RoundedCornerShape(19.dp),
                colors = CardDefaults.cardColors(containerColor = if (selected) SoftGreen else Color.White),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { onSelect(account.id) }
            ) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (selected) Teal else Paper), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Person, contentDescription = null, tint = if (selected) Color.White else Muted, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.label.takeIf { it.isNotBlank() } ?: "图书馆账号", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(account.username, color = Muted, fontSize = 12.sp)
                        }
                        if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = "当前账号", tint = Teal, modifier = Modifier.size(21.dp))
                    }
                    HorizontalDivider(color = if (selected) Teal.copy(alpha = 0.12f) else Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected) "当前使用" else "点击切换并登录", color = if (selected) Teal else Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = account; showEditor = true }, enabled = !state.busy, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Edit, contentDescription = "修改 ${account.username}", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deleting = account }, enabled = !state.busy, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除 ${account.username}", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Button(
            onClick = { editing = null; showEditor = true },
            enabled = !state.busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加账号", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.accounts.isNotEmpty()) SessionCard(state, state.accounts.firstOrNull { it.id == state.activeAccountId }, null, onLogin)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = Muted, modifier = Modifier.size(17.dp))
            Text("账号与密码加密保存在本机。验证码识别在设备上完成。", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
        }
        CampusNotice()
    }
    if (showEditor) {
        AccountDialog(editing, onDismiss = { showEditor = false }, onSave = { username, password, label ->
            onSave(username, password, label)
            showEditor = false
        })
    }
    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除保存的账号") },
            text = { Text("将从此设备删除 ${account.username} 的登录信息。") },
            confirmButton = { TextButton(onClick = { onDelete(account.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun AccountDialog(account: AccountSummary?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var username by remember(account?.id) { mutableStateOf(account?.username.orEmpty()) }
    var password by remember(account?.id) { mutableStateOf("") }
    var label by remember(account?.id) { mutableStateOf(account?.label.orEmpty()) }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
        title = { Text(if (account == null) "添加图书馆账号" else "修改登录信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (account == null) "使用与图书馆网站相同的账号和密码。" else "请重新输入密码，保存后更新此账号。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                OutlinedTextField(username, { username = it.trim() }, label = { Text("学号 / 账号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), readOnly = account != null)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "隐藏密码" else "显示密码") } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(label, { label = it }, label = { Text("备注名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(username.trim(), password, label.trim()) }, enabled = username.isNotBlank() && password.isNotBlank()) { Text("保存账号") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsScreen(state: LibraryUiState, modifier: Modifier, onAutoLogin: (Boolean) -> Unit, onAutoSwitch: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenHeading("让登录更简单", "少一些操作，多一些专注。", Icons.Outlined.Settings)
        ContentCard("登录偏好", Icons.Outlined.AutoAwesome) {
            PreferenceRow(Icons.Outlined.Lock, "自动恢复登录", "启动时恢复会话，过期后自动识别验证码并登录。", state.autoLogin, onAutoLogin)
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
            PreferenceRow(Icons.Outlined.SwapHoriz, "失败时自动切换账号", "当前账号无法登录时，依次尝试其他已保存的账号。", state.autoSwitch, onAutoSwitch)
        }
        CampusNotice()
        ContentCard("关于这个客户端", Icons.Outlined.AutoStories) {
            Text("为建大同学准备的图书馆入口", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text("原生账号管理与自动登录，配合适配手机的完整网站功能。此应用为第三方客户端。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("验证码识别 · ddddocr / ONNX", color = Teal, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreferenceRow(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.padding(top = 2.dp).size(19.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, lineHeight = 19.sp, color = Muted)
        }
        Switch(checked, onChange, modifier = Modifier.height(28.dp), colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Teal))
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String, icon: ImageVector) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, 46)
        Spacer(Modifier.height(2.dp))
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun IconTile(icon: ImageVector, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(SoftGreen), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun ContentCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(19.dp))
            content()
        }
    }
}

@Composable
private fun CampusNotice() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftGreen.copy(alpha = 0.65f)).padding(15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Wifi, contentDescription = null, tint = Teal, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("仅在校园内网使用", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text("请连接能访问 10.1.20.7 的校园网络。", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun CaptchaDialog(bytes: ByteArray, busy: Boolean, status: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var answer by remember(bytes) { mutableStateOf("") }
    val image = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("请确认验证码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(status.ifBlank { "自动识别未通过，请输入图片中的字符继续登录。" }, fontSize = 13.sp, color = Muted, lineHeight = 21.sp)
                if (image != null) {
                    Surface(color = Paper, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Image(image, contentDescription = "登录验证码", modifier = Modifier.height(90.dp).padding(12.dp))
                    }
                } else Text("验证码图片无法显示，请取消后重新登录。", fontSize = 12.sp)
                OutlinedTextField(answer, { answer = it.trim() }, label = { Text("验证码") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(answer) }, enabled = answer.isNotBlank() && !busy) { Text(if (busy) "正在验证…" else "继续登录") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}
