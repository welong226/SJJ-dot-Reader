package com.sjianjun.reader.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 操作失敗時拋出的例外，message 已經是可直接顯示給使用者的文字。
 */
class WebDavException(message: String, val statusCode: Int = -1) : IOException(message)

/**
 * 最小的 WebDAV 客戶端，只用到 MKCOL、PUT、GET。
 *
 * 注意：不要共用 Http.kt 的 okClient，那個 client 信任全部憑證；
 * 同步要傳帳號密碼，必須用預設憑證驗證的 client。
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", Credentials.basic(username, password))

    private fun base(path: String) = baseUrl.trimEnd('/') + path

    /**
     * 確保遠端目錄存在。目錄已存在時 pCloud 回 405，視為成功。
     */
    fun ensureDirectory(path: String) {
        val request = requestBuilder(base(path)).method("MKCOL", null).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 405) return
            throwException(response.code, "建立目錄失敗")
        }
    }

    /** 上傳檔案，覆蓋同名檔案。 */
    fun upload(path: String, bytes: ByteArray) {
        val request = requestBuilder(base(path))
            .put(bytes.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return
            throwException(response.code, "上傳失敗")
        }
    }

    /** 下載檔案。 */
    fun download(path: String): ByteArray {
        val request = requestBuilder(base(path)).get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body?.bytes() ?: throw WebDavException("下載失敗：空回應")
            }
            if (response.code == 404) throw WebDavException("雲端還沒有同步檔，請先上傳", 404)
            throwException(response.code, "下載失敗")
        }
    }

    private fun throwException(code: Int, action: String): Nothing {
        if (code == 401) throw WebDavException("帳號或密碼錯誤", 401)
        throw WebDavException("$action：HTTP $code", code)
    }
}
