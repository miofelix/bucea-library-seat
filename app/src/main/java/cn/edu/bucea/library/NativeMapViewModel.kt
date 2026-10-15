package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeMapRepository
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.loadRegion
import cn.edu.bucea.library.data.models.NativeMapOptions
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.data.models.RegionSeatOverview
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class NativeMapViewModel(application: Application) : AndroidViewModel(application) {
    private val accounts = AccountStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val library by lazy { LibraryRepository(accounts, ocrResource.value) }
    private val repository by lazy { NativeMapRepository(library) }
    private val coordinator = NativeMapCoordinator(
        scope = viewModelScope,
        repository = { repository },
        account = { id ->
            check(accounts.activeAccountId == id) { "请先登录当前图书馆账号" }
            accounts.accounts().find { it.id == id } ?: error("账号已移除，请重新登录")
        },
        releaseSession = { library.releaseSession(it) },
        userMessage = { library.userMessage(it) },
    )
    val mapState = coordinator.mapState

    fun bindAccount(id: String?, loggedIn: Boolean) = coordinator.bindAccount(id, loggedIn)
    fun selectRegion(region: RegionSeatOverview) = coordinator.selectRegion(region)
    fun showRegionSelector() = coordinator.showRegionSelector()
    fun refresh() = coordinator.refresh()
    fun onFilter(key: String, value: String) = coordinator.onFilter(key, value)
    fun loadRoom(roomId: String) = coordinator.loadRoom(roomId)
    fun setListMode(value: Boolean) = coordinator.setListMode(value)

    override fun onCleared() {
        coordinator.close()
        if (ocrResource.isInitialized()) ocrResource.value.close()
        super.onCleared()
    }
}

/** Keeps the requested room independent from the last successfully rendered layout. */
internal class NativeMapCoordinator(
    private val scope: CoroutineScope,
    repository: () -> NativeMapRepository,
    private val account: (String?) -> SavedAccount,
    private val releaseSession: (String) -> Unit,
    private val userMessage: (Throwable) -> String,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val repository by lazy(repository)
    private val mutableState = MutableStateFlow(NativeMapState())
    val mapState = mutableState.asStateFlow()
    private var boundId: String? = null
    private var authenticated = false
    private var operation: Job? = null
    private var optionsDay: LocalDate? = null
    private var pendingRegion: RegionSeatOverview? = null
    private var requestedRoomId: String? = null

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (boundId == id && authenticated == loggedIn) return
        operation?.cancel()
        boundId?.let(releaseSession)
        boundId = id
        authenticated = loggedIn
        optionsDay = null
        pendingRegion = null
        requestedRoomId = null
        mutableState.value = NativeMapState()
    }

    private fun account(): SavedAccount {
        check(authenticated) { "请先登录当前图书馆账号" }
        return account(boundId)
    }

    private fun run(block: suspend (SavedAccount) -> Unit) {
        operation?.cancel()
        operation = scope.launch {
            mutableState.update { it.copy(loading = true, error = null, notice = null) }
            try { block(account()) }
            catch (e: CancellationException) { throw e }
            catch (e: NativeMapRepository.VerificationRequired) {
                mutableState.update { it.copy(verificationRequired = true, verificationUserId = e.userId, notice = e.message) }
            }
            catch (e: Exception) { mutableState.update { it.copy(error = userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) mutableState.update { it.copy(loading = false) } }
        }
    }

    /** Stage exact region navigation; map opening owns the subsequent refresh request. */
    fun selectRegion(region: RegionSeatOverview) {
        operation?.cancel()
        pendingRegion = region
        requestedRoomId = null
        optionsDay = null
        mutableState.value = NativeMapState(directRegionView = true)
    }

    fun showRegionSelector() {
        pendingRegion = null
        mutableState.update { it.copy(directRegionView = false) }
    }

    /** Refresh the requested region, current room, or server date/building/floor choices. */
    fun refresh() = run { account ->
        releaseSession(account.id)
        pendingRegion?.let { region ->
            val selected = repository.loadRegion(account, region)
            if (pendingRegion !== region) return@run
            mutableState.update { selected.copy(loading = true, listMode = it.listMode) }
            if (!selected.verificationRequired && selected.layout?.roomId == region.id) {
                pendingRegion = null
                requestedRoomId = region.id
                optionsDay = today()
            }
            return@run
        }
        if (mutableState.value.options.dates.isEmpty() || optionsDay != today()) {
            requestedRoomId = null
            val previous = mutableState.value.filters
            val options = repository.options(account)
            val dates = options.dates.filterNot { it.value.isBlank() || it.value == "null" }
            val buildings = options.buildings.filterNot { it.value.isBlank() || it.value == "null" }
            val building = previous["building"]?.takeIf { value -> buildings.any { it.value == value } } ?: buildings.firstOrNull()?.value.orEmpty()
            val floors = if (building.isBlank()) emptyList() else repository.floors(account, building)
            val floor = previous["floor"]?.takeIf { value -> floors.any { it.value == value } }
                ?: floors.firstOrNull { it.value.isNotBlank() && it.value != "null" }?.value.orEmpty()
            val onDate = previous["onDate"]?.takeIf { value -> dates.any { it.value == value } } ?: dates.firstOrNull()?.value.orEmpty()
            mutableState.update { it.copy(options = NativeMapOptions(dates, buildings, floors),
                filters = mapOf("onDate" to onDate, "building" to building, "floor" to floor), layout = null, rooms = emptyList()) }
            optionsDay = today()
        }
        val room = requestedRoomId ?: mutableState.value.layout?.roomId
        if (room != null && mutableState.value.rooms.any { it.id == room }) loadLayout(account, room)
        else loadRooms(account)
    }

    fun onFilter(key: String, value: String) {
        if (key !in setOf("onDate", "building", "floor")) return
        val valid = when (key) {
            "onDate" -> mutableState.value.options.dates
            "building" -> mutableState.value.options.buildings
            else -> mutableState.value.options.floors
        }.any { it.value == value }
        if (!valid) return
        pendingRegion = null
        requestedRoomId = null
        mutableState.update { it.copy(filters = it.filters + (key to value), layout = null, rooms = emptyList()) }
        run { account ->
            if (key == "building") {
                val floors = repository.floors(account, value)
                mutableState.update { it.copy(options = it.options.copy(floors = floors),
                    filters = it.filters + ("floor" to floors.firstOrNull { floor -> floor.value.isNotBlank() && floor.value != "null" }?.value.orEmpty())) }
            }
            loadRooms(account)
        }
    }

    private suspend fun loadRooms(account: SavedAccount) {
        val filters = mutableState.value.filters
        val onDate = filters["onDate"].orEmpty()
        val building = filters["building"].orEmpty()
        val floor = filters["floor"].orEmpty()
        if (onDate.isBlank() || building.isBlank() || floor.isBlank()) {
            mutableState.update { it.copy(notice = "请选择日期、场馆和楼层") }
            return
        }
        val rooms = repository.rooms(account, onDate, building, floor)
        mutableState.update { it.copy(rooms = rooms.rooms, layout = null, notice = rooms.message.takeIf(String::isNotBlank),
            verificationRequired = rooms.verificationRequired, verificationUserId = rooms.verificationUserId) }
    }

    fun loadRoom(roomId: String) {
        requestedRoomId = roomId
        run { account -> loadLayout(account, roomId) }
    }

    private suspend fun loadLayout(account: SavedAccount, roomId: String) {
        val room = mutableState.value.rooms.find { it.id == roomId } ?: return
        val layout = repository.layout(account, room, mutableState.value.filters["onDate"].orEmpty())
        mutableState.update { it.copy(layout = layout, notice = layout.message.takeIf(String::isNotBlank),
            verificationRequired = layout.verificationRequired, verificationUserId = layout.verificationUserId) }
    }

    fun setListMode(value: Boolean) { mutableState.update { it.copy(listMode = value) } }

    fun close() {
        operation?.cancel()
    }
}
