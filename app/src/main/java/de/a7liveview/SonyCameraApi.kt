package de.a7liveview

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class ApiException(message: String) : Exception(message)

/**
 * Minimaler Client für die Sony Camera Remote API (HTTP/JSON-RPC über WLAN).
 * Die Kamera muss dafür die In-Camera-App "Smart Remote Control" laufen haben.
 */
class SonyCameraApi(private val endpoint: String) {

    private var requestId = 1

    private fun call(method: String, params: JSONArray = JSONArray()): JSONObject {
        val body = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", requestId++)
            .put("version", "1.0")
            .toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun availableApis(): Set<String> {
        val arr = call("getAvailableApiList").optJSONArray("result")?.optJSONArray(0)
            ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    /** Schaltet die Kamera in den Aufnahmemodus und liefert die Live-View-URL. */
    fun startLiveview(): String {
        var apis = availableApis()
        if ("startRecMode" in apis) {
            call("startRecMode")
            Thread.sleep(3000)
            apis = availableApis()
        }

        var tries = 0
        while ("startLiveview" !in apis && tries < 10) {
            Thread.sleep(1000)
            apis = availableApis()
            tries++
        }
        if ("startLiveview" !in apis) {
            throw ApiException(
                "Kamera bietet kein Live-View an. Läuft „Smart Remote Control“ auf der Kamera?"
            )
        }

        val res = call("startLiveview")
        if (res.has("error")) {
            throw ApiException("startLiveview: ${res.get("error")}")
        }
        return res.getJSONArray("result").getString(0)
    }

    fun stopLiveview() {
        call("stopLiveview")
    }

    /**
     * Liest den Live-View-Stream (JPEG-Frames in Sony-Paketen) und ruft [onFrame] je Bild auf.
     * Läuft, bis [isActive] false liefert oder die Verbindung abbricht.
     */
    fun streamLiveview(url: String, onFrame: (ByteArray) -> Unit, isActive: () -> Boolean) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10000
        }
        try {
            val input = DataInputStream(conn.inputStream.buffered())
            val common = ByteArray(8)
            val payloadHeader = ByteArray(128)

            while (isActive()) {
                input.readFully(common)
                if (common[0] != 0xFF.toByte()) throw ApiException("Ungültiges Live-View-Paket")
                val type = common[1].toInt() and 0xFF

                input.readFully(payloadHeader)
                val validStart = payloadHeader[0] == 0x24.toByte() &&
                    payloadHeader[1] == 0x35.toByte() &&
                    payloadHeader[2] == 0x68.toByte() &&
                    payloadHeader[3] == 0x79.toByte()
                if (!validStart) throw ApiException("Ungültiger Live-View-Header")

                val size = ((payloadHeader[4].toInt() and 0xFF) shl 16) or
                    ((payloadHeader[5].toInt() and 0xFF) shl 8) or
                    (payloadHeader[6].toInt() and 0xFF)
                val padding = payloadHeader[7].toInt() and 0xFF

                val data = ByteArray(size)
                input.readFully(data)
                if (padding > 0) input.readFully(ByteArray(padding))

                // Typ 0x01 = JPEG-Bild, 0x02 = Fokusrahmen-Infos (hier ignoriert)
                if (type == 0x01) onFrame(data)
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://192.168.122.1:8080/sony/camera"

        /** Sucht die Kamera per SSDP; liefert die Camera-Endpoint-URL oder null. */
        fun discover(timeoutMs: Int = 3000): String? {
            val msg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n"
                ).toByteArray()

            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.send(
                        DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900)
                    )
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val text = String(packet.data, 0, packet.length)
                        val location = Regex("(?im)^location:\\s*(\\S+)")
                            .find(text)?.groupValues?.get(1) ?: continue
                        val actionList = fetchActionListUrl(location) ?: continue
                        return actionList.trimEnd('/') + "/camera"
                    }
                }
            } catch (e: Exception) {
                // Discovery ist optional, es gibt einen Standard-Endpoint als Rückfall.
            }
            return null
        }

        private fun fetchActionListUrl(location: String): String? {
            val conn = (URL(location).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            return try {
                val xml = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("<(?:\\w+:)?X_ScalarWebAPI_ActionList_URL>\\s*([^<\\s]+)\\s*<")
                    .find(xml)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }
    }
}
