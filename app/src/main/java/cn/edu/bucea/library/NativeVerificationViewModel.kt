package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeVerificationRepository
import cn.edu.bucea.library.data.models.*
import cn.edu.bucea.library.ocr.DdddOcr
import cn.edu.bucea.library.ocr.DdddClickSolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NativeVerificationViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AccountStore(application)
    private val ocrResource = lazy { DdddOcr(application) }
    private val library by lazy { LibraryRepository(store, ocrResource.value) }
    private val repository by lazy { NativeVerificationRepository(library) }
    private val solverResource = lazy { DdddClickSolver(application) }
    private val mutable = MutableStateFlow(NativeVerificationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun begin(accountId: String, userId: String = "") {
        job?.cancel()
        mutable.value = NativeVerificationState(context = VerificationContext(accountId, userId = userId), loading = true)
        job = viewModelScope.launch {
            try {
                val account = store.accounts().first { it.id == accountId }
                check(store.activeAccountId == accountId && !store.isSignedOut(accountId)) { "请先登录当前图书馆账号" }
                // Login and verification use separate repositories; reload their latest saved cookies.
                library.releaseSession(accountId)
                val context = repository.context(account, userId)
                mutable.update { it.copy(context = context, loading = true, error = null) }
                val proof = repository.automaticallyVerify(account, context, solve = { challenge ->
                    check(store.activeAccountId == account.id && !store.isSignedOut(account.id)) { "账号已切换，请重新验证" }
                    solverResource.value.solve(challenge).also {
                        check(store.activeAccountId == account.id && !store.isSignedOut(account.id)) { "账号已切换，请重新验证" }
                    }
                }, progress = { challenge -> mutable.update { it.copy(challenge = challenge, loading = true) } })
                mutable.update { it.copy(loading = false, proof = proof) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(loading = false, error = library.userMessage(error)) } }
        }
    }

    fun refresh() {
        val context = state.value.context ?: return
        begin(context.accountId, context.userId)
    }

    fun dismiss() { job?.cancel(); mutable.value = NativeVerificationState() }

    override fun onCleared() {
        job?.cancel()
        state.value.context?.accountId?.let(library::releaseSession)
        if (ocrResource.isInitialized()) ocrResource.value.close()
        if (solverResource.isInitialized()) solverResource.value.close()
        super.onCleared()
    }
}
