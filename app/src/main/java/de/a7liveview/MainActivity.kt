package de.a7liveview

import android.app.Activity
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val analyzer = FrameAnalyzer()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val workerStarted = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var generation = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var surface: LiveViewSurface
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onStop() {
        super.onStop()
        if (running) disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    // --- UI ---------------------------------------------------------------

    private fun buildUi() {
        surface = LiveViewSurface(this)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = "Mit dem Kamera-WLAN verbinden, dann „Verbinden“ tippen"
        }

        connectButton = Button(this).apply {
            text = "Verbinden"
            setOnClickListener { if (running) disconnect() else connect() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(connectButton)
            addView(toggle("Peaking", analyzer.peaking) { analyzer.peaking = it })
            addView(toggle("Zebra", analyzer.zebra) { analyzer.zebra = it })
            addView(toggle("Histogramm", analyzer.histogram) { analyzer.histogram = it })
        }

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(
            statusView,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        setContentView(root)
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            isChecked = initial
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(text: String) = runOnUiThread { statusView.text = text }

    // --- Verbindung -------------------------------------------------------

    private fun connect() {
        releaseNetwork()
        running = true
        workerStarted.set(false)
        val gen = ++generation
        connectButton.text = "Trennen"
        setStatus("Suche WLAN …")

        // Der Datenverkehr muss über das Kamera-WLAN laufen, auch wenn es kein Internet hat.
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                if (gen == generation && workerStarted.compareAndSet(false, true)) {
                    startWorker(gen)
                }
            }

            override fun onUnavailable() {
                if (gen != generation) return
                running = false
                setStatus("Kein WLAN gefunden. Mit dem Kamera-WLAN verbinden.")
                runOnUiThread { connectButton.text = "Verbinden" }
            }
        }
        networkCallback = callback
        cm.requestNetwork(request, callback, 8000)
    }

    private fun disconnect() {
        running = false
        generation++
        releaseNetwork()
        connectButton.text = "Verbinden"
        statusView.text = "Getrennt"
    }

    private fun releaseNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.bindProcessToNetwork(null)
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        networkCallback = null
    }

    private fun startWorker(gen: Int) {
        Thread {
            var api: SonyCameraApi? = null
            try {
                setStatus("Suche Kamera …")
                val endpoint = SonyCameraApi.discover() ?: SonyCameraApi.DEFAULT_ENDPOINT
                api = SonyCameraApi(endpoint)

                setStatus("Starte Live-View …")
                val url = api.startLiveview()

                setStatus("Live")
                api.streamLiveview(url, { jpeg -> handleFrame(jpeg) }, { running && gen == generation })
            } catch (e: Exception) {
                if (running && gen == generation) {
                    setStatus("Fehler: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                try {
                    api?.stopLiveview()
                } catch (_: Exception) {
                }
                if (gen == generation) {
                    running = false
                    runOnUiThread {
                        releaseNetwork()
                        connectButton.text = "Verbinden"
                    }
                }
            }
        }.start()
    }

    // Verwirft Bilder, solange die Analyse noch läuft, damit die Latenz nicht wächst.
    private fun handleFrame(jpeg: ByteArray) {
        if (!busy.compareAndSet(false, true)) return
        analysisExecutor.execute {
            try {
                analyzer.analyze(jpeg)?.let { surface.show(it) }
            } catch (_: Exception) {
            } finally {
                busy.set(false)
            }
        }
    }
}
