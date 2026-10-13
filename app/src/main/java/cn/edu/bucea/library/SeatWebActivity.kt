package cn.edu.bucea.library

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import cn.edu.bucea.library.data.*
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Every original route, form and AJAX operation runs in the site's own document. */
class SeatWebActivity : ComponentActivity() {
    private lateinit var store: AccountStore
    private lateinit var account: SavedAccount
    private lateinit var webView: WebView
    private lateinit var ocr: DdddOcr
    private lateinit var repository: LibraryRepository
    private var pageTitle by mutableStateOf("完整功能中心")
    private var loading by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var mobile by mutableStateOf(true)
    private var challenge by mutableStateOf<CaptchaChallenge?>(null)
    private var reauthenticating by mutableStateOf(false)
    private var installingCookies by mutableStateOf(true)
    private var reauthenticationCount = 0
    private var signedOut = false
    private var cookiesInstalled = false
    private var logoutPending = false
    private var requestedUrl = LibraryRepository.BASE_URL + "/self"
    private var scrollToRestore: Pair<Int, Int>? = null
    private val routes = listOf("自选座位" to "/self", "布局选座" to "/map", "常用座位" to "/freeBook/fav",
        "我的预约" to "/history?type=SEAT", "违约记录" to "/user/violations", "修改密码" to "/user/password")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        store = AccountStore(this)
        val selected = store.accounts().find { it.id == intent.getStringExtra("account_id") }
        if (selected == null || selected.id != store.activeAccountId) { finish(); return }
        account = selected
        signedOut = store.isSignedOut(account.id)
        mobile = getSharedPreferences("library_browser", MODE_PRIVATE).getBoolean("mobile_layout", true)
        val sameAccount = savedInstanceState?.getString("browser_account") == account.id
        requestedUrl = safePageUrl(if (sameAccount) savedInstanceState?.getString("browser_url") else intent.getStringExtra("initial_path"))
            ?: LibraryRepository.BASE_URL + "/self"
        if (sameAccount) scrollToRestore = savedInstanceState!!.getInt("browser_scroll_x") to savedInstanceState.getInt("browser_scroll_y")
        ocr = DdddOcr(this)
        repository = LibraryRepository(store, ocr)
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(246, 247, 242))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) { pageTitle = title?.substringBefore(" ::") ?: "完整功能中心" }
                override fun onProgressChanged(view: WebView?, progress: Int) { loading = progress < 100 }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (!allowed(uri)) { message = "此 App 仅访问校园内网图书馆"; return true }
                    if (request?.isForMainFrame == true && uri.path == "/logout") beginSignOut()
                    if (request?.isForMainFrame == true && request.method == "GET") safePageUrl(uri.toString())?.let { requestedUrl = it }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val uri = request?.url ?: return null
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        if (!allowed(uri)) return android.webkit.WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "仅支持图书馆内网".byteInputStream())
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true; message = null
                    if (url != null) {
                        if (Uri.parse(url).path == "/logout") beginSignOut()
                        safePageUrl(url)?.let { requestedUrl = it }
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    if (url == null || !allowed(Uri.parse(url))) return
                    if (mobile) injectAdapter()
                    if (logoutPending) { completeSignOut(); return }
                    val loginPage = Uri.parse(url).path.orEmpty().let { it == "/login" || it == "/auth/signIn" }
                    syncCookies()
                    if (loginPage && !signedOut && store.autoLogin && reauthenticationCount < 1) authenticate()
                    else if (loginPage && !signedOut) message = "登录状态已失效，请返回首页重新登录"
                    else if (!loginPage && safePageUrl(url) != null) {
                        reauthenticationCount = 0
                        if (signedOut) detectManualSignIn()
                        scrollToRestore?.let { (x, y) -> webView.post { webView.scrollTo(x, y) } }
                        scrollToRestore = null
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        loading = false; message = "无法连接图书馆，请检查校园网连接后重试"
                        if (logoutPending) completeSignOut()
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        message = "图书馆服务暂不可用（HTTP ${errorResponse?.statusCode ?: "错误"}）"
                        if (logoutPending) completeSignOut()
                    }
                }

                override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
                    dontResend?.sendToTarget()
                    message = "已保留本次提交结果，可返回上一页查看，或重试打开功能页面"
                }
            }
        }
        setContent { BrowserScreen() }
        lifecycleScope.launch {
            try {
                installCookies()
                webView.loadUrl(if (signedOut) LibraryRepository.BASE_URL + "/login?targetUri=" + Uri.encode(Uri.parse(requestedUrl).encodedPath) else requestedUrl)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "无法恢复登录状态，请返回首页重试"; loading = false }
            finally { installingCookies = false }
        }
    }

    private fun allowed(uri: Uri) = uri.scheme == "http" && uri.host == "10.1.20.7" && (uri.port == -1 || uri.port == 80)

    private fun safePageUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = Uri.parse(if (value.startsWith("/") && !value.startsWith("//")) LibraryRepository.BASE_URL + value else value)
        if (!allowed(uri) || uri.userInfo != null || (uri.path != "/" && routes.none { Uri.parse(it.second).path == uri.path })) return null
        return uri.buildUpon().fragment(null).build().toString()
    }

    private fun selectedAccountValid() = ::account.isInitialized && ::store.isInitialized && store.activeAccountId == account.id

    private fun beginSignOut() {
        if (signedOut) return
        signedOut = true
        logoutPending = true
        cookiesInstalled = false
        store.setSignedOut(account.id, true)
        repository.forget(account.id)
        setResult(RESULT_OK, Intent().putExtra("signed_out", true))
    }

    private fun completeSignOut() {
        if (!logoutPending) return
        logoutPending = false
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
    }

    private fun retryCurrentPage() {
        if (reauthenticating || installingCookies || challenge != null) return
        message = null
        val url = webView.url
        val loginPage = url?.let { Uri.parse(it).path in listOf("/login", "/auth/signIn") } == true
        if (loginPage && !signedOut && store.autoLogin) { reauthenticationCount = 0; authenticate(); return }
        // Always issue a GET for a verified feature page; never replay an earlier form POST.
        webView.loadUrl(safePageUrl(url) ?: requestedUrl)
    }

    private fun detectManualSignIn() {
        val username = org.json.JSONObject.quote(account.username)
        webView.evaluateJavascript("(function(){var u=document.getElementById('sysUsername');return !!u && u.value===$username;})()") { result ->
            if (result == "true" && selectedAccountValid() && !logoutPending) {
                signedOut = false
                cookiesInstalled = true
                store.setSignedOut(account.id, false)
                setResult(RESULT_OK, Intent().putExtra("signed_out", false))
                syncCookies()
            }
        }
    }

    private fun injectAdapter() {
        val script = assets.open("mobile/adapter.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(script, null)
    }

    private suspend fun installCookies() {
        installingCookies = true
        cookiesInstalled = false
        webView.stopLoading()
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(webView, false)
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        for (cookie in SessionCookieJar(store, account.id).all()) {
            suspendCancellableCoroutine { continuation ->
                manager.setCookie(LibraryRepository.BASE_URL, cookie.toString()) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        manager.flush()
        cookiesInstalled = true
        installingCookies = false
    }

    private fun syncCookies() {
        if (!cookiesInstalled || reauthenticating || installingCookies || signedOut || !selectedAccountValid()) return
        runCatching {
            SessionCookieJar(store, account.id).replaceBrowserCookies(CookieManager.getInstance().getCookie(LibraryRepository.BASE_URL))
            CookieManager.getInstance().flush()
        }.onFailure { message = "无法保存最新登录状态，请返回首页重新登录" }
    }

    private fun authenticate() {
        if (reauthenticating || installingCookies || signedOut || !selectedAccountValid()) return
        reauthenticating = true
        reauthenticationCount++
        lifecycleScope.launch {
            try {
                repository.releaseSession(account.id)
                val result = repository.login(account) { message = it }
                challenge = result.challenge
                if (result.overview != null && selectedAccountValid()) { installCookies(); webView.loadUrl(requestedUrl); message = null }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = repository.userMessage(e) }
            finally { reauthenticating = false; installingCookies = false; loading = false }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun BrowserScreen() {
        var menu by remember { mutableStateOf(false) }
        val busy = reauthenticating || installingCookies
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF28766C), background = Color(0xFFF6F7F2))) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页") } },
                    actions = {
                        IconButton(enabled = !busy && challenge == null, onClick = { retryCurrentPage() }) { Icon(Icons.Outlined.Refresh, "刷新网页") }
                        Box {
                            IconButton(enabled = !busy && challenge == null, onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "全部功能") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                routes.forEach { (label, path) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menu = false; requestedUrl = LibraryRepository.BASE_URL + path; webView.loadUrl(requestedUrl)
                                }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(if (mobile) "切换原网页布局" else "切换手机布局") }, onClick = {
                                    mobile = !mobile; menu = false
                                    getSharedPreferences("library_browser", MODE_PRIVATE).edit().putBoolean("mobile_layout", mobile).apply()
                                    if (mobile) injectAdapter() else webView.evaluateJavascript("window.BuceaMobile?.setEnabled(false)", null)
                                })
                            }
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { text -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy && challenge == null, onClick = { retryCurrentPage() }) { Text("重试") }
                        }
                    } }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize(), update = {
                            it.isEnabled = !busy
                            it.importantForAccessibility = if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        })
                        if (busy) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .85f), modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                        }) {
                            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("正在恢复登录状态…", Modifier.padding(top = 16.dp))
                            }
                        }
                    }
                }
            }
            BackHandler { if (webView.canGoBack()) webView.goBack() else finish() }
            challenge?.let { pending ->
                var answer by remember(pending.id) { mutableStateOf("") }
                AlertDialog(onDismissRequest = { challenge = null }, title = { Text("输入验证码") }, text = {
                    Column {
                        val bitmap = remember(pending.id) { BitmapFactory.decodeByteArray(pending.image, 0, pending.image.size) }
                        if (bitmap != null) Image(bitmap.asImageBitmap(), "验证码", Modifier.fillMaxWidth().height(90.dp))
                        OutlinedTextField(answer, onValueChange = { answer = it }, label = { Text("验证码") }, singleLine = true)
                    }
                }, confirmButton = { TextButton(enabled = answer.isNotBlank() && !reauthenticating, onClick = {
                    reauthenticating = true
                    lifecycleScope.launch {
                        try {
                            repository.submit(account, pending, answer)
                            if (selectedAccountValid()) { challenge = null; installCookies(); webView.loadUrl(requestedUrl); message = null }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            message = repository.userMessage(e)
                            if (e is LoginFailure && e.kind == FailureKind.CAPTCHA) {
                                try { challenge = repository.challenge(account) }
                                catch (refreshError: CancellationException) { throw refreshError }
                                catch (refreshError: Exception) { challenge = null; message = repository.userMessage(refreshError) }
                            }
                        } finally { reauthenticating = false; installingCookies = false }
                    }
                }) { Text("登录") } }, dismissButton = { TextButton(onClick = { challenge = null; finish() }) { Text("返回首页") } })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::account.isInitialized && ::webView.isInitialized) {
            outState.putString("browser_account", account.id)
            outState.putString("browser_url", safePageUrl(webView.url) ?: requestedUrl)
            outState.putInt("browser_scroll_x", webView.scrollX)
            outState.putInt("browser_scroll_y", webView.scrollY)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { syncCookies(); super.onPause(); if (::webView.isInitialized) webView.onPause() }
    override fun onResume() {
        super.onResume()
        if (::account.isInitialized && !selectedAccountValid()) { cookiesInstalled = false; finish(); return }
        if (::webView.isInitialized) webView.onResume()
    }
    override fun onDestroy() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        completeSignOut()
        super.onDestroy()
        if (::ocr.isInitialized) ocr.close()
    }
}
