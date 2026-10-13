package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.LoginFailure
import cn.edu.bucea.library.data.NativeAccountRepository
import cn.edu.bucea.library.data.PasswordChangeUncertain
import cn.edu.bucea.library.data.models.NativeAccountUiState
import cn.edu.bucea.library.data.models.PasswordChangeOutcome
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class NativeAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AccountStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val library by lazy { LibraryRepository(store, ocrResource.value) }
    private val repository by lazy { NativeAccountRepository(library, store) }
    private val mutableState = MutableStateFlow(NativeAccountUiState())
    val state = mutableState.asStateFlow()
    private var accountId: String? = null
    private var authenticated = false
    private var operation: Job? = null

    fun bindAccount(id: String?, loggedIn: Boolean) {
        if (accountId == id && authenticated == loggedIn) {
            val label = store.accounts().find { it.id == id }?.let {
                if (it.label.isBlank()) it.username else "${it.label} · ${it.username}"
            }.orEmpty()
            mutableState.update { it.copy(accountLabel = label) }
            return
        }
        operation?.cancel()
        accountId?.let(library::releaseSession)
        accountId = id
        authenticated = loggedIn
        mutableState.value = NativeAccountUiState(accountLabel = store.accounts().find { it.id == id }
            ?.let { "${it.label} · ${it.username}" }.orEmpty())
    }

    fun clearResult() {
        if (state.value.busy || state.value.outcomeUncertain) return
        mutableState.value = NativeAccountUiState(accountLabel = state.value.accountLabel)
    }

    /** Call only from the native password dialog's explicit 修改密码 button. */
    fun changePassword(current: String, new: String, confirmation: String) {
        if (state.value.busy || state.value.outcomeUncertain || state.value.passwordChanged) return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = null) }
            try {
                check(authenticated) { "请先登录后修改密码" }
                val account = store.accounts().find { it.id == accountId } ?: error("账号已移除")
                val result = repository.changePassword(account, current, new, confirmation)
                mutableState.update { it.copy(passwordChanged = result.outcome == PasswordChangeOutcome.SUCCESS,
                    credentialsSaved = result.credentialsSaved, outcomeUncertain = result.outcome == PasswordChangeOutcome.UNKNOWN,
                    message = result.message, error = if (result.outcome == PasswordChangeOutcome.REJECTED) result.message else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.update { it.copy(error = if (error is IllegalArgumentException || error is IllegalStateException)
                    error.message else if (error is PasswordChangeUncertain || error is LoginFailure) error.message
                    else if (error is IOException) "无法连接图书馆，请检查校园网络后重试" else "无法处理账号操作，请重试",
                    outcomeUncertain = error is PasswordChangeUncertain) }
            } finally {
                if (coroutineContext[Job]?.isActive == true) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        operation?.cancel()
        if (ocrResource.isInitialized()) ocrResource.value.close()
        super.onCleared()
    }
}
