package com.sjianjun.reader.module.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchViewModelUrlTest {

    @Test
    fun extractsPlainHttpsUrl() {
        val url = "https://www.69shuba.com/book/12345.htm"
        assertEquals(url, SearchViewModel.extractHttpUrl(url))
    }

    @Test
    fun extractsUrlWithWhitespaceAndQuotes() {
        val raw = "  \"https://www.69shuba.com/book/12345.htm\"  "
        assertEquals(
            "https://www.69shuba.com/book/12345.htm",
            SearchViewModel.extractHttpUrl(raw),
        )
    }

    @Test
    fun extractsUrlEmbeddedInText() {
        val raw = "捞尸人 https://69shuba.com/book/999.htm 在线"
        assertEquals(
            "https://69shuba.com/book/999.htm",
            SearchViewModel.extractHttpUrl(raw),
        )
    }

    @Test
    fun keywordIsNotUrl() {
        assertNull(SearchViewModel.extractHttpUrl("撈屍人"))
        assertNull(SearchViewModel.extractHttpUrl(""))
    }

    @Test
    fun bookTitleFromUrlUsesPathSegment() {
        assertEquals(
            "12345",
            SearchViewModel.bookTitleFromUrl("https://www.69shuba.com/book/12345.htm"),
        )
    }
}
