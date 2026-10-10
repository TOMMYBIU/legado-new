package io.legado.app.help.sync

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.AppFont
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getFile
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest

/**
 * 同步数据的打包与应用。
 *
 * 包的形态与内建备份一致（同名 JSON 文件 + 同名资源目录），
 * 因此同步包也可以直接当作备份包的一个子集来理解。
 *
 * 应用时采用「按主键覆盖 + 保留本机独有条目」的策略：
 * 服务端有的条目会覆盖本机同名条目，本机独有的条目不会被删除，
 * 避免整包覆盖导致误删书源 / 书架数据。
 */
object CloudSyncPackager {

    private const val BOOKSHELF_FILE = "bookshelf.json"
    private const val BOOK_GROUP_FILE = "bookGroup.json"
    private const val READ_RECORD_FILE = "readRecord.json"
    private const val SEARCH_HISTORY_FILE = "searchHistory.json"
    private const val BOOKMARK_FILE = "bookmark.json"
    private const val BOOK_SOURCE_FILE = "bookSource.json"
    private const val RSS_SOURCE_FILE = "rssSources.json"
    private const val SOURCE_SUB_FILE = "sourceSub.json"
    private const val REPLACE_RULE_FILE = "replaceRule.json"
    private const val TXT_TOC_RULE_FILE = "txtTocRule.json"
    private const val DICT_RULE_FILE = "dictRule.json"
    private const val HTTP_TTS_FILE = "httpTTS.json"
    private const val RSS_STAR_FILE = "rssStar.json"
    private const val KEYBOARD_ASSIST_FILE = "keyboardAssists.json"

    private const val ASSETS_DIR = "assets"
    private const val FONT_DIR_NAME = "font"
    private const val BG_DIR_NAME = "bg"

    private const val INSERT_BATCH_SIZE = 500

    // ------------------------------------------------------------ 打包

    suspend fun buildPackage(
        ns: SyncNamespace,
        includeAssets: Boolean,
        baseDir: File
    ): SyncPackage {
        val packDir = File(baseDir, "pack_${ns.key}")
        FileUtils.delete(packDir.absolutePath)
        if (!packDir.mkdirs() && !packDir.isDirectory) {
            throw SyncException("创建同步临时目录失败")
        }
        when (ns) {
            SyncNamespace.SOURCES -> buildSources(packDir)
            SyncNamespace.SHELF -> buildShelf(packDir)
            SyncNamespace.SETTINGS -> buildSettings(packDir, includeAssets)
            SyncNamespace.BOOKMARKS -> buildBookmarks(packDir)
        }
        val entries = packDir.listFiles()?.map { it.absolutePath }.orEmpty()
        if (entries.isEmpty()) throw SyncException("本机该分类暂无可同步的数据")
        // 先算内容哈希（与打包时间无关），再压缩
        val contentHash = contentHash(packDir)
        val zipFile = File(baseDir, "${ns.key}.zip")
        FileUtils.delete(zipFile.absolutePath)
        if (!ZipUtils.zipFiles(entries, zipFile.absolutePath)) {
            throw SyncException("打包同步数据失败")
        }
        return SyncPackage(zipFile, contentHash)
    }

    /**
     * 包内内容的稳定哈希：按相对路径排序后逐个累加「路径 + 内容」。
     * 用它判断本机数据是否改动过；不能直接用 zip 字节，因为 zip 条目带时间戳。
     */
    private fun contentHash(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).path.replace('\\', '/') }
            .forEach { file ->
                digest.update(file.relativeTo(dir).path.replace('\\', '/').toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun buildSources(dir: File) = withContext(IO) {
        writeTable(dir, BOOK_SOURCE_FILE, appDb.bookSourceDao.all)
        writeTable(dir, RSS_SOURCE_FILE, appDb.rssSourceDao.all)
        writeTable(dir, SOURCE_SUB_FILE, appDb.ruleSubDao.all)
        writeTable(dir, REPLACE_RULE_FILE, appDb.replaceRuleDao.all)
        writeTable(dir, TXT_TOC_RULE_FILE, appDb.txtTocRuleDao.all)
        writeTable(dir, DICT_RULE_FILE, appDb.dictRuleDao.all)
        writeTable(dir, HTTP_TTS_FILE, appDb.httpTTSDao.all)
        writeTable(dir, RSS_STAR_FILE, appDb.rssStarDao.all)
        writeTable(dir, KEYBOARD_ASSIST_FILE, appDb.keyboardAssistsDao.all)
    }

    private suspend fun buildShelf(dir: File) {
        withContext(IO) {
            writeBookshelf(dir)
            writeTable(dir, BOOK_GROUP_FILE, appDb.bookGroupDao.all)
            writeTable(dir, READ_RECORD_FILE, appDb.readRecordDao.all)
            writeTable(dir, SEARCH_HISTORY_FILE, appDb.searchKeywordDao.all)
        }
    }

    private suspend fun buildBookmarks(dir: File) = withContext(IO) {
        writeTable(dir, BOOKMARK_FILE, appDb.bookmarkDao.all)
        HighlightRules.store.backupTo(File(dir, HighlightRules.BACKUP_DIR))
    }

    private suspend fun buildSettings(dir: File, includeAssets: Boolean) {
        withContext(IO) {
            ReadBookConfig.backupLayoutFiles().forEach { (name, json) ->
                File(dir, name).writeText(json, Charsets.UTF_8)
            }
            File(dir, ThemeConfig.configFileName)
                .writeText(GSON.toJson(ThemeConfig.configList), Charsets.UTF_8)
            BookCover.getConfig()?.let { config ->
                File(dir, BookCover.configFileName)
                    .writeText(GSON.toJson(config), Charsets.UTF_8)
            }
            File(appCtx.filesDir, EpubReaderTemplateStore.fileName)
                .takeIf { it.isFile }
                ?.copyTo(File(dir, EpubReaderTemplateStore.fileName), overwrite = true)
        }
        if (includeAssets) {
            withContext(IO) {
                copyDir(
                    appCtx.externalFiles.getFile(FONT_DIR_NAME),
                    File(dir, "$ASSETS_DIR/$FONT_DIR_NAME")
                )
                copyDir(
                    appCtx.externalFiles.getFile(BG_DIR_NAME),
                    File(dir, "$ASSETS_DIR/$BG_DIR_NAME")
                )
            }
        }
    }

    private suspend fun writeBookshelf(dir: File) = withContext(IO) {
        val bookUrls = appDb.bookDao.allBookUrls
        if (bookUrls.isEmpty()) return@withContext
        File(dir, BOOKSHELF_FILE).outputStream().buffered().use { output ->
            JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                writer.beginArray()
                bookUrls.chunked(50).forEach { chunk ->
                    appDb.bookDao.getBooksSafe(chunk, chunkSize = 50).forEach { book ->
                        GSON.toJson(book, book.javaClass, writer)
                    }
                }
                writer.endArray()
            }
        }
    }

    private fun writeTable(dir: File, name: String, list: List<Any>) {
        if (list.isEmpty()) return
        File(dir, name).outputStream().buffered().use { output ->
            GSON.writeToOutputStream(output, list)
        }
    }

    // ------------------------------------------------------------ 应用

    suspend fun applyPackage(ns: SyncNamespace, dir: File) {
        when (ns) {
            SyncNamespace.SOURCES -> applySources(dir)
            SyncNamespace.SHELF -> applyShelf(dir)
            SyncNamespace.SETTINGS -> applySettings(dir)
            SyncNamespace.BOOKMARKS -> applyBookmarks(dir)
        }
    }

    private suspend fun applySources(dir: File) = withContext(IO) {
        insertTable<BookSource>(dir, BOOK_SOURCE_FILE) { appDb.bookSourceDao.insert(*it) }
        insertTable<RssSource>(dir, RSS_SOURCE_FILE) { appDb.rssSourceDao.insert(*it) }
        insertTable<RuleSub>(dir, SOURCE_SUB_FILE) { appDb.ruleSubDao.insert(*it) }
        insertTable<ReplaceRule>(dir, REPLACE_RULE_FILE) { appDb.replaceRuleDao.insert(*it) }
        insertTable<TxtTocRule>(dir, TXT_TOC_RULE_FILE) { appDb.txtTocRuleDao.insert(*it) }
        insertTable<DictRule>(dir, DICT_RULE_FILE) { appDb.dictRuleDao.insert(*it) }
        insertTable<HttpTTS>(dir, HTTP_TTS_FILE) { appDb.httpTTSDao.insert(*it) }
        insertTable<RssStar>(dir, RSS_STAR_FILE) { appDb.rssStarDao.insert(*it) }
        readList<KeyboardAssist>(dir, KEYBOARD_ASSIST_FILE)?.takeIf { it.isNotEmpty() }?.let { items ->
            // 与备份恢复保持一致：先清空再写入，保证顺序与内容完全一致
            appDb.keyboardAssistsDao.deleteAll()
            items.chunked(INSERT_BATCH_SIZE).forEach { chunk ->
                appDb.keyboardAssistsDao.insert(*chunk.toTypedArray())
            }
        }
    }

    private suspend fun applyShelf(dir: File) = withContext(IO) {
        applyBookshelf(File(dir, BOOKSHELF_FILE))
        insertTable<BookGroup>(dir, BOOK_GROUP_FILE) { appDb.bookGroupDao.insert(*it) }
        readList<ReadRecord>(dir, READ_RECORD_FILE)?.forEach { record ->
            val localTime = appDb.readRecordDao.getReadTime(record.deviceId, record.bookName)
            if (localTime == null || localTime < record.readTime) {
                appDb.readRecordDao.insert(record)
            }
        }
        insertTable<SearchKeyword>(dir, SEARCH_HISTORY_FILE) { appDb.searchKeywordDao.insert(*it) }
    }

    private suspend fun applyBookmarks(dir: File) = withContext(IO) {
        insertTable<Bookmark>(dir, BOOKMARK_FILE) { appDb.bookmarkDao.insert(*it) }
        File(dir, HighlightRules.BACKUP_DIR)
            .takeIf { it.isDirectory }
            ?.let { HighlightRules.store.restoreFrom(it) }
    }

    private suspend fun applySettings(dir: File) = withContext(IO) {
        val layoutFiles = ReadBookConfig.backupLayoutFiles().keys
        layoutFiles.forEach { name ->
            val source = File(dir, name)
            if (source.isFile) {
                source.copyTo(File(appCtx.filesDir, name), overwrite = true)
            }
        }
        ReadBookConfig.initConfigs()
        ReadBookConfig.initShareConfig()

        File(dir, ThemeConfig.configFileName).takeIf { it.isFile }?.let {
            File(ThemeConfig.configFilePath).writeText(it.readText(Charsets.UTF_8), Charsets.UTF_8)
            ThemeConfig.upConfig()
        }
        File(dir, BookCover.configFileName).takeIf { it.isFile }?.let {
            BookCover.saveCoverRule(it.readText(Charsets.UTF_8))
        }
        File(dir, EpubReaderTemplateStore.fileName).takeIf { it.isFile }?.let {
            EpubReaderTemplateStore.restoreJson(it.readText(Charsets.UTF_8))
        }
        copyDir(
            File(dir, "$ASSETS_DIR/$FONT_DIR_NAME"),
            appCtx.externalFiles.getFile(FONT_DIR_NAME)
        )
        copyDir(
            File(dir, "$ASSETS_DIR/$BG_DIR_NAME"),
            appCtx.externalFiles.getFile(BG_DIR_NAME)
        )
        AppFont.invalidateFontList()
    }

    private fun applyBookshelf(file: File) {
        if (!file.isFile) return
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val jsonReader = JsonReader(reader)
            val pending = ArrayList<Book>(INSERT_BATCH_SIZE)
            fun flush() {
                if (pending.isEmpty()) return
                appDb.bookDao.insert(*pending.toTypedArray())
                pending.clear()
            }
            jsonReader.beginArray()
            while (jsonReader.hasNext()) {
                val book = GSON.fromJson<Book>(jsonReader, Book::class.java) ?: continue
                book.upType()
                if (book.isLocal) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (appDb.bookDao.has(book.bookUrl)) {
                    runCatching { appDb.bookDao.update(book) }
                } else {
                    pending.add(book)
                    if (pending.size >= INSERT_BATCH_SIZE) flush()
                }
            }
            jsonReader.endArray()
            flush()
        }
    }

    // ------------------------------------------------------------ 工具

    private inline fun <reified T> insertTable(dir: File, name: String, insert: (Array<T>) -> Unit) {
        val list = readList<T>(dir, name) ?: return
        if (list.isEmpty()) return
        list.chunked(INSERT_BATCH_SIZE).forEach { insert(it.toTypedArray()) }
    }

    private inline fun <reified T> readList(dir: File, name: String): List<T>? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { GSON.fromJsonArray<T>(it).getOrThrow() }
        }.onFailure {
            AppLog.put("读取同步数据 $name 失败\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun copyDir(source: File, target: File) {
        if (!source.isDirectory) return
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                runCatching { file.copyTo(targetFile, overwrite = true) }
            }
        }
    }
}
