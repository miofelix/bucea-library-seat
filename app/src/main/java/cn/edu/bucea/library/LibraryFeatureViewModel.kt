package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.*
import cn.edu.bucea.library.data.models.*
import cn.edu.bucea.library.ocr.DdddOcr
import cn.edu.bucea.library.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Seat search and booking use only options/times returned by the active account's server. */
class LibraryFeatureViewModel(application: Application) : AndroidViewModel(application) {
    private val accounts = AccountStore(application)
    private val favorites = SeatFavoritesStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val repository by lazy { LibraryRepository(accounts, ocrResource.value) }
    private val functions by lazy { LibraryFunctionsRepository(repository) }
    private val searchState = MutableStateFlow(SeatSearchState())
    val search = searchState.asStateFlow()
    private val hubState = MutableStateFlow(LibraryHubState())
    val hub = hubState.asStateFlow()
    private var boundAccountId: String? = null
    private var authenticated = false
    private var searchJob: Job? = null
    private var roomsJob: Job? = null
    private var timesJob: Job? = null
    private var hubJob: Job? = null
    private var actionJob: Job? = null
    private val pages = mutableMapOf<Int, SeatPage>()
    private val backendSeats = mutableMapOf<String, LibrarySeat>()
    private var requestedQuery: SeatQuery? = null
    private var selectedDate: String? = null
    private val extensionState = MutableStateFlow<List<LibraryOption>>(emptyList())
    val extensionTimes = extensionState.asStateFlow()
    private val mutationState = MutableStateFlow(false)
    val mutationBusy = mutationState.asStateFlow()
    private var lastActionMessage: String? = null

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (boundAccountId == id && authenticated == loggedIn) return
        cancelWork()
        boundAccountId?.let { repository.releaseSession(it) }
        boundAccountId = id
        authenticated = loggedIn
        pages.clear(); backendSeats.clear(); requestedQuery = null
        selectedDate = null; historyOffset = null; extensionState.value = emptyList()
        mutationState.value = false; lastActionMessage = null
        searchState.value = SeatSearchState(accountLabel = accounts.accounts().find { it.id == id }?.let { "${it.label} · ${it.username}" }.orEmpty())
        hubState.value = LibraryHubState()
        if (loggedIn && id != null) refreshHub()
    }

    private fun account(): SavedAccount {
        check(authenticated) { "请先登录图书馆账号" }
        return accounts.accounts().find { it.id == boundAccountId }
            ?: throw IllegalStateException("账号已移除，请重新选择账号")
    }

    fun returnedFromWeb() {
        boundAccountId?.let { repository.releaseSession(it) }
        if (authenticated) refreshHub()
    }

    fun loadSearch(favorite: NativeSeat? = null) {
        if (searchState.value.options.isNotEmpty()) { favorite?.let(::selectSeat); return }
        if (searchState.value.loading) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchState.update { it.copy(loading = true, error = null) }
            try {
                val options = functions.options(account())
                val mapped = mapOf("onDate" to options.dates, "building" to options.buildings, "room" to options.rooms,
                    "hour" to options.durations, "startMin" to options.startTimes, "endMin" to options.endTimes,
                    "power" to options.powers, "window" to options.windows)
                    .mapValues { (_, list) -> list.map { SeatOption(it.value, it.label) } }
                val filters = mapped.mapValues { (key, list) ->
                    if (key == "onDate") list.firstOrNull()?.value.orEmpty()
                    else list.firstOrNull { it.value == "null" }?.value ?: list.firstOrNull()?.value ?: "null"
                } + ("name" to "")
                searchState.update { it.copy(options = mapped, filters = filters, status = "选择日期与条件后查询座位") }
                favorite?.let(::selectSeat)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) searchState.update { it.copy(loading = false) } }
        }
    }

    fun setFilter(key: String, value: String) {
        if (searchState.value.reservationBusy) return
        searchJob?.cancel(); timesJob?.cancel(); pages.clear(); requestedQuery = null
        searchState.update { it.copy(filters = it.filters + (key to value), seats = emptyList(), hasSearched = false,
            page = 0, hasNextPage = false, selectedSeat = null, error = null, status = null,
            loading = false, reservationRequiresOfficial = false) }
        if (key == "building") {
            roomsJob?.cancel()
            searchState.update { it.copy(filters = it.filters + ("room" to "null")) }
            roomsJob = viewModelScope.launch {
                try {
                    val rooms = functions.rooms(account(), value).map { SeatOption(it.value, it.label) }
                    searchState.update { it.copy(options = it.options + ("room" to rooms)) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e)) } }
            }
        }
    }

    private fun query(): SeatQuery = searchState.value.filters.let { f ->
        fun value(key: String) = f[key].orEmpty().ifBlank { "null" }
        SeatQuery(f["onDate"].orEmpty(), value("building"), value("room"), value("hour"), value("power"),
            value("window"), value("startMin"), value("endMin"), f["name"].orEmpty().trim())
    }

    fun searchSeats() {
        if (searchState.value.loading || searchState.value.reservationBusy) return
        if (searchState.value.options.isEmpty()) { loadSearch(); return }
        pages.clear(); requestedQuery = query()
        requestPage(0, 0)
    }

    private fun requestPage(pageIndex: Int, offset: Int) {
        val currentQuery = requestedQuery ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchState.update { it.copy(loading = true, error = null, status = null) }
            try {
                val page = functions.search(account(), currentQuery, offset)
                pages[pageIndex] = page
                page.seats.forEach { backendSeats[it.id] = it }
                showPage(pageIndex, page)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) searchState.update { it.copy(loading = false) } }
        }
    }

    private fun nativeSeat(seat: LibrarySeat): NativeSeat = NativeSeat(seat.id, seat.label, seat.room,
        status = seat.status, available = seat.available,
        localFavorite = boundAccountId?.let { id -> favorites.seats(id).any { it.id == seat.id } } == true)

    private fun showPage(index: Int, page: SeatPage) {
        searchState.update { it.copy(seats = page.seats.map(::nativeSeat), page = index, hasSearched = true,
            hasNextPage = page.nextOffset != null, total = page.seats.size, status = page.message.ifBlank { null },
            reservationRequiresOfficial = page.verificationRequired) }
    }

    fun nextPage() {
        if (searchState.value.loading) return
        val next = searchState.value.page + 1
        pages[next]?.let { showPage(next, it); return }
        val offset = pages[searchState.value.page]?.nextOffset ?: return
        requestPage(next, offset)
    }

    fun previousPage() {
        if (searchState.value.loading) return
        val previous = searchState.value.page - 1
        pages[previous]?.let { showPage(previous, it) }
    }

    fun toggleFavorite(seat: NativeSeat) {
        val id = boundAccountId ?: return
        val model = backendSeats[seat.id] ?: LibrarySeat(seat.id, seat.name, seat.location, seat.status, seat.available)
        favorites.toggle(id, model)
        searchState.update { it.copy(seats = it.seats.map { shown -> if (shown.id == seat.id) shown.copy(localFavorite = !shown.localFavorite) else shown },
            selectedSeat = it.selectedSeat?.let { shown -> if (shown.id == seat.id) shown.copy(localFavorite = !shown.localFavorite) else shown }) }
        if (hubState.value.section == HubSection.FAVORITES) refreshHub()
    }

    fun selectSeat(seat: NativeSeat) {
        if (!seat.available || searchState.value.reservationBusy) return
        timesJob?.cancel()
        selectedDate = searchState.value.filters["onDate"]
        if (selectedDate.isNullOrBlank()) { loadSearch(); return }
        backendSeats.putIfAbsent(seat.id, LibrarySeat(seat.id, seat.name, seat.location, seat.status, seat.available))
        searchState.update { it.copy(selectedSeat = seat, startOptions = emptyList(), endOptions = emptyList(), selectedStart = null,
            selectedEnd = null, reservationBusy = true, error = null, status = null, reservationRequiresOfficial = false,
            reservationOutcomeUncertain = false) }
        timesJob = viewModelScope.launch {
            try {
                val result = functions.startTimes(account(), seat.id, requireNotNull(selectedDate))
                searchState.update { it.copy(startOptions = result.options.map { option -> SeatOption(option.value, option.label) },
                    status = result.message.ifBlank { null }, reservationRequiresOfficial = result.verificationRequired) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) searchState.update { it.copy(reservationBusy = false) } }
        }
    }

    fun selectStart(value: String) {
        val state = searchState.value
        val seat = state.selectedSeat ?: return
        if (state.reservationBusy || state.startOptions.none { it.value == value }) return
        timesJob?.cancel()
        searchState.update { it.copy(selectedStart = value, selectedEnd = null, endOptions = emptyList(), reservationBusy = true, error = null) }
        timesJob = viewModelScope.launch {
            try {
                val result = functions.endTimes(account(), seat.id, requireNotNull(selectedDate), value)
                searchState.update { it.copy(endOptions = result.options.map { option -> SeatOption(option.value, option.label) },
                    status = result.message.ifBlank { null }, reservationRequiresOfficial = result.verificationRequired) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) searchState.update { it.copy(reservationBusy = false) } }
        }
    }

    fun selectEnd(value: String) {
        if (searchState.value.endOptions.any { it.value == value }) searchState.update { it.copy(selectedEnd = value) }
    }

    fun dismissReservation() {
        if (searchState.value.reservationBusy) return
        timesJob?.cancel()
        searchState.update { it.copy(selectedSeat = null, status = null, error = null) }
    }

    /** The screen asks for a final confirmation before invoking this mutation. Never auto-retry it. */
    fun confirmReservation() {
        val state = searchState.value
        if (state.reservationBusy || state.reservationRequiresOfficial || state.reservationOutcomeUncertain) return
        val seat = state.selectedSeat ?: return
        val start = state.startOptions.find { it.value == state.selectedStart } ?: return
        val end = state.endOptions.find { it.value == state.selectedEnd } ?: return
        timesJob = viewModelScope.launch {
            var submitted = false
            searchState.update { it.copy(reservationBusy = true, error = null, status = "正在确认可用时段…") }
            try {
                val current = account()
                val onDate = requireNotNull(selectedDate)
                val selection = ReservationSelection(onDate, backendSeats.getValue(seat.id), LibraryOption(start.value, start.label), LibraryOption(end.value, end.label))
                val freshStarts = functions.startTimes(current, seat.id, onDate)
                val freshEnds = functions.endTimes(current, seat.id, onDate, start.value)
                if (freshStarts.verificationRequired || freshEnds.verificationRequired) {
                    searchState.update { it.copy(reservationRequiresOfficial = true, status = "请在网页中完成学校要求的验证") }; return@launch
                }
                require(freshStarts.options.any { it.value == start.value } && freshEnds.options.any { it.value == end.value }) {
                    "该时段已不可预约，请重新选择座位或时间"
                }
                val prepared = functions.prepareReservation(current, selection)
                if (prepared.verificationRequired) {
                    searchState.update { it.copy(reservationRequiresOfficial = true, status = "请在网页中完成学校要求的验证") }; return@launch
                }
                submitted = true
                val result = functions.reserve(current, prepared)
                searchState.update { it.copy(selectedSeat = if (result.success) null else it.selectedSeat, status = result.message,
                    reservationOutcomeUncertain = !result.success && result.message.contains("确认结果")) }
                refreshHub()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchState.update { it.copy(error = repository.userMessage(e) +
                if (submitted) "；请在我的预约中确认结果，再决定是否重试" else "", reservationOutcomeUncertain = submitted) } }
            finally { if (coroutineContext[Job]?.isActive == true) searchState.update { it.copy(reservationBusy = false) } }
        }
    }

    fun refreshHub() {
        hubJob?.cancel()
        hubJob = viewModelScope.launch {
            hubState.update { it.copy(loading = true, error = null) }
            try {
                val current = account()
                val user = functions.userState(current)
                hubState.update { it.copy(currentStatus = user.status, currentDetails = user.details,
                    actions = user.actions.map(::hubAction)) }
                when (hubState.value.section) {
                    HubSection.RESERVATIONS -> {
                        val result = functions.history(current)
                        hubState.update { it.copy(records = result.items.map(::hubRecord), hasMore = result.nextOffset != null,
                            currentStatus = result.userStatus, notice = operationNotice(result.message)) }
                        historyOffset = result.nextOffset
                    }
                    HubSection.FAVORITES -> {
                        val server = functions.favorites(current)
                        server.seats.forEach { backendSeats[it.id] = it }
                        val saved = favorites.seats(current.id)
                        saved.forEach { backendSeats.putIfAbsent(it.id, it) }
                        val combined = saved.map { HubFavorite(it.id, it.label, it.room, local = true) } +
                            server.seats.filterNot { seat -> saved.any { it.id == seat.id } }.map { HubFavorite(it.id, it.label, it.room) }
                        hubState.update { it.copy(favorites = combined, hasMore = false, notice = operationNotice("收藏保存在本机；网站常用座位按原站记录显示")) }
                    }
                    HubSection.VIOLATIONS -> {
                        val result = functions.violations(current)
                        hubState.update { it.copy(records = result.items.map(::hubRecord), hasMore = result.nextOffset != null, notice = operationNotice(result.message)) }
                        historyOffset = result.nextOffset
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { hubState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) hubState.update { it.copy(loading = false) } }
        }
    }

    private var historyOffset: Int? = null
    private fun operationNotice(message: String) = listOfNotNull(lastActionMessage, message.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { null }
    private fun hubRecord(item: LibraryHistoryItem) = HubRecord(item.id, item.title.ifBlank { "座位记录" }, item.status, item.details)

    fun selectHubSection(section: HubSection) {
        hubState.update { it.copy(section = section, records = emptyList(), favorites = emptyList(), hasMore = false, notice = null) }
        refreshHub()
    }

    fun loadMoreHistory() {
        if (hubState.value.loading) return
        val offset = historyOffset ?: return
        hubJob = viewModelScope.launch {
            hubState.update { it.copy(loading = true, error = null) }
            try {
                val result = if (hubState.value.section == HubSection.VIOLATIONS) functions.violations(account(), offset) else functions.history(account(), offset)
                historyOffset = result.nextOffset
                hubState.update { it.copy(records = (it.records + result.items.map(::hubRecord)).distinctBy { record -> record.id },
                    hasMore = result.nextOffset != null, notice = result.message.ifBlank { null }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { hubState.update { it.copy(error = repository.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) hubState.update { it.copy(loading = false) } }
        }
    }

    fun removeFavorite(item: HubFavorite) { boundAccountId?.let { favorites.remove(it, item.id) }; refreshHub() }
    fun favoriteSeat(item: HubFavorite): NativeSeat = nativeSeat(backendSeats[item.id] ?: LibrarySeat(item.id, item.name, item.location, "常用座位", true))

    private fun hubAction(id: String): HubAction = when (id) {
        "checkIn" -> HubAction(id, "签到", "为当前预约签到，请确认已在图书馆内。")
        "leave" -> HubAction(id, "暂离", "将当前座位标记为暂离，请按学校规定及时返回。")
        "resume" -> HubAction(id, "返回座位", "结束暂离，恢复使用当前座位。")
        "stopUsing" -> HubAction(id, "结束使用", "结束当前座位使用，座位将释放给其他同学。", true)
        "extend", "getTimeExtend" -> HubAction("getTimeExtend", "续约", "查看学校允许的可续约时段。")
        else -> HubAction(id, id, "操作当前预约")
    }

    fun performAction(action: HubAction) {
        if (hubState.value.loading || actionJob?.isActive == true) return
        if (hubState.value.actions.none { it.id == action.id }) return
        actionJob = viewModelScope.launch {
            mutationState.value = true
            var refresh = false
            hubState.update { it.copy(loading = true, error = null) }
            try {
                if (action.id == "getTimeExtend") {
                    val options = functions.extensionTimes(account())
                    extensionState.value = options
                    if (options.isEmpty()) hubState.update { it.copy(notice = "当前没有可续约的时段") }
                } else {
                    val result = functions.performAction(account(), action.id)
                    lastActionMessage = result.message
                    hubState.update { it.copy(notice = result.message) }
                    refresh = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { hubState.update { it.copy(error = repository.userMessage(e) + "；请刷新预约状态确认结果") } }
            finally { if (coroutineContext[Job]?.isActive == true) { mutationState.value = false; hubState.update { it.copy(loading = false) } } }
            if (refresh) refreshHub()
        }
    }

    fun extendReservation(value: String) {
        if (actionJob?.isActive == true || extensionState.value.none { it.value == value }) return
        extensionState.value = emptyList()
        actionJob = viewModelScope.launch {
            mutationState.value = true
            var refresh = false
            hubState.update { it.copy(loading = true, error = null) }
            try {
                val result = functions.performAction(account(), "extend", value)
                lastActionMessage = result.message
                hubState.update { it.copy(notice = result.message) }; refresh = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { hubState.update { it.copy(error = repository.userMessage(e) + "；请刷新预约状态确认结果") } }
            finally { if (coroutineContext[Job]?.isActive == true) { mutationState.value = false; hubState.update { it.copy(loading = false) } } }
            if (refresh) refreshHub()
        }
    }

    fun dismissExtension() { extensionState.value = emptyList() }

    private fun cancelWork() { listOf(searchJob, roomsJob, timesJob, hubJob, actionJob).forEach { it?.cancel() } }
    override fun onCleared() { cancelWork(); if (ocrResource.isInitialized()) ocrResource.value.close(); super.onCleared() }
}
