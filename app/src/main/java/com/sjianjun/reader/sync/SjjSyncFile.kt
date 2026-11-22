package com.sjianjun.reader.sync

import com.sjianjun.reader.bean.Book
import com.sjianjun.reader.bean.BookSource
import com.sjianjun.reader.bean.ReadingRecord

/**
 * pCloud WebDAV 同步檔 sjj-sync.json 的內容。
 *
 * 只同步書架、書源、閱讀進度。章節快取（ChapterContent）不同步，
 * 換裝置後第一次打開會重新抓取；刪除也不會同步，本地刪掉的書
 * 下次下載會回來。
 */
data class SjjSyncFile(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val books: List<Book> = emptyList(),
    val sources: List<BookSource> = emptyList(),
    val records: List<ReadingRecord> = emptyList()
)
