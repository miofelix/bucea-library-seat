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
import androidx.compose.runtime.LaunchedEffect
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
import cn.edu.bucea.library.LibraryFeatureViewModel
import cn.edu.bucea.library.NativeMapViewModel
import cn.edu.bucea.library.NativeAccountViewModel
import cn.edu.bucea.library.NativeVerificationViewModel
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Password
import androidx.compose.foundation.layout.consumeWindowInsets

private val Ink = Color(0xFF183334)
private val Muted = Color(0xFF778583)
private val Teal = Color(0xFF28766C)
private val Paper = Color(0xFFF6F7F2)
private val SoftGreen = Color(0xFFE5EFE8)
private val Line = Color(0xFFE2E8E1)
private val Warm = Color(0xFFD4B87D)

@Composable
fun LibraryApp(viewModel: MainViewModel, features: LibraryFeatureViewModel, maps: NativeMapViewModel,
    nativeAccount: NativeAccountViewModel, verification: NativeVerificationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val seatState by features.search.collectAsStateWithLifecycle()
    val hubState by features.hub.collectAsStateWithLifecycle()
    val extensionOptions by features.extensionTimes.collectAsStateWithLifecycle()
    val mutating by features.mutationBusy.collectAsStateWithLifecycle()
    val mapState by maps.mapState.collectAsStateWithLifecycle()
    val accountState by nativeAccount.state.collectAsStateWithLifecycle()
    val verificationState by verification.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }
    var signOutOpen by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var verificationOrigin by remember { mutableStateOf("search") }
    val destinations = listOf("首页" to Icons.Outlined.Home, "选座" to Icons.Outlined.GridView,
        "我的" to Icons.Outlined.AutoStories, "账号" to Icons.Outlined.Person, "设置" to Icons.Outlined.Settings)
    LaunchedEffect(state.activeAccountId, state.authenticated) {
        features.bindAccount(state.activeAccountId, state.authenticated)
        maps.bindAccount(state.activeAccountId, state.authenticated)
        nativeAccount.bindAccount(state.activeAccountId, state.authenticated)
        verification.dismiss()
        if (!state.authenticated) { mapOpen = false; passwordOpen = false; signOutOpen = false }
    }
    LaunchedEffect(tab, mapOpen, state.authenticated) {
        if (state.authenticated && tab == 1 && !mapOpen) features.loadSearch()
        if (state.authenticated && tab == 2) features.refreshHub()
        if (state.authenticated && mapOpen) maps.refresh()
    }
    LaunchedEffect(accountState.passwordChanged, accountState.credentialsSaved, accountState.busy) {
        if (accountState.passwordChanged && !accountState.busy) {
            accountMessage = accountState.message; passwordOpen = false; viewModel.credentialsChanged()
        }
    }
    LaunchedEffect(seatState.verificationRequired, mapState.verificationRequired, state.activeAccountId) {
        val id = state.activeAccountId
        if (state.authenticated && id != null && verificationState.context == null) {
            if (mapOpen && mapState.verificationRequired) { verificationOrigin = "map"; verification.begin(id, mapState.verificationUserId) }
            else if (seatState.verificationRequired) { verificationOrigin = "search"; verification.begin(id, seatState.verificationUserId) }
        }
    }
    val verifySearch: () -> Unit = { state.activeAccountId?.let { verificationOrigin = "search"; verification.begin(it, seatState.verificationUserId) } }
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White, secondary = Ink,
        secondaryContainer = SoftGreen, onSecondaryContainer = Ink, background = Paper, surface = Color.White,
        onBackground = Ink, onSurface = Ink, surfaceVariant = SoftGreen, onSurfaceVariant = Muted, outline = Line,
        surfaceContainerHigh = Color.White, surfaceContainerHighest = SoftGreen, surfaceContainer = Color.White,
        surfaceContainerLow = Paper, surfaceContainerLowest = Color.White), typography = Typography()) {
        Scaffold(containerColor = Paper, bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                destinations.forEachIndexed { index, (label, icon) -> NavigationBarItem(
                    selected = tab == index, enabled = !mutating && !seatState.reservationBusy && !accountState.busy && verificationState.context == null,
                    onClick = { mapOpen = false; tab = index }, icon = { Icon(icon, null, Modifier.size(23.dp)) },
                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Teal, selectedTextColor = Teal,
                        indicatorColor = SoftGreen, unselectedIconColor = Muted, unselectedTextColor = Muted)) }
            }
        }) { padding ->
            when {
                mapOpen && state.authenticated -> {
                    NativeMapScreen(mapState, Modifier.padding(padding), maps::onFilter, maps::refresh, maps::loadRoom,
                        maps::setListMode, onSeat = features::selectMappedSeat,
                        onBack = { if (!seatState.reservationBusy) { features.dismissReservation(); mapOpen = false } },
                        onVerify = { state.activeAccountId?.let { verificationOrigin = "map"; verification.begin(it, mapState.verificationUserId) } })
                    seatState.selectedSeat?.let { seat -> NativeReservationDialog(seatState, seat, features::selectStart,
                        features::selectEnd, features::confirmReservation, features::dismissReservation, verifySearch,
                        onOpenReservations = { features.dismissReservation(); mapOpen = false; tab = 2 }) }
                }
                tab == 0 -> Dashboard(state, Modifier.padding(padding), viewModel::login, viewModel::refresh,
                    onOpenLibrary = { tab = 1 }, onOpenMap = { tab = 1; mapOpen = true }, onReservations = { tab = 2 }, onAccounts = { tab = 3 })
                tab == 1 -> if (state.authenticated) Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    SeatSearchScreen(seatState, features::setFilter, features::searchSeats, features::nextPage, features::previousPage,
                        features::selectSeat, onBack = { tab = 0 }, onOpenMap = { tab = 1; mapOpen = true }, onToggleFavorite = features::toggleFavorite,
                        onSelectStart = features::selectStart, onSelectEnd = features::selectEnd, onConfirmReservation = features::confirmReservation,
                        onDismissReservation = features::dismissReservation, onRefresh = features::refreshSearch, onVerify = verifySearch,
                        onOpenReservations = { features.dismissReservation(); tab = 2 })
                } else FeatureLoginPrompt(state, Modifier.padding(padding), viewModel::login) { tab = 3 }
                tab == 2 -> if (state.authenticated) LibraryHubScreen(hubState, Modifier.padding(padding), features::refreshHub,
                    features::selectHubSection, features::loadMoreHistory, onSearch = { tab = 1 }, onOpenMap = { tab = 1; mapOpen = true },
                    onFavorite = { favorite -> tab = 1; features.loadSearch(features.favoriteSeat(favorite)) },
                    onRemoveFavorite = features::removeFavorite, onAction = features::performAction, onCancelRecord = features::cancelRecord)
                else FeatureLoginPrompt(state, Modifier.padding(padding), viewModel::login) { tab = 3 }
                tab == 3 -> AccountsScreen(state.copy(busy = state.busy || seatState.reservationBusy || mutating || accountState.busy),
                    Modifier.padding(padding), viewModel::saveAccount, viewModel::selectAccount, viewModel::deleteAccount, viewModel::login,
                    onChangePassword = { nativeAccount.clearResult(); passwordOpen = true }, onLogout = { signOutOpen = true }, message = accountMessage)
                else -> SettingsScreen(state, Modifier.padding(padding), viewModel::setAutoLogin, viewModel::setAutoSwitch)
            }
        }
        if (passwordOpen) PasswordChangeDialog(accountState, nativeAccount::changePassword) { if (!accountState.busy) passwordOpen = false }
        if (signOutOpen) SignOutDialog(accountState.accountLabel, state.busy,
            onConfirm = { signOutOpen = false; viewModel.logout() }, onDismiss = { if (!state.busy) signOutOpen = false })
        NativeVerificationDialog(verificationState, verification::refresh, verification::dismiss, onVerified = { proof ->
            features.verificationCompleted(proof, resumeSearch = verificationOrigin == "search")
            verification.dismiss(); if (verificationOrigin == "map") maps.refresh()
        })
        if (extensionOptions.isNotEmpty()) {
            var selected by remember(extensionOptions) { mutableStateOf<String?>(null) }
            AlertDialog(onDismissRequest = features::dismissExtension, title = { Text("续约至") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    extensionOptions.forEach { option -> TextButton(onClick = { selected = option.value }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selected == option.value) "✓ ${option.label}" else option.label)
                    } }
                } }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(features::extendReservation) }) { Text("确认续约") } },
                dismissButton = { TextButton(onClick = features::dismissExtension) { Text("取消") } })
        }
    }
}

@Composable
private fun FeatureLoginPrompt(state: LibraryUiState, modifier: Modifier, onLogin: () -> Unit, onAccounts: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("登录后使用图书馆功能", style = MaterialTheme.typography.titleLarge)
        Text(state.status, Modifier.padding(vertical = 16.dp), color = Muted)
        Button(onClick = if (state.accounts.isEmpty()) onAccounts else onLogin, enabled = !state.busy) {
            Text(if (state.accounts.isEmpty()) "添加账号" else "登录图书馆")
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
    onOpenMap: () -> Unit,
    onReservations: () -> Unit,
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
                Text(when { state.busy -> "正在连接图书馆…"; state.authenticated -> "查询并预约座位"; account != null -> "登录并同步"; else -> "添加账号，开始使用" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!state.busy) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
                }
            }
            if (state.authenticated) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onOpenMap) { Text("布局选座") }
                TextButton(onClick = onReservations) { Text("我的预约") }
            }
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
    onLogin: () -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
    message: String? = null,
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
        if (state.authenticated) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                TextButton(onClick = onChangePassword, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Password, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("修改图书馆密码")
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Line)
                TextButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("退出登录")
                }
            }
        }
        message?.let { Text(it, color = Teal, fontSize = 12.sp) }
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
            Text("选座、地图、预约与账号操作均使用 Android 原生界面，验证码在本机自动识别。此应用为第三方客户端。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
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
