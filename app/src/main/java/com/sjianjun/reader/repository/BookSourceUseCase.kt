package com.sjianjun.reader.repository

import android.util.Base64
import android.widget.Toast
import androidx.core.net.toUri
import com.sjianjun.coroutine.withIo
import com.sjianjun.reader.App
import com.sjianjun.reader.bean.BookSource
import com.sjianjun.reader.bean.SourceUrl
import com.sjianjun.reader.http.http
import com.sjianjun.reader.utils.fromJson
import com.sjianjun.reader.utils.gson
import com.sjianjun.reader.utils.toast
import com.sjianjun.reader.lib.icu4j.CharsetDetector
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import sjj.alog.Log
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object BookSourceUseCase {
    private val bookSourceDao get() = DbFactory.db.bookSourceDao()
    suspend fun autoImport() = withIo {
        val list = SourceUrlUseCase.getAutoUpdate()
        import(list)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun import(urls: List<SourceUrl>): Int = withIo {

        val sources = urls.map { sourceUrl ->
            async {
                try {
                    val url = sourceUrl.url.trim()
                    val dest = mutableListOf<BookSource>()
                    if (url.startsWith("http")) {
                        val resp = http.get(url)
                        if (resp.code != 200) {
                            Log.i("书源下载失败:${sourceUrl.group}:${sourceUrl.url}:HTTP${resp.code}")
                            return@async emptyList<BookSource>()
                        }
                        var body = resp.body
                        if (url.endsWith("gzip")) {
                            body = GZIPInputStream(Base64.decode(body, Base64.NO_WRAP).inputStream()).reader().readText()
                        }
                        parsePySource(sourceUrl, body, dest)
                    } else {
                        val buffer = App.app.contentResolver.openInputStream(url.toUri())!!.readBytes()
                        if (isZipBytes(buffer)) {
                            parseZipPySources(sourceUrl, buffer, dest)
                        } else {
                            val charsetName = CharsetDetector().setText(buffer).detect()?.name ?: "UTF_8"
                            val charset = Charset.forName(charsetName)
                            var body = String(buffer, charset)
                            if (url.endsWith("gzip")) {
                                body = GZIPInputStream(Base64.decode(body, Base64.NO_WRAP).inputStream()).reader().readText()
                            }
                            parsePySource(sourceUrl, body, dest)
                        }
                    }
                    dest
                } catch (e: Exception) {
                    Log.i("书源导入失败:${sourceUrl}:${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten().toMutableList()
        val total = sources.size
        val countInvalid = sources.count { it.comment.contains("无效书源") }
        sources.removeAll { it.comment.contains("无效书源") }
        val allJs = bookSourceDao.getAllBookSource()
        val updates = sources.filter { s ->
            val local = allJs.find { it.id == s.id }
            if (local != null && local.version <= s.version && local != s || (local?.enable == true && !s.enable)) {
                s.checkResult = local.checkResult
                s.checkErrorMsg = local.checkErrorMsg
                return@filter true
            } else {
                return@filter false
            }
        }
        val newSource = sources.filter { s ->
            val local = allJs.find { it.id == s.id }
            local == null
        }
        if (updates.isEmpty() && newSource.isEmpty()) {
            Log.i("书源无变化：${total}个书源,跳过${countInvalid}个无效书源")
            return@withIo sources.size
        }
        bookSourceDao.insertBookSource(listOf(*updates.toTypedArray(), *newSource.toTypedArray()))
        toast(
            "新增${newSource.size}、更新${updates.size},跳过${countInvalid}个",
            Toast.LENGTH_LONG
        )
        Log.i("${sources.first().group}：新增${newSource.size}、更新${updates.size}")
        return@withIo sources.size
    }


}

fun parsePySource(sourceUrl: SourceUrl, body: String, dest: MutableList<BookSource>): Boolean {
    // 1. JSON 批量书源（含 pySource）。解析成功就按原来的方式入库。
    if (body.contains("pySource")) {
        val parsed = mutableListOf<BookSource>()
        try {
            val source = gson.fromJson<Source>(body)
            source?.pySource?.forEach { pySource ->
                parsed.add(
                    BookSource().apply {
                        name = pySource.source!!
                        group = sourceUrl.group
                        js = pySource.js!!
                        version = pySource.version
                        enable = pySource.enable
                        requestDelay = pySource.requestDelay
                        lauanage = BookSource.Language.py
                        comment = pySource.comment ?: ""
                    }
                )
            }
            dest.addAll(parsed)
            Log.i("解析书源成功：${sourceUrl.group}，共${dest.size}个书源")
            return true
        } catch (e: Exception) {
            Log.e("解析JSON书源失败", e)
            // 不是合法 JSON 时继续尝试单个 .py 脚本
        }
    }

    // 2. 单个 Python 脚本（本地 .py 或脚本 URL），和粘贴脚本新建书源一样写入 js。
    if (!body.contains("def getDetails") && !body.contains("def getChapterContent")) {
        Log.i("不是修仙py书源")
        return false
    }

    // 去掉 if __name__ == 之后的本地测试代码
    val script = body.split("if __name__ ==")[0].trim() + "\n"
    val stem = extractSourceStemFromUrl(sourceUrl.url)
    val name = if (stem.isEmpty()) "导入书源" else sourceNameFromStem(stem)

    dest.add(
        BookSource().apply {
            // 展示名去掉 __ 站点后缀；id 保留完整文件名，避免同名不同站的脚本互相覆盖
            if (stem.isNotEmpty()) {
                id = "${sourceUrl.group.trim()}:${stem}"
            }
            this.name = name
            group = sourceUrl.group
            js = script
            version = 1
            enable = true
            requestDelay = -1
            lauanage = BookSource.Language.py
            comment = ""
        }
    )

    Log.i("解析Python脚本书源成功：${sourceUrl.group}：$name")
    return true
}

/**
 * 一个 zip 里每个 .py 建成一个书源，分组沿用这次导入填写的分组。
 * 跳过目录、__pycache__ 和非 .py。单个脚本仍走 [parsePySource]。
 */
fun parseZipPySources(sourceUrl: SourceUrl, zipBytes: ByteArray, dest: MutableList<BookSource>): Boolean {
    var added = 0
    ZipInputStream(zipBytes.inputStream(), Charsets.UTF_8).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            try {
                val entryName = entry.name.replace('\\', '/')
                if (entry.isDirectory || !isPyZipEntry(entryName)) {
                    continue
                }
                val text = decodeSourceText(zip.readBytes())
                val before = dest.size
                parsePySource(SourceUrl(entryName, sourceUrl.group), text, dest)
                added += dest.size - before
            } catch (e: Exception) {
                Log.i("书源导入失败:${entry.name}:${e.message}")
            } finally {
                zip.closeEntry()
            }
        }
    }
    if (added == 0) {
        Log.i("压缩包里没有可用书源")
        return false
    }
    Log.i("解析压缩包书源成功：${sourceUrl.group}，共${added}个书源")
    return true
}

internal fun isZipBytes(buffer: ByteArray): Boolean {
    if (buffer.size < 4) return false
    if (buffer[0] != 'P'.code.toByte() || buffer[1] != 'K'.code.toByte()) return false
    return when (buffer[2]) {
        0x03.toByte() -> buffer[3] == 0x04.toByte()
        0x05.toByte() -> buffer[3] == 0x06.toByte()
        0x07.toByte() -> buffer[3] == 0x08.toByte()
        else -> false
    }
}

private fun isPyZipEntry(name: String): Boolean {
    val parts = name.split('/')
    if (parts.any { it == "__pycache__" || it == "__MACOSX" || it.startsWith(".") }) return false
    val file = parts.lastOrNull() ?: return false
    return file.endsWith(".py", ignoreCase = true)
}

private fun decodeSourceText(buffer: ByteArray): String {
    return try {
        val charsetName = CharsetDetector().setText(buffer).detect()?.name ?: "UTF_8"
        String(buffer, Charset.forName(charsetName))
    } catch (e: Exception) {
        String(buffer, Charsets.UTF_8)
    }
}

/**
 * 从 URL 或文件路径取出书源名称。
 * 例如 https://example.com/sources/笔趣阁.py → "笔趣阁"
 *      content://.../document/abc%2Ftest.py → "test"
 *      天天小說（优+++）__www.example.com.py → "天天小說（优+++）"
 * 文件名里没有 __ 后缀时，用去掉扩展名后的整段。
 */
internal fun extractSourceNameFromUrl(url: String): String {
    val stem = extractSourceStemFromUrl(url)
    if (stem.isEmpty()) return "导入书源"
    return sourceNameFromStem(stem)
}

internal fun extractSourceStemFromUrl(url: String): String {
    return try {
        val lastSegment = lastPathSegment(url) ?: return ""
        // SAF 解码后路径里可能还带 /，再取最后一段
        val filename = lastSegment.substringAfterLast("/").substringBefore("?")
        filename.substringBeforeLast(".", filename).trim()
    } catch (e: Exception) {
        ""
    }
}

internal fun sourceNameFromStem(stem: String): String {
    val trimmed = stem.trim()
    if (trimmed.isEmpty()) return "导入书源"
    val name = if (trimmed.contains("__")) trimmed.substringBefore("__") else trimmed
    return name.ifBlank { trimmed }.ifBlank { "导入书源" }
}

/**
 * 等价于 Android [android.net.Uri.getLastPathSegment]：丢掉 query，按 / 切分后对最后一段做百分号解码。
 * 用纯 JVM 实现，本地 .py 导入可以在单元测试里验证，不依赖 android.net.Uri。
 */
private fun lastPathSegment(url: String): String? {
    val noQuery = url.substringBefore("#").substringBefore("?")
    val path = if (noQuery.contains("://")) {
        val afterScheme = noQuery.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        afterScheme.substring(slash)
    } else {
        noQuery
    }
    val raw = path.trimEnd('/').substringAfterLast('/')
    if (raw.isEmpty()) return null
    return percentDecode(raw)
}

private fun percentDecode(value: String): String {
    // Uri.decode 不把 '+' 当成空格
    return java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
}




class Source {
    var pySource: List<PySource>? = null

    class PySource {
        var comment: String? = null
        var source: String? = null
        var js: String? = null
        var version: Long = -1
        var enable: Boolean = true
        var requestDelay: Long = -1
    }
}
