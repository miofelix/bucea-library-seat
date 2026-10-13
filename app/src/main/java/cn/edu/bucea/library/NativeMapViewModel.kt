package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeMapRepository
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.NativeMapOptions
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
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
    private val mutableState = MutableStateFlow(NativeMapState())
    val mapState = mutableState.asStateFlow()
    private var boundId: String? = null
    private var authenticated = false
    private var operation: Job? = null
    private var optionsDay: LocalDate? = null

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (boundId == id && authenticated == loggedIn) return
        operation?.cancel()
        boundId?.let { library.releaseSession(it) }
        boundId = id
        authenticated = loggedIn
        optionsDay = null
        mutableState.value = NativeMapState()
    }

    private fun account(): SavedAccount {
        check(authenticated && accounts.activeAccountId == boundId) { "请先登录当前图书馆账号" }
        return accounts.accounts().find { it.id == boundId } ?: error("账号已移除，请重新登录")
    }

    private fun run(block: suspend (SavedAccount) -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, notice = null) }
            try { block(account()) }
            catch (e: CancellationException) { throw e }
            catch (e: NativeMapRepository.VerificationRequired) {
                mutableState.update { it.copy(verificationRequired = true, verificationUserId = e.userId, notice = e.message) }
            }
            catch (e: Exception) { mutableState.update { it.copy(error = library.userMessage(e)) } }
            finally { if (coroutineContext[Job]?.isActive == true) mutableState.update { it.copy(loading = false) } }
        }
    }

    /** Refresh the current room, or initialise server date/building/floor choices. */
    fun refresh() = run { account ->
        library.releaseSession(account.id)
        if (mutableState.value.options.dates.isEmpty() || optionsDay != LocalDate.now()) {
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
            optionsDay = LocalDate.now()
        }
        val room = mutableState.value.layout?.roomId
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

    fun loadRoom(roomId: String) = run { account -> loadLayout(account, roomId) }

    private suspend fun loadLayout(account: SavedAccount, roomId: String) {
        val room = mutableState.value.rooms.find { it.id == roomId } ?: return
        val layout = repository.layout(account, room, mutableState.value.filters["onDate"].orEmpty())
        mutableState.update { it.copy(layout = layout, notice = layout.message.takeIf(String::isNotBlank),
            verificationRequired = layout.verificationRequired, verificationUserId = layout.verificationUserId) }
    }

    fun setListMode(value: Boolean) { mutableState.update { it.copy(listMode = value) } }

    override fun onCleared() {
        operation?.cancel()
        if (ocrResource.isInitialized()) ocrResource.value.close()
        super.onCleared()
    }
}
