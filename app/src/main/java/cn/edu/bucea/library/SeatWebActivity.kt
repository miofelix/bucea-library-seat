package cn.edu.bucea.library

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import cn.edu.bucea.library.data.*
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
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
    private var reauthenticating = false
    private var reauthenticationCount = 0
    private var signedOut = false
    private var cookiesInstalled = false
    private val routes = listOf("自选座位" to "/self", "布局选座" to "/map", "常用座位" to "/freeBook/fav",
        "我的预约" to "/history?type=SEAT", "违约记录" to "/user/violations", "修改密码" to "/user/password")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = AccountStore(this)
        val selected = store.accounts().find { it.id == intent.getStringExtra("account_id") }
        if (selected == null || selected.id != store.activeAccountId) { finish(); return }
        account = selected
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
                    if (uri.path == "/logout") { signedOut = true; store.clearCookies(account.id) }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val uri = request?.url ?: return null
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        if (!allowed(uri)) return android.webkit.WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "仅支持图书馆内网".byteInputStream())
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true; message = null }
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    if (url == null || !allowed(Uri.parse(url))) return
                    if (mobile) injectAdapter()
                    val loginPage = Uri.parse(url).path.orEmpty().let { it == "/login" || it == "/auth/signIn" }
                    if (loginPage && !signedOut && store.autoLogin && reauthenticationCount < 1) authenticate()
                    else if (loginPage && !signedOut) message = "登录状态已失效，请返回首页重新登录"
                    syncCookies()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) { loading = false; message = "无法连接图书馆，请检查校园网连接后重试" }
                }
            }
        }
        setContent { BrowserScreen() }
        lifecycleScope.launch {
            try { installCookies(); webView.loadUrl(LibraryRepository.BASE_URL + "/self") }
            catch (_: Exception) { message = "无法恢复登录状态，请返回首页重试"; loading = false }
        }
    }

    private fun allowed(uri: Uri) = uri.scheme == "http" && uri.host == "10.1.20.7" && (uri.port == -1 || uri.port == 80)

    private fun injectAdapter() {
        val script = assets.open("mobile/adapter.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(script, null)
    }

    private suspend fun installCookies() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(webView, false)
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
    }

    private fun syncCookies() {
        if (!cookiesInstalled || reauthenticating || signedOut) return
        val header = CookieManager.getInstance().getCookie(LibraryRepository.BASE_URL) ?: return
        val jar = SessionCookieJar(store, account.id)
        val existing = jar.all().associateBy { it.name }
        val parsed = header.split(";").mapNotNull { entry ->
            val name = entry.substringBefore("=").trim()
            if (!entry.contains("=")) return@mapNotNull null
            val old = existing[name]
            val builder = Cookie.Builder().name(name).value(entry.substringAfter("=").trim())
                .hostOnlyDomain("10.1.20.7").path(old?.path ?: "/")
                .expiresAt(old?.expiresAt ?: Long.MAX_VALUE)
            if (old?.httpOnly == true) builder.httpOnly()
            builder.build()
        }
        jar.saveFromResponse(okhttp3.HttpUrl.Builder().scheme("http").host("10.1.20.7").build(), parsed)
        CookieManager.getInstance().flush()
    }

    private fun authenticate() {
        if (reauthenticating) return
        reauthenticating = true
        reauthenticationCount++
        lifecycleScope.launch {
            try {
                val result = repository.login(account) { message = it }
                challenge = result.challenge
                if (result.overview != null) { installCookies(); webView.loadUrl(LibraryRepository.BASE_URL + "/self"); message = null }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = repository.userMessage(e) }
            finally { reauthenticating = false; loading = false }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun BrowserScreen() {
        var menu by remember { mutableStateOf(false) }
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF28766C), background = Color(0xFFF6F7F2))) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页") } },
                    actions = {
                        IconButton(onClick = { webView.reload() }) { Icon(Icons.Outlined.Refresh, "刷新网页") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "全部功能") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                routes.forEach { (label, path) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; webView.loadUrl(LibraryRepository.BASE_URL + path) }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(if (mobile) "切换原网页布局" else "切换手机布局") }, onClick = {
                                    mobile = !mobile; menu = false
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
                            TextButton(onClick = { webView.loadUrl(LibraryRepository.BASE_URL + "/self") }) { Text("重试") }
                        }
                    } }
                    AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
            BackHandler { if (webView.canGoBack()) webView.goBack() else finish() }
            challenge?.let { pending ->
                var answer by remember(pending.id) { mutableStateOf("") }
                AlertDialog(onDismissRequest = { challenge = null }, title = { Text("输入验证码") }, text = {
                    Column {
                        val bitmap = remember(pending.id) { BitmapFactory.decodeByteArray(pending.image, 0, pending.image.size) }
                        Image(bitmap.asImageBitmap(), "验证码", Modifier.fillMaxWidth().height(90.dp))
                        OutlinedTextField(answer, onValueChange = { answer = it }, label = { Text("验证码") }, singleLine = true)
                    }
                }, confirmButton = { TextButton(enabled = answer.isNotBlank() && !reauthenticating, onClick = {
                    reauthenticating = true
                    lifecycleScope.launch {
                        try {
                            repository.submit(account, pending, answer)
                            challenge = null; installCookies(); webView.loadUrl(LibraryRepository.BASE_URL + "/self")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            message = repository.userMessage(e)
                            if (e is LoginFailure && e.kind == FailureKind.CAPTCHA) {
                                try { challenge = repository.challenge(account) }
                                catch (refreshError: CancellationException) { throw refreshError }
                                catch (refreshError: Exception) { challenge = null; message = repository.userMessage(refreshError) }
                            }
                        } finally { reauthenticating = false }
                    }
                }) { Text("登录") } }, dismissButton = { TextButton(onClick = { challenge = null; finish() }) { Text("返回首页") } })
            }
        }
    }

    override fun onPause() { syncCookies(); super.onPause(); if (::webView.isInitialized) webView.onPause() }
    override fun onResume() { super.onResume(); if (::webView.isInitialized) webView.onResume() }
    override fun onDestroy() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
        if (::ocr.isInitialized) ocr.close()
    }
}
