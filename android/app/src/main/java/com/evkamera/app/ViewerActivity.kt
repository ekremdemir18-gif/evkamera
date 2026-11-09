package com.evkamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/** İzleyici modu: kayıtlı kameraları listeler, QR okutur ve canlı izler (sayfa uygulamanın içinde açılır). */
class ViewerActivity : Activity() {

    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSharedPreferences("evk", Context.MODE_PRIVATE).edit().putString("role", "viewer").apply()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#111111"))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true          // eşleşen kameralar kalıcı olarak saklanır
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.addJavascriptInterface(Bridge(), "EvApp")
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsCamera || hasCamera()) request.grant(request.resources)
                    else {
                        pendingPermission = request
                        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
                    }
                }
            }

            // Videoyu tam ekran yapma
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullView?.let { onHideCustomView() }
                fullView = view
                fullCallback = callback
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                fullView?.let { (window.decorView as FrameLayout).removeView(it) }
                fullView = null
                fullCallback?.onCustomViewHidden()
                fullCallback = null
            }
        }
        setContentView(web)
        web.loadUrl(BuildConfig.VIEWER_URL + "#izle")
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val req = pendingPermission ?: return
        pendingPermission = null
        if (hasCamera()) req.grant(req.resources) else req.deny()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullView != null) {
            web.webChromeClient?.onHideCustomView()
            return
        }
        // Sayfa içinde bir önceki ekrana dön; zaten listedeysek uygulamadan çık
        web.evaluateJavascript("window.evBack ? window.evBack() : false") { r ->
            if (r != "true") finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        /** Sayfadaki "←" ile mod seçim ekranına dönülür */
        @JavascriptInterface
        fun home() = runOnUiThread {
            getSharedPreferences("evk", Context.MODE_PRIVATE).edit().remove("role").apply()
            startActivity(Intent(this@ViewerActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
