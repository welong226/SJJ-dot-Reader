package com.sjianjun.reader.sync

import com.sjianjun.reader.repository.DbFactory.db
import com.sjianjun.reader.utils.fromJson
import com.sjianjun.reader.utils.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * pCloud WebDAV 手動同步：只同步書架、書源、閱讀進度。
 *
 * 同步檔是 pCloud 上 /SJJ-Reader/sjj-sync.json 一個 JSON。
 * 上傳：把本地三張表匯出成 JSON，用 PUT 覆蓋雲端檔案。
 * 下載：把雲端檔案 GET 下來，逐筆合併進本地資料庫。
 *
 * 合併規則：
 * - Book：主鍵 id（= url），雲端那份直接覆蓋，沒有的就新增。
 * - BookSource：主鍵 id（= "group:name"），version 新的贏。
 * - ReadingRecord：主鍵 bookTitle，updateTime 新的贏。
 *
 * 刪除不同步：本地刪掉的書，下次下載會回來。
 * 章節快取（ChapterContent）不同步，換裝置後第一次打開會重新抓取。
 */
object PCloudSyncManager {
    const val REMOTE_DIR = "/SJJ-Reader"
    const val REMOTE_FILE = "$REMOTE_DIR/sjj-sync.json"

    data class SyncStats(val books: Int, val sources: Int, val records: Int)

    private fun client(): WebDavClient {
        val email = PCloudCredentials.email?.trim().orEmpty()
        val password = PCloudCredentials.password.orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            throw WebDavException("請先在設定頁填入 pCloud 帳號與密碼")
        }
        return WebDavClient(PCloudCredentials.server.baseUrl, email, password)
    }

    /** 上傳：匯出本地書架、書源、閱讀進度，覆蓋雲端同步檔。 */
    suspend fun upload(): SyncStats = withContext(Dispatchers.IO) {
        val books = db.bookDao().getAllBook().first()
        val sources = db.bookSourceDao().getAllBookSource()
        val records = db.readingRecordDao().getAllReadingRecord().first()
        val file = SjjSyncFile(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            books = books,
            sources = sources,
            records = records
        )
        val json = gson.toJson(file)
        val webDav = client()
        webDav.ensureDirectory(REMOTE_DIR)
        webDav.upload(REMOTE_FILE, json.toByteArray(Charsets.UTF_8))
        PCloudCredentials.lastSyncAt = System.currentTimeMillis()
        SyncStats(books.size, sources.size, records.size)
    }

    /** 下載：取回雲端同步檔，逐筆合併進本地資料庫。 */
    suspend fun download(): SyncStats = withContext(Dispatchers.IO) {
        val bytes = client().download(REMOTE_FILE)
        val file = gson.fromJson<SjjSyncFile>(String(bytes, Charsets.UTF_8))
            ?: throw WebDavException("同步檔解析失敗")
        if (file.version != 1) {
            throw WebDavException("不支援的同步檔版本：${file.version}")
        }
        val stats = merge(file)
        PCloudCredentials.lastSyncAt = System.currentTimeMillis()
        stats
    }

    /**
     * 逐筆合併，整個合併在一個 transaction 裡做。
     * 書架 UI 是用 Flow 觀察資料庫，合併完會自動刷新；
     * 正在閱讀的書，進度以下次開書為準，不做即時跳轉。
     */
    private fun merge(file: SjjSyncFile): SyncStats {
        var booksChanged = 0
        var sourcesChanged = 0
        var recordsChanged = 0
        db.runInTransaction {
            val bookDao = db.bookDao()
            val sourceDao = db.bookSourceDao()
            val recordDao = db.readingRecordDao()
            for (book in file.books) {
                val local = bookDao.getBookById(book.id)
                if (local == null) {
                    bookDao.insertBook(book)
                    booksChanged++
                } else if (local != book) {
                    bookDao.updateBook(book)
                    booksChanged++
                }
            }
            for (source in file.sources) {
                val local = sourceDao.getBookSourceById(source.id)
                if (local == null) {
                    sourceDao.insertBookSource(listOf(source))
                    sourcesChanged++
                } else if (source.version > local.version) {
                    sourceDao.insertBookSource(listOf(source))
                    sourcesChanged++
                }
            }
            for (record in file.records) {
                val local = recordDao.getReadingRecordSync(record.bookTitle)
                if (local == null) {
                    recordDao.insertReadingRecord(record)
                    recordsChanged++
                } else if (record.updateTime > local.updateTime) {
                    recordDao.insertReadingRecord(record)
                    recordsChanged++
                }
            }
        }
        return SyncStats(booksChanged, sourcesChanged, recordsChanged)
    }
}
