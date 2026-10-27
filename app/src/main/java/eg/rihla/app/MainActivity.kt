package eg.rihla.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.URLDecoder
import java.util.Locale

/**
 * رحلة المعرفة — غلاف أندرويد للتطبيق.
 * الصفحة كاملة داخل assets/www/index.html، وكل البيانات تبقى في تخزين WebView المحلي على الهاتف.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var tts: TextToSpeech? = null
    private var ttsArabicReady = false
    private var lastBackPress = 0L

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true           // localStorage: حفظ التقدم
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                picker.launch("*/*")
                return true
            }
        }
        // روابط data: (تصدير النسخة الاحتياطية) تُحفظ في مجلد التنزيلات
        web.setDownloadListener { url, _, _, mimetype, _ ->
            if (url.startsWith("data:")) saveDataUrl(url, mimetype)
        }
        web.addJavascriptInterface(Bridge(), "Android")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar"))
                ttsArabicReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(0.9f)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("(function(){try{return window.__rihlaBack&&window.__rihlaBack()?'1':'0'}catch(e){return '0'}})()") { handled ->
                if (handled != "\"1\"") {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPress < 2000) finish()
                    else { lastBackPress = now; Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show() }
                }
            }
        }

        if (savedInstanceState == null) web.loadUrl("file:///android_asset/www/index.html")
        else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun saveDataUrl(url: String, mimetype: String?) {
        try {
            val comma = url.indexOf(',')
            val header = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val bytes = if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                        else URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            val mime = mimetype?.takeIf { it.isNotBlank() } ?: header.substringBefore(';').ifBlank { "application/octet-stream" }
            val ext = if (mime.contains("json")) "json" else "bin"
            val name = "rihla-backup-" + System.currentTimeMillis() + "." + ext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                java.io.File(dir, name).writeBytes(bytes)
            }
            Toast.makeText(this, R.string.saved_to_downloads, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** الجسر الذي تناديه الصفحة: window.Android.* */
    inner class Bridge {
        @JavascriptInterface
        fun hasArabicVoice(): Boolean = ttsArabicReady

        @JavascriptInterface
        fun speak(text: String) {
            if (!ttsArabicReady) return
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rihla")
        }

        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                val adapter = web.createPrintDocumentAdapter(getString(R.string.print_job))
                pm.print(getString(R.string.print_job), adapter, PrintAttributes.Builder().build())
            }
        }
    }
}
