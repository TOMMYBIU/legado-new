package io.legado.app.help.book

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * 批量刮削书籍封面。
 *
 * 背景：项目里"打开书籍详情页自动刮封面"的条件是 coverUrl 与 customCoverUrl 都为空，
 * 但备份恢复/同步拉取会给本地书写入一个"约定封面路径"（图片其实不存在），
 * 导致这个条件永远不成立、自动刮削形同虚设。
 * 这里改成按"磁盘上有没有真实封面文件"判断，并且提供批量处理入口。
 *
 * 两级策略：
 *  1. 先用项目配置的封面规则（`BookCover.searchCover`，尊重用户自定义规则）
 *  2. 失败则回退到内置宽松搜索：只按书名匹配
 *     （本地 TXT 的作者字段常是文件名解析产物，如"（精校版全本）"，
 *      严格规则要求作者互相包含，必然匹配不上）
 */
object BookCoverScraper {

    /** 每本书之间的间隔，避免把搜索接口打爆 */
    private const val REQUEST_INTERVAL_MS = 300L

    private const val LOOSE_SEARCH_API =
        "https://pre-api.tuishujun.com/api/searchBook?page=1&pageSize=20&search_value="

    data class Result(
        /** 书架总书数 */
        val total: Int,
        /** 本次需要刮削的书数（跳过已有真实封面的） */
        val targets: Int,
        val scraped: Int,
        val failed: Int
    ) {
        val skipped: Int get() = total - targets
    }

    /**
     * @param onlyLocal 只处理本地导入的书（TXT/EPUB 等）
     * @param onProgress 进度回调，在 IO 线程执行
     */
    suspend fun scrape(
        onlyLocal: Boolean = true,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): Result = withContext(IO) {
        val all = appDb.bookDao.all
        val targets = all.filter { (!onlyLocal || it.isLocal) && !hasRealCover(it) }
        var scraped = 0
        var failed = 0
        targets.forEachIndexed { index, book ->
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, targets.size, book.name)
            val cover = runCatching { searchCoverSmart(book) }.getOrElse { error ->
                AppLog.put("刮削封面失败《${book.name}》\n${error.localizedMessage}", error)
                null
            }
            if (cover.isNullOrBlank()) {
                failed++
            } else {
                book.customCoverUrl = cover
                appDb.bookDao.update(book)
                scraped++
            }
            delay(REQUEST_INTERVAL_MS)
        }
        Result(total = all.size, targets = targets.size, scraped = scraped, failed = failed)
    }

    private suspend fun searchCoverSmart(book: Book): String? {
        // 1. 项目配置的封面规则
        runCatching { BookCover.searchCover(book) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // 2. 内置宽松搜索
        return searchLoose(book.name)
    }

    private suspend fun searchLoose(bookName: String): String? {
        val name = bookName.trim()
        if (name.isBlank()) return null
        val api = LOOSE_SEARCH_API + URLEncoder.encode(name, "UTF-8")
        val body = okHttpClient.newCallStrResponse(1) {
            url(api)
        }.body ?: return null
        val items = GSON.fromJsonObject<LooseSearchResponse>(body)
            .getOrNull()
            ?.data?.data
            ?: return null
        return pickCover(items, name)
    }

    private fun pickCover(items: List<LooseSearchItem>, bookName: String): String? {
        val name = bookName.trim()
        // 优先书名完全一致
        items.firstOrNull { it.title?.trim() == name && !it.cover.isNullOrBlank() }
            ?.let { return it.cover }
        // 其次书名互相包含（书名太短容易误配，要求至少 4 个字）
        if (name.length >= 4) {
            items.firstOrNull { item ->
                val title = item.title?.trim().orEmpty()
                !item.cover.isNullOrBlank() && title.isNotBlank() &&
                    (title.contains(name) || name.contains(title))
            }?.let { return it.cover }
        }
        return null
    }

    /** 是否已有真实封面：自定义封面、网络封面、或磁盘上真实存在的封面文件 */
    fun hasRealCover(book: Book): Boolean {
        if (!book.customCoverUrl.isNullOrBlank()) return true
        val coverUrl = book.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            if (coverUrl.startsWith("http", true)) return true
            if (File(coverUrl).isFile) return true
        }
        return LocalBook.findCoverPath(book) != null
    }

    // ------------------------------------------------------------ 宽松搜索的响应模型

    private data class LooseSearchResponse(
        @SerializedName("data") val data: LooseSearchData? = null
    )

    private data class LooseSearchData(
        @SerializedName("data") val data: List<LooseSearchItem>? = null
    )

    private data class LooseSearchItem(
        @SerializedName("title") val title: String? = null,
        @SerializedName("cover") val cover: String? = null,
        @SerializedName("author_nickname") val author: String? = null
    )
}
