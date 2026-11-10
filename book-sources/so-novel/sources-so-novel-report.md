# Pack so-novel Python sources

Source: https://github.com/freeok/so-novel (`bundle/rules/*.json`).

A source was eligible when the JSON contains no `@js:` or `@java:`. Empty `book.*` fields use so-novel `SourceUtils` meta defaults (`og:novel:*` / `description` / `og:image`). `crawl.*` concurrency/intervals are not Reader fields and were not executed.

DNS failure and connection refusal are noted. A timeout is not treated as dead; the script is still written. TLS hosts that answer only with verification off set `insecure` and retry once.

Scripts: `/cursor/stores/bc-c379266f-1fda-41b2-b913-3b62f63e71a7/docs/sources-so-novel/`.

Compile errors: 0.

## Converted

Converted 16. Skipped 15.

### Converted

- 黄易天地 — http://www.xhytd.com/ — `黄易天地__www.xhytd.com.py` (search disabled / URL-only) — base URL timed out from this environment
- 东滩小说 — http://www.dongtanxs.com/ — `东滩小说__www.dongtanxs.com.py`
- 哔哩轻小说 — https://www.linovelib.com/ — `哔哩轻小说__www.linovelib.com.py`
- 香书小说 — http://www.ibiqugu.net/ — `香书小说__www.ibiqugu.net.py` — base URL timed out from this environment
- 书海阁小说网 — https://www.shuhaige.net/ — `书海阁小说网__www.shuhaige.net.py` — base URL connection aborted from this environment
- 鸟书网 — http://www.99wx.info/ — `鸟书网__www.99wx.info.py` — base URL timed out from this environment
- 笔尖中文 — http://www.xbiquwk.com/ — `笔尖中文__www.xbiquwk.com.py` — base URL timed out from this environment
- 悠久小说网 — http://www.ujxsw.org/ — `悠久小说网__www.ujxsw.org.py` — base URL timed out from this environment
- 阅读库 — http://www.yeudusk.com/ — `阅读库__www.yeudusk.com.py` — base URL timed out from this environment
- 笔趣阁365 — https://www.biquge365.net/ — `笔趣阁365__www.biquge365.net.py`
- 笔趣阁info — https://www.bqg.info/ — `笔趣阁info__www.bqg.info.py`
- 小说虎 — https://www.xiaoshuohu.com/ — `小说虎__www.xiaoshuohu.com.py` (search disabled / URL-only)
- 小说之家 — https://xszj.org/ — `小说之家__xszj.org.py` (search disabled / URL-only)
- 69书吧 — https://www.69shuba.com/ — `69书吧__www.69shuba.com.py` (search disabled / URL-only; proxy-required pack)
- 101看书 — https://101kks.com/ — `101看书__101kks.com.py` (proxy-required pack)
- 新天禧小说 — https://www.xtxxs9.com/ — `新天禧小说__www.xtxxs9.com.py`

### Skipped

- 顶点小说 — https://www.wxsy.net/ — depends on so-novel JS/Java (toc.list (@js), chapter.content (@js+@java))
- 96读书 — https://www.96dushu.com/ — depends on so-novel JS/Java (chapter.content (@js+@java), chapter.nextPageInJs (@js))
- 和图书 — http://127.0.0.1:8000/ — depends on so-novel JS/Java (search.url (@js), search.result (@js), search.author (@js), book.category (@js), toc.list (@js))
- 梦书中文 — http://www.mcxs.la/ — depends on so-novel JS/Java (book.coverUrl (@js))
- 第八中文网 — https://www.d8zw.com/ — depends on so-novel JS/Java (search.bookName (@js), search.status (@js), search.lastUpdateTime (@js))
- 笔趣阁52 — https://www.52bqg.com/ — depends on so-novel JS/Java (toc.list (@js), chapter.title (@js), chapter.content (@js+@java), chapter.nextPageInJs (@js))
- 少年小说网 — https://www.snxsw.com/ — depends on so-novel JS/Java (toc.list (@js), chapter.title (@js), chapter.content (@js+@java))
- 新笔趣阁小说网 — https://www.xbiquge.info/ — depends on so-novel JS/Java (search.bookName (@js), search.status (@js), search.lastUpdateTime (@js))
- 天天看小说 — https://cn.ttkan.co/ — depends on so-novel JS/Java (book.intro (@js))
- 全本小说网 — https://quanben5.com/ — depends on so-novel JS/Java (search.url (@js), search.result (@js))
- 大熊猫文学 — https://www.dxmwx.org/ — depends on so-novel JS/Java (search.latestChapter (@js))
- 笔趣屋 — https://www.fsshu.com/ — depends on so-novel JS/Java (search.bookName (@js))
- 零点小说 — https://www.0xs.net/ — depends on so-novel JS/Java (chapter.title (@js))
- 速读谷 — https://www.suduguu.com/ — depends on so-novel JS/Java (search.author (@js), book.author (@js))
- 笔趣阁中文网 — https://www.xbiquge.cc/ — depends on so-novel JS/Java (toc.list (@js), chapter.title (@js), chapter.content (@js+@java))

## Notes

- Runtime matches `sources-1331` Legado subset, plus so-novel helpers: `tocUrlTemplate`/`bookUrlRegex`, `filterTags`/`filterTxt`, `searchDisabled`, `tocDesc`.
- Chapter content is taken as HTML then cleaned (so-novel filterTxt/filterTag), not raw Legado `@text` only.
- No push. myjknote untouched.
