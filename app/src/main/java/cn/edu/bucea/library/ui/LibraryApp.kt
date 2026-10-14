package cn.edu.bucea.library.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import cn.edu.bucea.library.LibraryFeatureViewModel
import cn.edu.bucea.library.MainViewModel
import cn.edu.bucea.library.NativeAccountViewModel
import cn.edu.bucea.library.NativeMapViewModel
import cn.edu.bucea.library.NativeVerificationViewModel
import cn.edu.bucea.library.update.UpdateViewModel
import kotlinx.coroutines.delay

@Composable
fun LibraryApp(viewModel: MainViewModel, features: LibraryFeatureViewModel, maps: NativeMapViewModel,
    nativeAccount: NativeAccountViewModel, verification: NativeVerificationViewModel,
    updates: UpdateViewModel, onInstallUpdate: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val seatState by features.search.collectAsStateWithLifecycle()
    val hubState by features.hub.collectAsStateWithLifecycle()
    val extensionOptions by features.extensionTimes.collectAsStateWithLifecycle()
    val mutating by features.mutationBusy.collectAsStateWithLifecycle()
    val mapState by maps.mapState.collectAsStateWithLifecycle()
    val accountState by nativeAccount.state.collectAsStateWithLifecycle()
    val verificationState by verification.state.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val interactions = remember { BusinessInteractions() }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var updateIdle by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var signOutOpen by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var verificationOrigin by remember { mutableStateOf("search") }
    val context = LocalContext.current
    val businessBusy = state.busy || mutating || seatState.loading || hubState.loading || mapState.loading ||
        seatState.reservationBusy || accountState.busy || interactions.focusedInputs.intValue > 0 ||
        seatState.selectedSeat != null || hubState.selectedRecord != null || mapOpen || signOutOpen ||
        verificationState.context != null || extensionOptions.isNotEmpty() || interactions.dialogs.intValue > 0
    LaunchedEffect(businessBusy, lastTouch, lifecycleState) {
        updateIdle = false
        if (!businessBusy && lifecycleState == Lifecycle.State.RESUMED) {
            delay(1500)
            updateIdle = true
        }
    }
    val destinations = listOf("首页" to Icons.Outlined.Home, "选座" to Icons.Outlined.GridView,
        "我的" to Icons.Outlined.AutoStories, "账号" to Icons.Outlined.Person, "设置" to Icons.Outlined.Settings)
    LaunchedEffect(state.activeAccountId, state.authenticated) {
        features.bindAccount(state.activeAccountId, state.authenticated)
        maps.bindAccount(state.activeAccountId, state.authenticated)
        nativeAccount.bindAccount(state.activeAccountId, state.authenticated)
        verification.dismiss()
        if (!state.authenticated) { mapOpen = false; signOutOpen = false }
    }
    val activeAccountLabel = state.accounts.firstOrNull { it.id == state.activeAccountId }?.label
    LaunchedEffect(state.activeAccountId, activeAccountLabel, state.authenticated) {
        features.bindAccount(state.activeAccountId, state.authenticated)
        nativeAccount.bindAccount(state.activeAccountId, state.authenticated)
    }
    LaunchedEffect(tab, mapOpen, state.authenticated) {
        if (state.authenticated && tab == 1 && !mapOpen) features.loadSearch()
        if (state.authenticated && tab == 2) features.refreshHub()
        if (state.authenticated && mapOpen) maps.refresh()
    }
    LaunchedEffect(seatState.verificationRequired, mapState.verificationRequired, state.activeAccountId) {
        val id = state.activeAccountId
        if (state.authenticated && id != null && verificationState.context == null) {
            if (mapOpen && mapState.verificationRequired) { verificationOrigin = "map"; verification.begin(id, mapState.verificationUserId) }
            else if (seatState.verificationRequired) { verificationOrigin = "search"; verification.begin(id, seatState.verificationUserId) }
        }
    }
    val verifySearch: () -> Unit = { state.activeAccountId?.let { verificationOrigin = "search"; verification.begin(it, seatState.verificationUserId) } }
    BackHandler(enabled = tab != 0 && !mapOpen) {
        if (!mutating && !seatState.reservationBusy && !accountState.busy && !state.busy && verificationState.context == null) tab = 0
    }
    CompositionLocalProvider(LocalBusinessInteractions provides interactions) {
    LibraryTheme(state.themeColor) {
        Scaffold(modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = android.os.SystemClock.elapsedRealtime()
                }
            }
        }, containerColor = Paper, bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, Line),
                tonalElevation = 0.dp, shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().selectableGroup().padding(7.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    destinations.forEachIndexed { index, (label, icon) ->
                        val selected = tab == index
                        Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                            .background(if (selected) SoftAccent else Color.Transparent)
                            .selectable(selected = selected, role = Role.Tab,
                                enabled = !state.busy && !mutating && !seatState.reservationBusy && !accountState.busy && verificationState.context == null,
                                onClick = { features.dismissRecord(); mapOpen = false; tab = index })
                            .heightIn(min = 56.dp).padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) Accent else Muted)
                            Text(label, fontSize = 11.sp, color = if (selected) Accent else Muted,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
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
                tab == 0 -> Dashboard(state.copy(busy = state.busy || mutating || seatState.reservationBusy || accountState.busy), hubState, Modifier.padding(padding), viewModel::login,
                    onRefresh = { viewModel.refresh(); if (state.authenticated) features.refreshHub() },
                    onOpenLibrary = { tab = 1 },
                    onReservations = { features.selectHubSection(HubSection.RESERVATIONS); tab = 2 }, onAccounts = { tab = 3 },
                    onOpenMap = { tab = 1; mapOpen = true },
                    onFavorites = { features.selectHubSection(HubSection.FAVORITES); tab = 2 })
                tab == 1 -> if (state.authenticated) Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    SeatSearchScreen(seatState, features::setFilter, features::searchSeats, features::nextPage, features::previousPage,
                        features::selectSeat, onBack = { tab = 0 }, onOpenMap = { tab = 1; mapOpen = true }, onToggleFavorite = features::toggleFavorite,
                        onSelectStart = features::selectStart, onSelectEnd = features::selectEnd, onConfirmReservation = features::confirmReservation,
                        onDismissReservation = features::dismissReservation, onRefresh = features::refreshSearch, onVerify = verifySearch,
                        onOpenReservations = { features.dismissReservation(); tab = 2 })
                } else FeatureLoginPrompt(state, Modifier.padding(padding), viewModel::login, viewModel::refresh) { tab = 3 }
                tab == 2 -> if (state.authenticated) LibraryHubScreen(hubState, Modifier.padding(padding), features::refreshHub,
                    features::selectHubSection, features::loadMoreHistory, onSearch = { tab = 1 }, onOpenMap = { tab = 1; mapOpen = true },
                    onFavorite = { favorite -> tab = 1; features.loadSearch(features.favoriteSeat(favorite)) },
                    onRemoveFavorite = features::removeFavorite, onAction = features::performAction, onCancelRecord = features::cancelRecord,
                    onOpenRecord = features::openRecord, onDismissRecord = features::dismissRecord)
                else FeatureLoginPrompt(state, Modifier.padding(padding), viewModel::login, viewModel::refresh) { tab = 3 }
                tab == 3 -> AccountsScreen(state.copy(busy = state.busy || seatState.reservationBusy || mutating || accountState.busy),
                    Modifier.padding(padding), viewModel::saveAccount, viewModel::selectAccount, viewModel::deleteAccount, viewModel::login,
                    onRename = viewModel::renameAccount,
                    onChangePassword = {
                        accountMessage = "修改密码当前未实现"
                        Toast.makeText(context, "修改密码当前未实现", Toast.LENGTH_SHORT).show()
                    }, onLogout = { signOutOpen = true }, onCheckCampus = viewModel::refresh, message = accountMessage)
                else -> SettingsScreen(state, Modifier.padding(padding), viewModel::setAutoLogin, viewModel::setAutoSwitch,
                    viewModel::setThemeColor) {
                    UpdateSettings(updateState, updates.installedVersionName, updates.coordinator)
                }
            }
        }
        if (signOutOpen) SignOutDialog(accountState.accountLabel, state.busy,
            onConfirm = { signOutOpen = false; viewModel.logout() }, onDismiss = { if (!state.busy) signOutOpen = false })
        NativeVerificationDialog(verificationState, verification::refresh, verification::dismiss, onVerified = { proof ->
            features.verificationCompleted(proof, resumeSearch = verificationOrigin == "search")
            verification.dismiss(); if (verificationOrigin == "map") maps.refresh()
        })
        if (extensionOptions.isNotEmpty()) {
            var selected by remember(extensionOptions) { mutableStateOf<String?>(null) }
            val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtMost(360.dp)
            BusinessSelectionSheet(onDismissRequest = features::dismissExtension, title = { Text("续约至") },
                text = { Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState())) {
                    extensionOptions.forEach { option -> TextButton(onClick = { selected = option.value }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selected == option.value) "✓ ${option.label}" else option.label)
                    } }
                } }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(features::extendReservation) }) { Text("确认续约") } },
                dismissButton = { TextButton(onClick = features::dismissExtension) { Text("取消") } })
        }
        UpdateDialogs(updateState, updateIdle && !businessBusy && lifecycleState == Lifecycle.State.RESUMED &&
            android.os.SystemClock.elapsedRealtime() - lastTouch >= 1500,
            updates.coordinator, onInstallUpdate)
    }
    }
}
