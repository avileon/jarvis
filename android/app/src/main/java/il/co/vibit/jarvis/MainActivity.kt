package il.co.vibit.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * JARVIS station shell. The UI and all logic live on the server (loaded into this WebView), so
 * server/UI updates never require reinstalling the APK. The shell only provides: fullscreen kiosk,
 * microphone permission, keep-screen-on while charging, network-aware reconnect and boot start.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var pageFailed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var tapCount = 0
    private var lastTap = 0L

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val serverUrl: String get() = prefs.getString("url", BuildConfig.DEFAULT_URL) ?: BuildConfig.DEFAULT_URL

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateKeepScreenOn()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#030913"))
        setContentView(root)
        createWebView()
        requestMic()
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        updateKeepScreenOn()
        watchNetwork()
        if (prefs.getString("url", null) == null) promptServerUrl(first = true) else web.loadUrl(serverUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#030913"))
        root.removeAllViews()
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            userAgentString = "$userAgentString JarvisStation/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(Bridge(), "JarvisNative")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val sameOrigin = Uri.parse(serverUrl).host == request.origin.host
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (sameOrigin && wantsMic && hasMic()) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    else request.deny()
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Keep the kiosk on our server; anything else opens outside.
                if (req.url.host == Uri.parse(serverUrl).host) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!pageFailed && url.startsWith(serverUrl.trimEnd('/'))) restoreToken()
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) showOffline()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Renderer crashed or was killed (low memory on old tablets): rebuild the WebView.
                root.removeView(web)
                web.destroy()
                createWebView()
                web.loadUrl(serverUrl)
                return true
            }
        }
        // Hidden settings: 5 quick taps in the top-left corner.
        web.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && ev.x < 80 && ev.y < 80) {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTap < 600) tapCount + 1 else 1
                lastTap = now
                if (tapCount >= 5) { tapCount = 0; promptServerUrl(first = false) }
            }
            false
        }
    }

    private fun restoreToken() {
        val t = prefs.getString("token", null) ?: return
        val js = "(function(){try{if(!localStorage.getItem('jarvis.deviceToken')){localStorage.setItem('jarvis.deviceToken'," +
            JSONObject.quote(t) + ");location.reload();}}catch(e){}})()"
        web.evaluateJavascript(js, null)
    }

    private fun showOffline() {
        pageFailed = true
        val html = """
            <html dir="rtl"><body style="margin:0;background:#030913;color:#9cc4d3;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column">
            <div style="letter-spacing:.34em;color:#72dfff;direction:ltr">J A R V I S</div>
            <p>אין חיבור לשרת. מנסה שוב…</p></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 10_000)
    }

    private val retry = Runnable {
        pageFailed = false
        web.loadUrl(serverUrl)
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onUp = {
            runOnUiThread {
                if (pageFailed) { handler.removeCallbacks(retry); retry.run() }
                else web.evaluateJavascript("window.jarvisOnNetwork&&window.jarvisOnNetwork(true)", null)
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { onUp() }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    if (cm.activeNetworkInfo?.isConnected == true) onUp()
                }
            }, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        }
    }

    private fun updateKeepScreenOn() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val always = prefs.getBoolean("alwaysOn", false)
        if (plugged || always) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hasMic() = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        if (!hasMic() && Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1 && hasMic()) web.reload()
    }

    private fun promptServerUrl(first: Boolean) {
        val input = EditText(this).apply { setText(serverUrl); setSingleLine() }
        val b = AlertDialog.Builder(this)
            .setTitle("כתובת שרת JARVIS")
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                var u = input.text.toString().trim()
                if (!u.startsWith("http")) u = "https://$u"
                if (!u.endsWith("/")) u += "/"
                prefs.edit().putString("url", u).apply()
                web.loadUrl(u)
            }
        if (!first) {
            b.setNeutralButton(if (prefs.getBoolean("alwaysOn", false)) "מסך: לפי טעינה" else "מסך: תמיד דולק") { _, _ ->
                prefs.edit().putBoolean("alwaysOn", !prefs.getBoolean("alwaysOn", false)).apply()
                updateKeepScreenOn()
            }
            b.setNegativeButton("הפעלה אוטומטית…") { _, _ -> openAutostartSettings() }
        }
        b.setCancelable(!first).show()
    }

    /** Android 10+ blocks background activity starts; "display over other apps" lets BootReceiver open us. */
    private fun openAutostartSettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        updateKeepScreenOn()
    }

    @Deprecated("kiosk")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(powerReceiver) }
        networkCallback?.let { runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun info(): String = JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("app", BuildConfig.VERSION_NAME)
        }.toString()

        @JavascriptInterface
        fun saveToken(token: String) {
            prefs.edit().apply { if (token.isEmpty()) remove("token") else putString("token", token) }.apply()
        }
    }
}
