package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.Track
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SubDecryptor(private val client: OkHttpClient, private val headers: Headers, private val baseUrl: String) {
    suspend fun getSubtitles(subUrl: String, subLang: String): Track {
        val subHeaders = headers.newBuilder().apply {
            set("Accept", "application/json, text/plain, */*")
            set("Origin", baseUrl)
            set("Referer", "$baseUrl/")
        }.build()

        val subtitleData = client.get(subUrl, subHeaders).bodyString()

        val chunks = subtitleData.split(CHUNK_REGEX)
            .filter(String::isNotBlank)
            .map(String::trim)

        val workingPair = findWorkingKeyIv(chunks)

        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.lines()
            val text = parts.drop(1)
            val d = if (workingPair != null) {
                text.joinToString("\n") { line ->
                    runCatching { decryptWithKeyIv(workingPair.first, workingPair.second, line) }.getOrDefault("")
                }
            } else {
                ""
            }

            "${index + 1}\n${parts.first()}\n$d"
        }.joinToString("\n\n")

        val file = File.createTempFile("subs", ".srt")
            .also(File::deleteOnExit)

        file.writeText(decrypted)
        val uri = Uri.fromFile(file)

        return Track(uri.toString(), subLang)
    }

    private fun findWorkingKeyIv(chunks: List<String>): Pair<ByteArray, ByteArray>? {
        for (chunk in chunks) {
            val lines = chunk.lines().drop(1)
            for (line in lines) {
                if (line.isNotBlank()) {
                    for (pair in KEY_IV_PAIRS) {
                        try {
                            decryptWithKeyIv(pair.first, pair.second, line)
                            return pair
                        } catch (_: Exception) {
                        }
                    }
                    return null
                }
            }
        }
        return null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedB64: String): String {
        if (encryptedB64.isBlank()) return ""
        val encryptedBytes = Base64.decode(encryptedB64)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    companion object {
        private val CHUNK_REGEX = Regex("^\\d+$", RegexOption.MULTILINE)

        private const val KEY = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"

        private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)

        private fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }

        private val KEY_IV_PAIRS = listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
        )
    }
}
