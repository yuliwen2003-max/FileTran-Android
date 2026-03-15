package com.yuliwen.filetran

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.charset.Charset

class LocalSendProtocolTest {
    @Test
    fun parseJsonObject_decodesGb18030Alias() {
        val alias = "\u6D4B\u8BD5\u624B\u673A"
        val payload = """
            {"alias":"$alias","deviceModel":"Windows","fingerprint":"fp-gb18030","port":53317,"protocol":"http"}
        """.trimIndent().toByteArray(Charset.forName("GB18030"))

        val json = LocalSendProtocol.parseJsonObject(payload)

        assertNotNull(json)
        assertEquals(alias, json?.optString("alias"))
    }

    @Test
    fun parsePeer_keepsPlainChineseAlias() {
        val expectedAlias = "\u6D4B\u8BD5\u624B\u673A"
        val peer = LocalSendProtocol.parsePeer(
            json = JSONObject()
                .put("alias", expectedAlias)
                .put("deviceModel", "Pixel 9")
                .put("fingerprint", "fp-plain-zh")
                .put("port", 53317)
                .put("protocol", "http"),
            fallbackIp = "192.168.1.22"
        )

        requireNotNull(peer)
        assertEquals(expectedAlias, peer.alias)
        assertEquals("Pixel 9", peer.deviceModel)
    }

    @Test
    fun parsePeer_repairsUtf8MojibakeAlias() {
        val expectedAlias = "\u6D4B\u8BD5\u624B\u673A"
        val peer = LocalSendProtocol.parsePeer(
            json = JSONObject()
                .put("alias", utf8Mojibake(expectedAlias, java.nio.charset.Charset.forName("windows-1252")))
                .put("deviceModel", "Pixel 9")
                .put("fingerprint", "fp-1")
                .put("port", 53317)
                .put("protocol", "http"),
            fallbackIp = "192.168.1.23"
        )

        requireNotNull(peer)
        assertEquals(expectedAlias, peer.alias)
        assertEquals("Pixel 9", peer.deviceModel)
    }

    @Test
    fun parsePeer_repairsUtf8MojibakeAliasWithAccents() {
        val expectedAlias = "Fran\u00E7ois"
        val peer = LocalSendProtocol.parsePeer(
            json = JSONObject()
                .put("alias", utf8Mojibake(expectedAlias))
                .put("deviceModel", "Windows PC")
                .put("fingerprint", "fp-2")
                .put("port", 53317)
                .put("protocol", "http"),
            fallbackIp = "192.168.1.24"
        )

        requireNotNull(peer)
        assertEquals(expectedAlias, peer.alias)
        assertEquals("Windows PC", peer.deviceModel)
    }

    @Test
    fun parsePeer_usesDeviceModelWhenAliasIsUnknown() {
        val peer = LocalSendProtocol.parsePeer(
            json = JSONObject()
                .put("alias", "???")
                .put("deviceModel", "Xiaomi 15")
                .put("fingerprint", "fp-3")
                .put("port", 53317)
                .put("protocol", "http"),
            fallbackIp = "192.168.1.25"
        )

        requireNotNull(peer)
        assertEquals("Xiaomi 15", peer.alias)
        assertEquals("Xiaomi 15", peer.deviceModel)
    }

    @Test
    fun mergePeer_keepsBetterAliasWhenIncomingFallsBackToModel() {
        val merged = LocalSendProtocol.mergePeer(
            existing = LocalSendPeer(
                ip = "192.168.1.26",
                alias = "\u82F1\u4FCA\u7684\u5357\u74DC",
                deviceModel = "iPhone",
                deviceType = "mobile",
                fingerprint = "fp-merge-1",
                port = 53317,
                protocol = "http",
                download = false
            ),
            incoming = LocalSendPeer(
                ip = "192.168.1.26",
                alias = "iPhone",
                deviceModel = "iPhone",
                deviceType = "mobile",
                fingerprint = "fp-merge-1",
                port = 53317,
                protocol = "http",
                download = false
            )
        )

        assertEquals("\u82F1\u4FCA\u7684\u5357\u74DC", merged.alias)
        assertEquals("iPhone", merged.deviceModel)
    }

    @Test
    fun mergePeer_acceptsIncomingAliasWhenItIsBetter() {
        val merged = LocalSendProtocol.mergePeer(
            existing = LocalSendPeer(
                ip = "192.168.1.27",
                alias = "Windows",
                deviceModel = "Windows",
                deviceType = "desktop",
                fingerprint = "fp-merge-2",
                port = 53317,
                protocol = "http",
                download = false
            ),
            incoming = LocalSendPeer(
                ip = "192.168.1.27",
                alias = "\u6D4B\u8BD5\u53F0\u5F0F\u673A",
                deviceModel = "Windows",
                deviceType = "desktop",
                fingerprint = "fp-merge-2",
                port = 53317,
                protocol = "http",
                download = false
            )
        )

        assertEquals("\u6D4B\u8BD5\u53F0\u5F0F\u673A", merged.alias)
        assertEquals("Windows", merged.deviceModel)
    }

    @Test
    fun needsPeerDetailsRefresh_isTrueWhenAliasFallsBackToModel() {
        val peer = LocalSendPeer(
            ip = "192.168.1.28",
            alias = "iPhone",
            deviceModel = "iPhone",
            deviceType = "mobile",
            fingerprint = "fp-refresh-1",
            port = 53317,
            protocol = "http",
            download = false
        )

        assertEquals(true, LocalSendProtocol.needsPeerDetailsRefresh(peer))
    }

    @Test
    fun needsPeerDetailsRefresh_isFalseWhenAliasLooksReal() {
        val peer = LocalSendPeer(
            ip = "192.168.1.29",
            alias = "\u82F1\u4FCA\u7684\u5357\u74DC",
            deviceModel = "iPhone",
            deviceType = "mobile",
            fingerprint = "fp-refresh-2",
            port = 53317,
            protocol = "http",
            download = false
        )

        assertEquals(false, LocalSendProtocol.needsPeerDetailsRefresh(peer))
    }

    @Test
    fun classifyPrepareUploadFailure_mapsBusyResponse() {
        val error = LocalSendProtocol.classifyPrepareUploadFailure(
            statusCode = 409,
            responseText = "receiver_busy"
        )

        assertEquals("RECEIVER_BUSY", error.message)
    }

    @Test
    fun classifyPrepareUploadFailure_mapsAuthResponse() {
        val error = LocalSendProtocol.classifyPrepareUploadFailure(
            statusCode = 401,
            responseText = "unauthorized"
        )

        assertEquals("AUTH_REQUIRED", error.message)
    }

    private fun utf8Mojibake(text: String): String {
        return utf8Mojibake(text, Charsets.ISO_8859_1)
    }

    private fun utf8Mojibake(text: String, sourceCharset: java.nio.charset.Charset): String {
        return String(text.toByteArray(Charsets.UTF_8), sourceCharset)
    }
}
