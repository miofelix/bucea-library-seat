package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.*
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AccountStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val ocr by ocrResource
    private val repository by lazy { LibraryRepository(store, ocr) }
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var pending: CaptchaChallenge? = null
    private var explicitlySignedOut = false

    init {
        try { syncAccounts(); refresh() }
        catch (_: Exception) { mutableState.update { it.copy(status = "无法读取账号存储，请清除应用数据后重新添加账号") } }
    }

    private fun syncAccounts() = mutableState.update { it.copy(
        accounts = store.accounts().map { account -> account.summary() }, activeAccountId = store.activeAccountId,
        autoLogin = store.autoLogin, autoSwitch = store.autoSwitch,
    ) }

    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(status = repository.userMessage(e), authenticated = false) } }
            finally { if (coroutineContext[Job]?.isActive == true) mutableState.update { it.copy(busy = false) } }
        }
    }

    fun refresh() = run {
        mutableState.update { it.copy(status = "正在连接图书馆…") }
        val overview = repository.publicOverview()
        mutableState.update { it.copy(overview = overview) }
        val account = store.accounts().find { it.id == store.activeAccountId }
        if (account != null) {
            explicitlySignedOut = store.isSignedOut(account.id)
            if (!explicitlySignedOut && (store.autoLogin || state.value.authenticated)) loginAccounts(account)
            else if (explicitlySignedOut) mutableState.update {
                it.copy(authenticated = false, status = "已退出登录", overview = overview.copy(displayName = null))
            }
            else {
                val restored = repository.restore(account)
                mutableState.update { it.copy(authenticated = restored != null, status = if (restored != null) "已恢复登录状态" else "已连接校园网，点击登录",
                    overview = overview.copy(displayName = restored?.displayName)) }
            }
        } else mutableState.update { it.copy(status = "已连接校园网，添加账号即可登录") }
    }

    fun login() = run {
        explicitlySignedOut = false
        pending = null
        mutableState.update { it.copy(captcha = null) }
        val account = store.accounts().find { it.id == store.activeAccountId }
            ?: throw IllegalArgumentException("请先添加图书馆账号")
        store.setSignedOut(account.id, false)
        loginAccounts(account)
    }

    fun onWebReturned(signedOut: Boolean) {
        operation?.cancel()
        store.activeAccountId?.let(repository::releaseSession)
        pending = null
        if (signedOut) {
            explicitlySignedOut = true
            store.activeAccountId?.let { store.setSignedOut(it, true) }
        }
        mutableState.update { it.copy(authenticated = false, captcha = null, overview = it.overview?.copy(displayName = null)) }
        refresh()
    }

    private suspend fun loginAccounts(first: SavedAccount) {
        val candidates = listOf(first) + if (store.autoSwitch) store.accounts().filterNot { it.id == first.id } else emptyList()
        for ((index, account) in candidates.withIndex()) {
            store.activeAccountId = account.id
            if (store.isSignedOut(account.id)) continue
            syncAccounts()
            mutableState.update { it.copy(authenticated = false, captcha = null, overview = it.overview?.copy(displayName = null)) }
            try {
                val result = repository.login(account) { progress -> mutableState.update { it.copy(status = progress) } }
                pending = result.challenge
                mutableState.update { it.copy(authenticated = result.overview != null, captcha = result.challenge?.image,
                    overview = (it.overview ?: LibraryOverview()).copy(displayName = result.overview?.displayName),
                    status = if (result.challenge != null) "验证码未识别成功，请手动输入一次" else "已登录，登录状态已保存") }
                return
            } catch (e: LoginFailure) {
                if (e.kind != FailureKind.CREDENTIALS || index == candidates.lastIndex) throw e
                mutableState.update { it.copy(status = "当前账号登录失败，正在尝试下一个账号…") }
            }
        }
    }

    fun saveAccount(username: String, password: String, label: String) {
        try {
            operation?.cancel(); pending = null
            val account = store.save(username.trim(), password, label.trim())
            repository.forget(account.id)
            syncAccounts()
            mutableState.update { it.copy(authenticated = false, captcha = null, overview = it.overview?.copy(displayName = null)) }
            login()
        } catch (e: Exception) { mutableState.update { it.copy(status = e.message ?: "保存失败") } }
    }

    fun selectAccount(id: String) {
        if (store.accounts().none { it.id == id }) return
        operation?.cancel(); pending = null; store.activeAccountId = id; syncAccounts()
        mutableState.update { it.copy(authenticated = false, captcha = null, overview = it.overview?.copy(displayName = null)) }
        login()
    }

    fun deleteAccount(id: String) {
        operation?.cancel(); pending = null
        repository.forget(id)
        SeatFavoritesStore(getApplication()).clear(id)
        store.delete(id); syncAccounts()
        mutableState.update { it.copy(authenticated = false, busy = false, captcha = null, status = "账号已删除", overview = it.overview?.copy(displayName = null)) }
        if (store.activeAccountId != null) refresh()
    }

    fun setAutoLogin(value: Boolean) { store.autoLogin = value; syncAccounts() }
    fun setAutoSwitch(value: Boolean) { store.autoSwitch = value; syncAccounts() }
    fun submitCaptcha(answer: String) {
        if (answer.isBlank()) return
        val challenge = pending ?: return
        val account = store.accounts().find { it.id == store.activeAccountId } ?: return
        run {
            try {
                val overview = repository.submit(account, challenge, answer)
                pending = null
                mutableState.update { it.copy(authenticated = true, captcha = null, status = "已登录，登录状态已保存", overview = (it.overview ?: LibraryOverview()).copy(displayName = overview.displayName)) }
            } catch (e: LoginFailure) {
                if (e.kind != FailureKind.CAPTCHA) { pending = null; mutableState.update { it.copy(captcha = null) }; throw e }
                pending = repository.challenge(account)
                mutableState.update { it.copy(captcha = pending?.image, status = "验证码错误，已刷新，请重新输入") }
            }
        }
    }
    fun dismissCaptcha() { pending = null; mutableState.update { it.copy(captcha = null, status = "已取消登录，可点击登录重试") } }

    override fun onCleared() {
        operation?.cancel()
        if (ocrResource.isInitialized()) ocr.close()
        super.onCleared()
    }
}
