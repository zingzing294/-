package com.example.txtreader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBackup: String? = null

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_SAVE = 1002
        // 앱 안의 파일을 정식 https 출처로 띄우는 주소. 실제로 인터넷에 나가지 않는다.
        private const val START = "https://appassets.androidplatform.net/assets/index.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage
            databaseEnabled = true
            allowFileAccess = false           // 자산 로더를 쓰므로 필요 없다
            allowContentAccess = true
            textZoom = 100                    // 기기 글꼴 크기와 앱 글자 크기가 겹쳐 커지는 것 방지
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                // 형식을 좁히면 파일 관리자가 txt를 숨겨버리는 기기가 많아 전부 열어둔다
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "txt 파일 고르기"), REQ_PICK)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    toast("파일을 고를 수 있는 앱이 없습니다")
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.loadUrl(START)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_PICK -> {
                val cb = fileCallback
                fileCallback = null
                if (cb == null) return
                if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            REQ_SAVE -> {
                val json = pendingBackup
                pendingBackup = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null || json == null) return
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    toast("백업 파일을 저장했습니다")
                } catch (e: Exception) {
                    toast("백업을 저장하지 못했습니다")
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // 글을 열 때 웹 화면이 방문 기록을 남기므로, 뒤로가기는 자연히 서재로 돌아간다
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (this::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    inner class Bridge {

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun setBarColor(hex: String, lightBackground: Boolean) = runOnUiThread {
            try {
                window.statusBarColor = Color.parseColor(hex.trim())
                val decor = window.decorView
                decor.systemUiVisibility = if (lightBackground)
                    decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else
                    decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } catch (e: Exception) { /* 색을 못 읽으면 그냥 둔다 */ }
        }

        @JavascriptInterface
        fun saveBackup(json: String, filename: String) {
            pendingBackup = json
            runOnUiThread {
                val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, filename)
                }
                try {
                    startActivityForResult(save, REQ_SAVE)
                } catch (e: Exception) {
                    pendingBackup = null
                    toast("저장할 위치를 고를 수 없습니다")
                }
            }
        }

        @JavascriptInterface
        fun toastFromWeb(msg: String) = toast(msg)
    }
}
