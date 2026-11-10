package com.sjianjun.reader.repository

import com.sjianjun.reader.bean.BookSource
import com.sjianjun.reader.bean.SourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源导入：单个 .py 脚本走和粘贴脚本相同的字段，原有 pySource JSON 批量导入保持不变。
 */
class ParsePySourceTest {

    private val script = """
        def search(query):
            return []

        def getDetails(url, lastChapterUrl):
            return {"url": url, "title": "t", "author": "a", "chapterList": []}

        def getChapterContent(url):
            return "正文"

        if __name__ == "__main__":
            print("local test")
    """.trimIndent()

    @Test
    fun `本地py文件按文件名建成书源并去掉测试代码`() {
        val dest = mutableListOf<BookSource>()
        val url = "content://com.android.externalstorage.documents/document/primary%3ADownload%2F%E7%AC%94%E8%B6%A3%E9%98%81.py"
        val ok = parsePySource(SourceUrl(url, "自建书源"), script, dest)

        assertTrue(ok)
        assertEquals(1, dest.size)
        val source = dest.single()
        assertEquals("笔趣阁", source.name)
        assertEquals("自建书源", source.group)
        assertEquals("自建书源:笔趣阁", source.id)
        assertEquals(BookSource.Language.py, source.lauanage)
        assertEquals(1L, source.version)
        assertTrue(source.enable)
        assertEquals(-1L, source.requestDelay)
        assertEquals("", source.comment)
        assertTrue(source.js.contains("def getDetails"))
        assertTrue(source.js.contains("def getChapterContent"))
        assertFalse(source.js.contains("if __name__"))
        assertFalse(source.js.contains("local test"))
        assertTrue(source.js.endsWith("\n"))
    }

    @Test
    fun `http脚本地址用最后一段文件名`() {
        val dest = mutableListOf<BookSource>()
        val ok = parsePySource(
            SourceUrl("https://example.com/sources/笔趣阁.py?raw=1", "网络"),
            script,
            dest
        )
        assertTrue(ok)
        assertEquals("笔趣阁", dest.single().name)
        assertEquals("网络:笔趣阁", dest.single().id)
    }

    @Test
    fun `只有getChapterContent的脚本也能导入`() {
        val body = "def getChapterContent(url):\n    return 'x'\n"
        val dest = mutableListOf<BookSource>()
        val ok = parsePySource(SourceUrl("file:///tmp/only.py", "自建书源"), body, dest)
        assertTrue(ok)
        assertEquals("only", dest.single().name)
    }

    @Test
    fun `不是书源脚本时失败且不新增`() {
        val dest = mutableListOf<BookSource>()
        val ok = parsePySource(SourceUrl("file:///tmp/readme.py", "自建书源"), "print('hi')\n", dest)
        assertFalse(ok)
        assertTrue(dest.isEmpty())
    }

    @Test
    fun `文件名取不出来时用导入书源`() {
        assertEquals("导入书源", extractSourceNameFromUrl("content://authority"))
        assertEquals("test", extractSourceNameFromUrl("content://auth/document/abc%2Ftest.py"))
    }

    @Test
    fun `原有pySource JSON批量导入不变`() {
        val json = """
            {
              "pySource": [
                {
                  "comment": "备注",
                  "source": "旧书源",
                  "js": "def getDetails(url, last):\n    pass\n",
                  "version": 3,
                  "enable": false,
                  "requestDelay": 1000
                }
              ]
            }
        """.trimIndent()
        val dest = mutableListOf<BookSource>()
        val ok = parsePySource(SourceUrl("https://example.com/sources.json", "订阅"), json, dest)
        assertTrue(ok)
        assertEquals(1, dest.size)
        val source = dest.single()
        assertEquals("旧书源", source.name)
        assertEquals("订阅", source.group)
        assertEquals("订阅:旧书源", source.id)
        assertEquals(3L, source.version)
        assertFalse(source.enable)
        assertEquals(1000L, source.requestDelay)
        assertEquals("备注", source.comment)
        assertEquals(BookSource.Language.py, source.lauanage)
        assertTrue(source.js.contains("def getDetails"))
    }
}
