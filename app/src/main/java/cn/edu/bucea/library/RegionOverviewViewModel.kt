package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.RegionOverviewRepository
import cn.edu.bucea.library.ocr.DdddOcr

/** Reads the active account's regions independently of homepage navigation and public statistics. */
class RegionOverviewViewModel(application: Application) : AndroidViewModel(application) {
    private val accounts = AccountStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val libraryResource = lazy { LibraryRepository(accounts, ocrResource.value) }
    private val repository by lazy { RegionOverviewRepository(libraryResource.value) }
    private var boundId: String? = null
    private var authenticated = false
    private val coordinator = RegionOverviewCoordinator(viewModelScope, load = { id, progress ->
        check(authenticated && boundId == id && accounts.activeAccountId == id && !accounts.isSignedOut(id)) {
            "请先登录当前图书馆账号"
        }
        val account = accounts.accounts().find { it.id == id } ?: error("账号已移除，请重新登录")
        // Shared native verification writes newer cookies; do not reuse a previous in-memory jar.
        libraryResource.value.releaseSession(id)
        repository.load(account, progress)
    }, userMessage = { error ->
        if (libraryResource.isInitialized()) libraryResource.value.userMessage(error)
        else error.message ?: "阅览区域查询失败，请稍后刷新"
    })
    val state = coordinator.state

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (boundId == id && authenticated == loggedIn) return
        val oldId = boundId
        boundId = id
        authenticated = loggedIn
        coordinator.bindAccount(id, loggedIn)
        if (oldId != null && libraryResource.isInitialized()) libraryResource.value.releaseSession(oldId)
    }

    fun ensureFresh() = coordinator.ensureFresh()

    fun refresh() = coordinator.refresh()

    fun onVerified() = coordinator.onVerified()

    override fun onCleared() {
        coordinator.close()
        if (libraryResource.isInitialized()) boundId?.let(libraryResource.value::releaseSession)
        if (ocrResource.isInitialized()) ocrResource.value.close()
        super.onCleared()
    }
}
