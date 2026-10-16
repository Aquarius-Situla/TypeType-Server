package dev.typetype.server.services

import java.io.StringReader
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

internal object BiliBiliCookieRefreshCrypto {
    private const val PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
            "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
            "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
            "JNrRuoEUXpabUzGB8QIDAQAB"
    private val refreshCsrfPattern = Regex("[A-Za-z0-9_-]{1,256}")

    fun correspondPath(timestamp: Long): String {
        require(timestamp > 0)
        val key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return cipher.doFinal("refresh_$timestamp".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun refreshCsrf(html: String): String? {
        var insideRefreshToken = false
        var token: String? = null
        ParserDelegator().parse(StringReader(html), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
                insideRefreshToken = tag == HTML.Tag.DIV &&
                    attributes.getAttribute(HTML.Attribute.ID)?.toString() == "1-name"
            }

            override fun handleText(data: CharArray, position: Int) {
                if (insideRefreshToken && token == null) token = String(data).trim()
            }

            override fun handleEndTag(tag: HTML.Tag, position: Int) {
                if (tag == HTML.Tag.DIV) insideRefreshToken = false
            }
        }, true)
        return token?.takeIf(refreshCsrfPattern::matches)
    }
}
