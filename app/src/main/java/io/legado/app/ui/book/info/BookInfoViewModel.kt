package io.legado.app.ui.book.info

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.addType
import io.legado.app.help.book.BookCoverScraper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.AudioPlay
import io.legado.app.model.AutoTask
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.VideoPlay
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO

class BookInfoViewModel(application: Application) : BaseViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val chapterListData = MutableLiveData<List<BookChapter>>()
    val tocLoadStateData = MutableLiveData(BookInfoTocLoadState())
    private val tocLoadTracker = BookInfoTocLoadTracker { tocLoadStateData.postValue(it) }
    val webFiles = mutableListOf<WebFile>()
    var inBookshelf = false
    var hasCustomBtn = false
    var bookSource: BookSource? = null
    private var changeSourceCoroutine: Coroutine<*>? = null
    val waitDialogData = MutableLiveData<Boolean>()
    val actionLive = MutableLiveData<String>()

    fun tocLoadPhase(bookUrl: String): BookInfoTocPhase {
        // A source can derive another book URL before the next loading-state emission.
        // Keep the existing chapter consumers independent of this presentation state.
        val chapters = chapterListData.value
        val fallback = when {
            chapters == null -> BookInfoTocPhase.NOT_LOADED
            chapters.isEmpty() -> BookInfoTocPhase.EMPTY
            else -> BookInfoTocPhase.READY
        }
        return tocLoadTracker.phaseFor(bookUrl, fallback)
    }

    fun initData(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            val bookUrl = intent.getStringExtra("bookUrl") ?: ""
            val origin = intent.getStringExtra("origin") ?: ""
            val originName = intent.getStringExtra("originName") ?: ""
            val coverUrl = intent.getStringExtra("coverUrl") ?: ""
            if (bookUrl.isNotBlank()) {
                appDb.bookDao.getBook(bookUrl)?.let { book ->
                    if (!book.isNotShelf) {
                        inBookshelf = true
                        upBook(book)
                        return@execute
                    }
                    upResolvedBook(book)
                    return@execute
                }
                appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.let { book ->
                    upResolvedBook(book)
                    return@execute
                }
            }
            appDb.bookDao.getBook(name, author)?.let { book ->
                if (!book.isNotShelf) {
                    inBookshelf = true
                    upBook(book)
                } else {
                    upResolvedBook(book)
                }
                return@execute
            }
            appDb.searchBookDao.getFirstByNameAuthor(name, author)?.toBook()?.let { book ->
                upResolvedBook(book)
                return@execute
            }
            if (bookUrl.isNotBlank() && origin.isNotBlank()) {
                upResolvedBook(
                    Book(
                        name = name,
                        author = author,
                        bookUrl = bookUrl,
                        origin = origin,
                        originName = originName,
                        coverUrl = coverUrl.takeIf { it.isNotBlank() }
                    )
                )
                return@execute
            }
            throw NoStackTraceException("未找到书籍")
        }.onError {
            AppLog.put(it.localizedMessage, it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun upBook(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            val bookUrl = intent.getStringExtra("bookUrl") ?: ""
            if (bookUrl.isNotBlank()) {
                appDb.bookDao.getBook(bookUrl)?.let { book ->
                    upResolvedBook(book)
                    return@execute
                }
            }
            appDb.bookDao.getBook(name, author)?.let { book ->
                upResolvedBook(book)
            }
        }
    }

    private fun upResolvedBook(candidate: Book) {
        resolveShelfBook(candidate)?.let { shelfBook ->
            inBookshelf = true
            upBook(shelfBook)
            return
        }
        inBookshelf = false
        upBook(candidate)
    }

    private fun resolveShelfBook(candidate: Book): Book? {
        if (candidate.bookUrl.isNotBlank()) {
            appDb.bookDao.getBook(candidate.bookUrl)
                ?.takeUnless { it.isNotShelf }
                ?.let { return it }
        }
        if (candidate.name.isBlank()) return null
        return appDb.bookDao.getBook(candidate.name, candidate.author)
            ?.takeUnless { it.isNotShelf }
    }

    private fun upBook(book: Book) {
        val request = tocLoadTracker.begin(book.bookUrl)
        execute {
            bookSource = if (book.isLocal) null else
                appDb.bookSourceDao.getBookSource(book.origin)?.also {
                    hasCustomBtn = it.customButton
                }
            if (!book.isLocal && bookSource == null) {
                context.toastOnUi(R.string.error_no_source)
            }
            bookData.postValue(book)
            upCoverByRule(book)
            if (book.tocUrl.isEmpty() && !book.isLocal) {
                loadBookInfo(book, true, inBookshelf, viewModelScope, request)
            } else {
                val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                if (chapterList.isNotEmpty()) {
                    chapterListData.postValue(chapterList)
                    tocLoadTracker.complete(request, book.bookUrl, chapterList.size)
                } else {
                    loadChapter(book, true, viewModelScope, true, request)
                }
            }
        }.onError {
            if (tocLoadTracker.isCurrent(request)) {
                tocLoadTracker.fail(request, book.bookUrl)
                AppLog.put(it.localizedMessage, it)
                context.toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun upCoverByRule(book: Book) {
        execute {
            // 注意：不能用"coverUrl 是否为空"判断，备份恢复/同步会给本地书写入一个约定路径，
            // 那个图片文件并不存在，会导致永远不刮封面
            if (!BookCoverScraper.hasRealCover(book)) {
                val coverUrl = BookCover.searchCover(book)
                if (coverUrl.isNullOrBlank()) {
                    return@execute
                }
                book.customCoverUrl = coverUrl
                bookData.postValue(book)
                if (inBookshelf) {
                    saveBook(book)
                }
            }
        }
    }

    fun refreshBook(book: Book) {
        val request = tocLoadTracker.begin(book.bookUrl)
        executeLazy(executeContext = IO) {
            if (book.isLocal) {
                book.tocUrl = ""
                book.getRemoteUrl()?.let {
                    val bookWebDav = AppWebDav.defaultBookWebDav
                        ?: throw NoStackTraceException("webDav没有配置")
                    val remoteBook = bookWebDav.getRemoteBook(it)
                    if (remoteBook == null) {
                        book.origin = BookType.localTag
                    } else if (remoteBook.lastModify > book.lastCheckTime) {
                        val uri = bookWebDav.downloadRemoteBook(remoteBook)
                        book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                        book.lastCheckTime = remoteBook.lastModify
                    }
                }
            } else {
                val bs = bookSource ?: return@executeLazy
                if (book.originName != bs.bookSourceName) {
                    book.originName = bs.bookSourceName
                }
            }
        }.onError {
            when (it) {
                is ObjectNotFoundException -> {
                    book.origin = BookType.localTag
                }

                else -> {
                    AppLog.put("下载远程书籍<${book.name}>失败", it)
                }
            }
        }.onFinally {
            loadBookInfo(book, false, true, viewModelScope, request)
        }.start()
    }

    fun loadBookInfo(
        book: Book,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope
    ) {
        loadBookInfo(book, canReName, runPreUpdateJs, scope, tocLoadTracker.begin(book.bookUrl))
    }

    private fun loadBookInfo(
        book: Book,
        canReName: Boolean,
        runPreUpdateJs: Boolean,
        scope: CoroutineScope,
        request: Long
    ) {
        if (book.isLocal) {
            try {
                LocalBook.upBookInfo(book)
                bookData.postValue(book)
                loadChapter(book, true, viewModelScope, false, request)
            } catch (exception: Exception) {
                tocLoadTracker.fail(request, book.bookUrl)
                throw exception
            }
        } else {
            val bookSource = bookSource ?: let {
                chapterListData.postValue(emptyList())
                tocLoadTracker.fail(request, book.bookUrl)
                context.toastOnUi(R.string.error_no_source)
                return
            }
            WebBook.getBookInfo(scope, bookSource, book, canReName = canReName)
                .onSuccess(IO) {
                    val displayBook = if (!inBookshelf) {
                        resolveShelfBook(it)?.also {
                            inBookshelf = true
                        } ?: it
                    } else {
                        it
                    }
                    bookData.postValue(displayBook)
                    // Only persist a shelf-state change when a shelf row for this identity
                    // actually exists. Refreshing info can hand back a book carrying a
                    // freshly derived url, and clearing notShelf on that would save a brand
                    // new row with the flag already off, putting a book the reader never
                    // added onto the shelf.
                    if (inBookshelf && resolveShelfBook(displayBook) != null) {
                        displayBook.removeType(BookType.notShelf)
                        displayBook.save()
                    }
                    if (displayBook.isWebFile) {
                        loadWebFile(displayBook, request)
                    } else {
                        loadChapter(displayBook, runPreUpdateJs, viewModelScope, true, request)
                    }
                }.onError {
                    tocLoadTracker.fail(request, book.bookUrl)
                    AppLog.put("获取书籍信息失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_book_info)
                }
        }
    }
    fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope,
        isFromBookInfo: Boolean = false
    ) {
        loadChapter(book, runPreUpdateJs, scope, isFromBookInfo, tocLoadTracker.begin(book.bookUrl))
    }

    private fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean,
        scope: CoroutineScope,
        isFromBookInfo: Boolean,
        request: Long
    ) {
        if (book.isLocal) {
            execute(scope) {
                LocalBook.getChapterList(book).let {
                    appDb.bookDao.update(book)
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    ReadBook.onChapterListUpdated(book)
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                    tocLoadTracker.complete(request, book.bookUrl, it.size)
                }
            }.onError {
                tocLoadTracker.fail(request, book.bookUrl)
                context.toastOnUi("LoadTocError:${it.localizedMessage}")
            }
        } else {
            val bookSource = bookSource ?: let {
                chapterListData.postValue(emptyList())
                tocLoadTracker.fail(request, book.bookUrl)
                context.toastOnUi(R.string.error_no_source)
                return
            }
            val oldBook = book.copy()
            WebBook.getChapterList(scope, bookSource, book, runPreUpdateJs, isFromBookInfo = isFromBookInfo)
                .onSuccess(IO) {
                    if (inBookshelf) {
                        val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
                        BookHelp.remapContentCache(oldBook, oldChapterList, it)
                        book.removeType(BookType.updateError)
                        appDb.bookDao.replace(oldBook, book)
                        /**
                         * runPreUpdateJs 有可能会修改 book 的 bookUrl
                         */
                        if (oldBook.bookUrl != book.bookUrl) {
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*it.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                    }
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                    tocLoadTracker.complete(request, book.bookUrl, it.size)
                }.onError {
                    chapterListData.postValue(emptyList())
                    tocLoadTracker.fail(request, book.bookUrl)
                    AppLog.put("获取目录失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_chapter_list)
                }
        }
    }


    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    private fun loadWebFile(book: Book, request: Long) {
        execute {
            webFiles.clear()
            val fileNameNoExtension = if (book.author.isBlank()) book.name
            else "${book.name} 作者：${book.author}"
            book.downloadUrls!!.map {
                val analyzeUrl = AnalyzeUrl(
                    it, source = bookSource,
                    coroutineContext = coroutineContext
                )
                var mFileName = UrlUtil.getFileName(analyzeUrl)
                    ?: fileNameNoExtension
                analyzeUrl.type?.let { suffix ->
                    mFileName += ".${suffix}"
                }
                WebFile(it, mFileName)
            }
        }.onError {
            tocLoadTracker.fail(request, book.bookUrl)
            context.toastOnUi("LoadWebFileError\n${it.localizedMessage}")
        }.onSuccess {
            webFiles.addAll(it)
            book.latestChapterTitle = "已下载"
            bookData.postValue(book)
            chapterListData.postValue(emptyList())
            tocLoadTracker.complete(request, book.bookUrl, 0)
        }
    }

    /* 导入或者下载在线文件 */
    fun <T> importOrDownloadWebFile(webFile: WebFile, success: ((T) -> Unit)?) {
        bookSource ?: return
        execute {
            waitDialogData.postValue(true)
            if (webFile.isSupported) {
                val book = LocalBook.importFileOnLine(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
                changeToLocalBook(book)
            } else {
                LocalBook.saveBookFile(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
            }
        }.onSuccess {
            @Suppress("unchecked_cast")
            success?.invoke(it as T)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it)
                    context.toastOnUi("ImportWebFileError\n${it.localizedMessage}")
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun getArchiveFilesName(archiveFileUri: Uri, onSuccess: (List<String>) -> Unit) {
        execute {
            ArchiveUtils.getArchiveFilesName(archiveFileUri) {
                AppPattern.bookFileRegex.matches(it)
            }
        }.onError {
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it)
            context.toastOnUi("getArchiveEntriesName Error:\n${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importArchiveBook(
        archiveFileUri: Uri,
        archiveEntryName: String,
        success: ((Book) -> Unit)? = null
    ) {
        execute {
            val suffix = archiveEntryName.substringAfterLast(".")
            LocalBook.importArchiveFile(
                archiveFileUri,
                bookData.value!!.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            val book = changeToLocalBook(it)
            success?.invoke(book)
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it)
            context.toastOnUi("importArchiveBook Error:\n${it.localizedMessage}")
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        val request = tocLoadTracker.begin(book.bookUrl)
        changeSourceCoroutine = execute {
            bookSource = source.also {
                hasCustomBtn = it.customButton
            }
            bookData.value?.migrateTo(book, toc)
            if (book.isWebFile) {
                loadWebFile(book, request)
            }
            if (inBookshelf) {
                book.removeType(BookType.updateError)
                bookData.value?.delete()
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            bookData.postValue(book)
            chapterListData.postValue(toc)
            if (!book.isWebFile) tocLoadTracker.complete(request, book.bookUrl, toc.size)
        }.onError {
            if (tocLoadTracker.isCurrent(request)) {
                tocLoadTracker.fail(request, book.bookUrl)
                AppLog.put(it.localizedMessage, it)
                context.toastOnUi(it.localizedMessage)
            }
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                val minOrder = appDb.bookDao.minOrder
                book.order = minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

    fun saveBook(book: Book?, success: (() -> Unit)? = null) {
        book ?: return
        execute {
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            appDb.bookDao.getBook(book.name, book.author)?.let {
                book.durChapterIndex = it.durChapterIndex
                book.durChapterPos = it.durChapterPos
                book.durChapterTitle = it.durChapterTitle
            }
            book.save()
            if (ReadBook.book?.isSameNameAuthor(book) == true) {
                ReadBook.book = book
            } else if (AudioPlay.book?.isSameNameAuthor(book) == true) {
                AudioPlay.book = book
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun saveChapterList(success: (() -> Unit)?) {
        execute {
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun prepareBookForEntry(book: Book, success: ((Book) -> Unit)?) {
        execute {
            val resolved = resolveShelfBook(book)
            if (resolved != null) {
                inBookshelf = true
                bookData.postValue(resolved)
                resolved
            } else {
                // resolveShelfBook found no shelf row for this book, so the reader has not
                // added it. Keep it temporary regardless of the cached inBookshelf flag:
                // that flag can still read "on shelf" after a source derived a fresh url
                // for the same book, and clearing notShelf here is what silently promotes
                // a book the reader only sampled.
                book.addType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                book.save()
                chapterListData.value?.let {
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                }
                bookData.postValue(book)
                book
            }
        }.onSuccess {
            success?.invoke(it)
        }
    }

    fun saveBookAtChapter(book: Book, chapter: BookChapter, success: ((Book) -> Unit)?) {
        execute {
            val resolved = resolveShelfBook(book)
            val target = resolved ?: book
            if (resolved != null) {
                inBookshelf = true
            }
            target.durChapterIndex = chapter.index
            target.durChapterPos = 0
            target.durChapterTitle = chapter.title
            if (resolved == null) {
                // No shelf row for this identity, so this is a book being sampled. Decide
                // from the lookup rather than the cached inBookshelf flag, which can still
                // read "on shelf" once a source derives a fresh url for the same book.
                target.addType(BookType.notShelf)
                if (target.order == 0) {
                    target.order = appDb.bookDao.minOrder - 1
                }
                target.save()
                chapterListData.value?.let {
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                }
            } else {
                target.removeType(BookType.notShelf)
                // save() rather than update(): update() is a silent no-op when the row is
                // absent, which would drop both the chapter position and this flag change.
                target.save()
            }
            bookData.postValue(target)
            target
        }.onSuccess {
            success?.invoke(it)
        }
    }

    fun addToBookshelf(success: (() -> Unit)?) { //点击书架按钮或在加分组时触发
        execute {
            bookData.value?.let { book ->
                book.removeType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                appDb.bookDao.getBook(book.name, book.author)?.let {
                    book.durChapterIndex = it.durChapterIndex
                    book.durChapterPos = it.durChapterPos
                    book.durChapterTitle = it.durChapterTitle
                }
                book.save()
                if (ReadBook.book?.isSameNameAuthor(book) == true) {
                    ReadBook.book = book
                    ReadBook.inBookshelf = true
                }
                if (ReadManga.book?.isSameNameAuthor(book) == true) {
                    ReadManga.book = book
                    ReadManga.inBookshelf = true
                }
                if (AudioPlay.book?.isSameNameAuthor(book) == true) {
                    AudioPlay.book = book
                    AudioPlay.inBookshelf = true
                }
                if (VideoPlay.book?.isSameNameAuthor(book) == true) {
                    VideoPlay.book = book
                    VideoPlay.inBookshelf = true
                }
                SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, bookSource, book)
            }
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
            inBookshelf = true
        }.onSuccess {
            success?.invoke()
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun delBook(deleteOriginal: Boolean = false, success: (() -> Unit)? = null) {
        execute {
            bookData.value?.let {
                AutoTask.delete(AutoTask.bookTaskId(it.bookUrl))
                it.delete()
                inBookshelf = false
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun clearCache(book: Book) {
        execute {
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
            }
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.value?.let {
            appDb.bookDao.getBook(it.bookUrl)?.let { book ->
                bookData.postValue(book)
            }
        }
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return LocalBook.mergeBook(localBook, bookData.value).let {
            bookData.postValue(it)
            loadChapter(it)
            inBookshelf = true
            it
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = bookSource ?: return
        val book = bookData.value ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.bookSourceName}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }

    data class WebFile(
        val url: String,
        val name: String,
    ) {

        override fun toString(): String {
            return name
        }

        // 后缀
        val suffix: String = UrlUtil.getSuffix(name)

        // txt epub umd pdf等文件
        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        // 压缩包形式的txt epub umd pdf文件
        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}
