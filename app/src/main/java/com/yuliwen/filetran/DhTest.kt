package com.yuliwen.filetran

import android.util.Log
import java.security.KeyPairGenerator
import java.util.Base64

object DhTest {
    private const val TAG = "DhTest"

    // RFC 7748 Section 6.1 官方测试向量
    // Alice 私钥（已 clamp）
    private val RFC_ALICE_PRIV = byteArrayOf(
        0x77.toByte(),0x07.toByte(),0x6d.toByte(),0x0a.toByte(),0x73.toByte(),0x18.toByte(),0xa5.toByte(),0x7d.toByte(),
        0x3c.toByte(),0x16.toByte(),0xc1.toByte(),0x72.toByte(),0x51.toByte(),0xb2.toByte(),0x66.toByte(),0x45.toByte(),
        0xdf.toByte(),0x0b.toByte(),0xfc.toByte(),0xb6.toByte(),0x40.toByte(),0x7a.toByte(),0x8d.toByte(),0xe7.toByte(),
        0xf7.toByte(),0x0b.toByte(),0x3a.toByte(),0x26.toByte(),0x2e.toByte(),0x35.toByte(),0x10.toByte(),0x68.toByte()
    )
    // Bob 私钥（已 clamp）
    private val RFC_BOB_PRIV = byteArrayOf(
        0x5d.toByte(),0xab.toByte(),0x08.toByte(),0x7e.toByte(),0x62.toByte(),0x4a.toByte(),0x8a.toByte(),0x4b.toByte(),
        0x79.toByte(),0xe1.toByte(),0x7f.toByte(),0x8b.toByte(),0x83.toByte(),0x80.toByte(),0x0e.toByte(),0xe6.toByte(),
        0x6f.toByte(),0x3b.toByte(),0xb1.toByte(),0x29.toByte(),0x26.toByte(),0x18.toByte(),0xb6.toByte(),0xfd.toByte(),
        0x1c.toByte(),0x26.toByte(),0x8f.toByte(),0xb6.toByte(),0x86.toByte(),0x77.toByte(),0x05.toByte(),0x2b.toByte()
    )
    // 期望：Alice 公钥
    private val RFC_ALICE_PUB = byteArrayOf(
        0x85.toByte(),0x20.toByte(),0xf0.toByte(),0x09.toByte(),0x89.toByte(),0x30.toByte(),0xa7.toByte(),0x54.toByte(),
        0x74.toByte(),0x8b.toByte(),0x7d.toByte(),0xdc.toByte(),0xb4.toByte(),0x3e.toByte(),0xf7.toByte(),0x5a.toByte(),
        0x0d.toByte(),0xbf.toByte(),0x3a.toByte(),0x0d.toByte(),0x26.toByte(),0x38.toByte(),0x1a.toByte(),0xf4.toByte(),
        0xeb.toByte(),0xa4.toByte(),0xa9.toByte(),0x8e.toByte(),0xaa.toByte(),0x9b.toByte(),0x4e.toByte(),0x6a.toByte()
    )
    // 期望：Bob 公钥
    private val RFC_BOB_PUB = byteArrayOf(
        0xde.toByte(),0x9e.toByte(),0xdb.toByte(),0x7d.toByte(),0x7b.toByte(),0x7d.toByte(),0xc1.toByte(),0xb4.toByte(),
        0xd3.toByte(),0x5b.toByte(),0x61.toByte(),0xc2.toByte(),0xec.toByte(),0xe4.toByte(),0x35.toByte(),0x37.toByte(),
        0x3f.toByte(),0x83.toByte(),0x43.toByte(),0xc8.toByte(),0x5b.toByte(),0x78.toByte(),0x67.toByte(),0x4d.toByte(),
        0xad.toByte(),0xfc.toByte(),0x7e.toByte(),0x14.toByte(),0x6f.toByte(),0x88.toByte(),0x2b.toByte(),0x4f.toByte()
    )
    // 期望：共享密钥
    private val RFC_SHARED = byteArrayOf(
        0x4a.toByte(),0x5d.toByte(),0x9d.toByte(),0x5b.toByte(),0xa4.toByte(),0xce.toByte(),0x2d.toByte(),0xe1.toByte(),
        0x72.toByte(),0x8e.toByte(),0x3b.toByte(),0xf4.toByte(),0x80.toByte(),0x35.toByte(),0x0f.toByte(),0x25.toByte(),
        0xe0.toByte(),0x7e.toByte(),0x21.toByte(),0xc9.toByte(),0x47.toByte(),0xd1.toByte(),0x9e.toByte(),0x33.toByte(),
        0x76.toByte(),0xf0.toByte(),0x9b.toByte(),0x3c.toByte(),0x1e.toByte(),0x16.toByte(),0x17.toByte(),0x42.toByte()
    )

    private const val CLIENT_PRIV = "8Dyybis2wC2T8d/WShbSTlDT7hRJrRNKJcQ6Pe1O90g="
    private const val SERVER_PUB  = "orVrzcq//2E5hoZXU6jjKhaurWckyPLCcgGEiFrzdQ0="
    private const val SERVER_PRIV = "iJZ7PomgdVtfo/Kq4au3WMN+9fEHHDbWv6C5/6ugPH8="
    private const val CLIENT_PUB  = "kToXB1v6QDFc+cnX05G6BQZ1sfuVuT0OcOl5jtLsNnM="

    fun verifyConfigKeys() {
        // 验证用户提供的配置公私钥是否匹配
        val serverPriv = "8FjAI56E1HxOWOvxR9wXjBY8qEvC3w5S+PiDa9i6CEI="
        val serverPubInClientConf = "N7pSAWiy8CmDfyovteu/+I/4Zmbf6a2SxUqTN5IJ9B4="  // 客户端配置里填的服务端公钥
        val clientPriv = "KJDF2nzMLm3a4UPZWgLyUPWVvDDYxxwoXl0Dag+nMHs="
        val clientPubInServerConf = "E7I2sK0U75mg1gzx5/jkFuQd9zG6BhW8GMCTR0Stfjs="  // 服务端配置里填的客户端公钥

        val computedServerPub = WireGuardKeyUtil.publicKeyFromPrivate(serverPriv)
        val computedClientPub = WireGuardKeyUtil.publicKeyFromPrivate(clientPriv)
        Log.e(TAG, "=== CONFIG KEY VERIFICATION ===")
        Log.e(TAG, "Server privKey -> computedPub : $computedServerPub")
        Log.e(TAG, "Server pub in client conf     : $serverPubInClientConf")
        Log.e(TAG, "Server pub MATCH: ${computedServerPub == serverPubInClientConf}")
        Log.e(TAG, "Client privKey -> computedPub : $computedClientPub")
        Log.e(TAG, "Client pub in server conf     : $clientPubInServerConf")
        Log.e(TAG, "Client pub MATCH: ${computedClientPub == clientPubInServerConf}")

        // 验证双端 DH 共享密钥是否一致
        val sPrivBytes = Base64.getDecoder().decode(serverPriv)
        val cPrivBytes = Base64.getDecoder().decode(clientPriv)
        val sPubBytes  = Base64.getDecoder().decode(computedServerPub)
        val cPubBytes  = Base64.getDecoder().decode(computedClientPub)
        val sharedCS = Curve25519.dh(cPrivBytes, sPubBytes)
        val sharedSC = Curve25519.dh(sPrivBytes, cPubBytes)
        Log.e(TAG, "DH shared match (c->s == s->c): ${sharedCS.contentEquals(sharedSC)}")
        Log.e(TAG, "================================")
    }

    fun run() {
        verifyConfigKeys()
        // RFC 7748 §6.1 向量验证
        // 注意：RFC_ALICE_PUB / RFC_BOB_PUB 是独立给出的已知公钥，
        // 不能通过 publicKey(RFC_ALICE_PRIV) 推导（私钥对应的基点乘法结果不同）
        // 正确验证方式：用各自私钥对对方公钥做 DH，结果应等于 RFC_SHARED
        val shared1  = Curve25519.dh(RFC_ALICE_PRIV, RFC_BOB_PUB)
        val shared2  = Curve25519.dh(RFC_BOB_PRIV, RFC_ALICE_PUB)
        Log.e(TAG, "RFC shared match:     ${shared1.contentEquals(RFC_SHARED)}")
        Log.e(TAG, "RFC shared symmetric: ${shared1.contentEquals(shared2)}")
        Log.e(TAG, "Computed shared[0..7]: ${shared1.take(8).map { it.toInt() and 0xFF }}")
        Log.e(TAG, "Expected shared[0..7]: ${RFC_SHARED.take(8).map { it.toInt() and 0xFF }}")

        // 项目自身密钥对验证
        val cPriv = Base64.getDecoder().decode(CLIENT_PRIV.trim())
        val sPub  = Base64.getDecoder().decode(SERVER_PUB.trim())
        val sPriv = Base64.getDecoder().decode(SERVER_PRIV.trim())
        val cPub  = Base64.getDecoder().decode(CLIENT_PUB.trim())
        val clientDH = Curve25519.dh(cPriv, sPub)
        val serverDH = Curve25519.dh(sPriv, cPub)
        Log.e(TAG, "Project DH match: ${clientDH.contentEquals(serverDH)}")
        Log.e(TAG, "Project clientPub match: ${Curve25519.publicKey(cPriv).contentEquals(cPub)}")
    }
}
