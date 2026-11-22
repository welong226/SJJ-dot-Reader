package com.sjianjun.reader.sync

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sjianjun.reader.App

/**
 * pCloud 同步的帳號設定，存在 EncryptedSharedPreferences（Android Keystore 加密）。
 *
 * 密碼絕對不要寫進 MMKV 明文、程式碼、日誌或任何檔案。
 * 使用者在手機 App 設定頁自己輸入帳號密碼。
 */
object PCloudCredentials {
    private const val PREFS_NAME = "pcloud_sync"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SERVER = "server"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"

    enum class Server(val baseUrl: String, val label: String) {
        US("https://webdav.pcloud.com", "美區"),
        EU("https://ewebdav.pcloud.com", "歐區")
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(App.app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            App.app,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

    var server: Server
        get() = runCatching { Server.valueOf(prefs.getString(KEY_SERVER, null) ?: "") }
            .getOrDefault(Server.US)
        set(value) {
            prefs.edit().putString(KEY_SERVER, value.name).apply()
        }

    /** 上次同步成功的時間（毫秒），0 表示尚未同步。 */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()
        }
}
