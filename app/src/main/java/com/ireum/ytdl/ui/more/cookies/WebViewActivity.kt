package com.ireum.ytdl.ui.more.cookies

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.cookies.CookieAcquisitionHandoff
import com.ireum.ytdl.database.cookies.CookieProjectionCoordinator
import com.ireum.ytdl.database.viewmodel.CookieViewModel
import com.ireum.ytdl.ui.BaseActivity
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.google.accompanist.web.AccompanistWebChromeClient
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.rememberWebViewState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class WebViewActivity : BaseActivity() {
    private lateinit var cookiesViewModel: CookieViewModel
    private var webView: WebView? = null
    private lateinit var webViewCompose: ComposeView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var generateBtn: MaterialButton
    private lateinit var cookieManager: CookieManager
    private lateinit var url: String
    private lateinit var description: String
    private lateinit var cookies: String
    private lateinit var webViewClient: WebViewClient
    private lateinit var preferences: SharedPreferences
    private lateinit var acquisitionRequestId: String

    private var incognito: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.webview_activity)
        val extras = intent.extras
        url = extras?.getString("url").orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        description = extras?.getString("description", "").orEmpty()
        incognito = extras?.getBoolean("incognito", false) == true
        acquisitionRequestId = extras?.getString(CookieAcquisitionHandoff.EXTRA_REQUEST_ID)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        intent.putExtra(CookieAcquisitionHandoff.EXTRA_REQUEST_ID, acquisitionRequestId)

        cookiesViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        lifecycleScope.launch {
            val appbar = findViewById<AppBarLayout>(R.id.webview_appbarlayout)
            toolbar = appbar.findViewById(R.id.webviewToolbar)
            generateBtn = toolbar.findViewById(R.id.generate)
            webViewCompose = findViewById(R.id.webview_compose)

            if (!url.isYoutubeURL()) {
                toolbar.menu.children.firstOrNull { it.itemId == R.id.get_data_sync_id }?.isVisible = false
            }

            toolbar.menu.children.firstOrNull { it.itemId == R.id.incognito }?.isChecked = incognito
            toolbar.menu.children.firstOrNull { it.itemId == R.id.get_data_sync_id }?.isVisible = false

            toolbar.setOnMenuItemClickListener { m : MenuItem ->
                when(m.itemId) {
                    R.id.back -> {
                        navigateBackInWebViewOrFinish()
                    }
                    R.id.incognito -> {
                        intent.putExtra("incognito", !incognito)
                        recreate()
                    }
                    R.id.desktop -> {
                        m.isChecked = !m.isChecked
                        webView.apply {
                            if (this == null) {
                                m.isChecked = false
                                return@apply
                            }

                            configureDesktopMode(this, m.isChecked)
                            this.reload()
                        }
                    }
                    else -> {}
                }
                true
            }

            preferences = PreferenceManager.getDefaultSharedPreferences(this@WebViewActivity)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    webView = view
                    super.onPageFinished(view, url)
                    runCatching {
                        toolbar.title = view?.title ?: ""
                        cookies = cookieManager.getCookie(view?.url)
                    }
                }
            }

            toolbar.setNavigationOnClickListener {
                navigateBackInWebViewOrFinish()
            }

            generateBtn.setOnClickListener {
                generateBtn.isEnabled = false
                lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) {
                            val content = cookiesViewModel.getCookiesFromDB(url).getOrThrow()
                            cookiesViewModel.acquireAndProject(
                                url = url,
                                description = description,
                                content = content,
                                requestId = acquisitionRequestId,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        CookieProjectionCoordinator.AcquisitionOutcome.Failed(
                            CookieProjectionCoordinator.Failure.MUTATION_FAILED,
                        )
                    }

                    when (result) {
                        is CookieProjectionCoordinator.AcquisitionOutcome.Ready -> {
                            val data = Intent()
                                .putExtra(CookieAcquisitionHandoff.EXTRA_REQUEST_ID, result.requestId)
                                .putExtra(
                                    CookieAcquisitionHandoff.EXTRA_PROJECTION_GENERATION,
                                    result.projectionGeneration,
                                )
                            setResult(RESULT_OK, data)
                            finish()
                        }
                        is CookieProjectionCoordinator.AcquisitionOutcome.Failed -> {
                            generateBtn.isEnabled = true
                            Toast.makeText(
                                this@WebViewActivity,
                                "Cookies could not be prepared. Retry after the page finishes loading.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

            cookieManager = CookieManager.getInstance()

            if (savedInstanceState == null) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }

            webViewCompose.apply {
                setContent { WebViewView(incognito, webViewClient, url) }
            }
        }

    }

    private fun configureDesktopMode(webView: WebView, desktop: Boolean) {
        webView.settings.apply {
            if (desktop) {
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = WebSettings.getDefaultUserAgent(webView.context)
                useWideViewPort = false
                loadWithOverviewMode = false
            }
        }
    }

    private fun navigateBackInWebViewOrFinish() {
        val currentWebView = webView
        if (currentWebView != null && currentWebView.canGoBack()) {
            currentWebView.goBack()
            return
        }
        onBackPressedDispatcher.onBackPressed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewView(
        incognito: Boolean,
        webViewClient: WebViewClient,
        url: String
    ) {
        val context = LocalContext.current
        val cookieManager = remember { CookieManager.getInstance() }

        val webView = remember {
            WebView(context).apply {
                settings.run {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true

                    if (!incognito) {
                        cacheMode = WebSettings.LOAD_DEFAULT
                        domStorageEnabled = true
                        setGeolocationEnabled(true)
                    } else {
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        domStorageEnabled = false
                        setGeolocationEnabled(false)
                        WebStorage.getInstance().deleteAllData()

                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }

                    if (Build.VERSION.SDK_INT >= 26) {
                        safeBrowsingEnabled = true
                    }
                }

                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                this.webViewClient = webViewClient
                this.webChromeClient = object : WebChromeClient() {}
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            AndroidView(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                factory = { webView },
                update = {
                    if (it.url != url) {
                        it.loadUrl(url)
                    }
                }
            )
        }
    }


    companion object {
        const val TAG = "WebViewActivity"
    }

    data class CookieItem(
        val domain: String = "",
        val name: String = "",
        val value: String = "",
        val includeSubdomains: Boolean = true,
        val path: String = "/",
        val secure: Boolean = true,
        val expiry: Long = 0L,
    ) {
        constructor(
            url: String,
            name: String,
            value: String
        ) : this(domain = url.replace(Regex("""http(s)?://(\w*(www|m|account|sso))?|/.*"""), ""), name = name, value = value)

        fun toNetscapeFormat(): String {
            val stringList = listOf(domain,
                includeSubdomains.toString().uppercase(),
                path,
                secure.toString().uppercase(),
                expiry.toString(),
                name,
                value)

            val builder = StringBuilder(stringList.first())

            for (s in stringList.subList(1, stringList.size)) {
                if (s.isNotEmpty()) {
                    if (builder.isNotEmpty())
                        builder.append("\u0009")
                    builder.append(s)
                }
            }
            return builder.toString()
        }
    }

}
