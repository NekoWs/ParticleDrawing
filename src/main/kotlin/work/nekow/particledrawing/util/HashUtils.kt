package work.nekow.particledrawing.util

import java.security.MessageDigest

/**
 * 文件哈希工具。
 */
object HashUtils {

    /** 计算字节数组的 SHA-1，返回小写 hex。 */
    @JvmStatic
    fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF]).append(HEX_CHARS[b.toInt() and 0xF])
        return sb.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
