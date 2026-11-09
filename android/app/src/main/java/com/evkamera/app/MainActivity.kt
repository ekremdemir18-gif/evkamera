package com.evkamera.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private val viewerUrl = "https://ekremdemir18-gif.github.io/evkamera/"
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var switchBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var qrView: ImageView
    private lateinit var qrHint: TextView

    private val accent = Color.parseColor("#F7B54A")
    private val card = Color.parseColor("#1D1D1F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#111111")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#111111"))
        }

        root.addView(text("EvKamera", 28f, Color.WHITE, bold = true))
        root.addView(text("Bu telefon KAMERA", 16f, Color.parseColor("#9A9AA0")).apply { setPadding(0, 0, 0, dp(16)) })

        statusText = text("", 17f, Color.WHITE).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(card)
        }
        root.addView(statusText, lp(bottom = 16))

        startBtn = button("") { toggle() }
        root.addView(startBtn, lp(bottom = 10))

        switchBtn = button("🔄  Ön / arka kamera") {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_SWITCH))
        }.apply { setBackgroundColor(card); setTextColor(Color.WHITE) }
        root.addView(switchBtn, lp(bottom = 10))

        batteryBtn = button("🔋  Pil kısıtlamasını kaldır (önerilir)") { askBatteryExemption() }
            .apply { setBackgroundColor(card); setTextColor(Color.WHITE) }
        root.addView(batteryBtn, lp(bottom = 20))

        qrHint = text("İzleyici telefonda $viewerUrl adresini aç, \"İZLEYİCİ olsun → QR kod okut\" de ve bu kodu okut:", 14f, Color.parseColor("#9A9AA0"))
        root.addView(qrHint, lp(bottom = 12))

        qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageBitmap(makeQr(viewerUrl + "#watch=" + CameraService.cameraId(this@MainActivity), 600))
            adjustViewBounds = true
        }
        root.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })

        root.addView(text(
            "• Yayın başlayınca uygulamayı kapatabilir, ekranı kilitleyebilirsin; kamera arka planda çalışmaya devam eder.\n" +
            "• Bildirim çubuğunda sessiz bir \"EvKamera açık\" bildirimi durur, oradan durdurabilirsin.\n" +
            "• Telefonu şarja takılı bırak.", 14f, Color.parseColor("#9A9AA0")))

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#111111")); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        CameraService.listener = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        CameraService.listener = null
    }

    private fun render() {
        val on = CameraService.running
        statusText.text = (if (on) "🔴  " else "⚪  ") + CameraService.status
        startBtn.text = if (on) "■  Kamerayı durdur" else "▶  Kamerayı başlat"
        startBtn.setBackgroundColor(if (on) Color.parseColor("#3A3A3C") else accent)
        startBtn.setTextColor(if (on) Color.WHITE else Color.BLACK)
        switchBtn.visibility = if (on) View.VISIBLE else View.GONE
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryBtn.visibility = if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) View.VISIBLE else View.GONE
    }

    // ---------------- Başlat / durdur ----------------

    private fun toggle() {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
            statusText.postDelayed({ render() }, 300)
            return
        }
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(missing.toTypedArray(), 1)
        else startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (ok) startCamera()
        else {
            statusText.text = "⚠️  Kamera ve mikrofon izni gerekli. Ayarlar → Uygulamalar → EvKamera → İzinler bölümünden açabilirsin."
        }
    }

    private fun startCamera() {
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        statusText.postDelayed({ render() }, 300)
    }

    private fun askBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    // ---------------- Yardımcılar ----------------

    private fun makeQr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(bottom) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        textSize = 16f
        isAllCaps = false
        setBackgroundColor(accent)
        setTextColor(Color.BLACK)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }
}
