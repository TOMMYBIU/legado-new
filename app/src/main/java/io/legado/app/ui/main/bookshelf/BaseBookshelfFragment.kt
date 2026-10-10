package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.BookCoverScraper
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.ComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.ComposeTextInputDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(
                ComposeTextInputDialog.create(
                    title = getString(R.string.export_success),
                    hint = getString(R.string.path),
                    initialValue = uri.toString(),
                    message = DirectLinkUpload.getSummary().takeIf { uri.toString().isAbsUrl() },
                    readOnly = true,
                    positiveText = getString(android.R.string.ok),
                    negativeText = getString(android.R.string.cancel),
                    onPositive = { requireContext().sendToClip(it) }
                )
            )
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }
    private var modernMenuPopup: ModernActionPopup.Handle? = null

    abstract fun gotoTop()

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
    }

    protected fun installModernBookshelfOverflow(toolbar: Toolbar) {
        toolbar.post {
            toolbar.children
                .filterIsInstance<ActionMenuView>()
                .firstOrNull()
                ?.children
                ?.forEach { itemView ->
                    itemView.setOnClickListener {
                        showModernBookshelfMenu(itemView)
                    }
                }
        }
    }

    protected fun showModernBookshelfMenu(anchor: View) {
        modernMenuPopup = ModernActionPopup.showFromMenu(
            anchor,
            R.menu.main_bookshelf,
            modernMenuPopup
        ) {
            onCompatOptionsItemSelected(it)
            true
        }
    }

    protected fun dismissBookshelfTransientUi() {
        modernMenuPopup?.dismiss()
        modernMenuPopup = null
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> activityViewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_book_tag_manage -> startActivity<BookshelfTagManageActivity> {
                putExtra("groupId", groupId)
            }
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> lifecycleScope.launch {
                val bookUrls = books.map { it.bookUrl }
                val fullBooks = withContext(IO) {
                    appDb.bookDao.getBooksSafe(bookUrls)
                }
                viewModel.exportBookshelf(fullBooks) { file ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData =
                            HandleFileContract.FileData("bookshelf.json", file, "application/json")
                    }
                }
            }

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_scrape_cover -> scrapeCovers()
        }
    }

    /**
     * 批量刮削封面：只处理"没有真实封面"的本地书，不覆盖用户已设置过的封面
     */
    private fun scrapeCovers() {
        val waitDialog = WaitDialog(requireContext())
        waitDialog.setText(R.string.scrape_cover)
        waitDialog.show()
        val job = lifecycleScope.launch {
            val result = BookCoverScraper.scrape(onlyLocal = true) { done, total, name ->
                activity?.runOnUiThread {
                    waitDialog.setText("$done/$total  $name")
                }
            }
            waitDialog.dismiss()
            toastOnUi(
                "刮削完成：成功 ${result.scraped}，未找到 ${result.failed}，跳过 ${result.skipped}"
            )
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        waitDialog.setOnCancelListener { job.cancel() }
    }

    protected open fun showBookTagManageAlert() {
        val targetBooks = books
        val tags = targetBooks
            .flatMap { BookTagHelper.parse(it.customTag) }
            .distinct()
            .sorted()
        if (tags.isEmpty()) {
            toastOnUi(R.string.bookshelf_tag_none)
            return
        }
        val checked = BooleanArray(tags.size) { true }
        val labels = tags.map { tag ->
            "$tag (${targetBooks.count { BookTagHelper.has(it.customTag, tag) }})"
        }
        showDialogFragment(
            ComposeMultiChoiceDialog.create(
                title = getString(R.string.bookshelf_tag_manage),
                labels = labels,
                checked = checked,
                message = getString(R.string.bookshelf_tag_manage_hint),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = { result ->
                    val keepTags = tags.filterIndexed { index, _ -> result[index] }.toSet()
                lifecycleScope.launch(IO) {
                    val fullBooks = appDb.bookDao.getBooksSafe(targetBooks.map { it.bookUrl })
                    fullBooks.forEach { book ->
                        val normalized = BookTagHelper.join(
                            BookTagHelper.parse(book.customTag).filter { it in keepTags }
                        )
                        if (normalized != book.customTag) {
                            book.customTag = normalized
                            appDb.bookDao.update(book)
                        }
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                }
            )
        )
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = getString(R.string.add_book_url),
                hint = "url",
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            )
        )
    }

    fun configBookshelf() {
        val groupStyleCount = resources.getStringArray(R.array.group_style).size
        if (AppConfig.bookGroupStyle !in 0 until groupStyleCount) {
            AppConfig.bookGroupStyle = 0
        }
        var bookshelfLayout = AppConfig.bookshelfLayout
        var bookshelfSort = AppConfig.bookshelfSort
        var showBookname = AppConfig.showBookname
        var listItemStyle = AppConfig.bookshelfListItemStyle
        var listIntroLines = AppConfig.bookshelfListIntroLines
        if (bookshelfLayout !in 0..6) {
            bookshelfLayout = 0
            AppConfig.bookshelfLayout = 0
        }
        if (bookshelfSort !in 0..5) {
            bookshelfSort = 0
            AppConfig.bookshelfSort = 0
        }
        if (showBookname !in 0..2) {
            showBookname = 0
            AppConfig.showBookname = 0
        }
        if (listItemStyle !in 0..2) {
            listItemStyle = 0
            AppConfig.bookshelfListItemStyle = 0
        }
        if (listIntroLines !in 0..3) {
            listIntroLines = 2
            AppConfig.bookshelfListIntroLines = 2
        }
        showDialogFragment(
            BookshelfConfigDialog.create(
                initialValues = BookshelfConfigValues(
                    groupStyle = AppConfig.bookGroupStyle,
                    showUnread = AppConfig.showUnread,
                    showLastUpdateTime = AppConfig.showLastUpdateTime,
                    showWaitUpCount = AppConfig.showWaitUpCount,
                    showFastScroller = AppConfig.showBookshelfFastScroller,
                    returnToTopAfterRead = AppConfig.bookshelfReturnToTopAfterRead,
                    layout = bookshelfLayout,
                    sort = bookshelfSort,
                    showBookname = showBookname,
                    listItemStyle = listItemStyle,
                    listIntroLines = listIntroLines,
                    margin = AppConfig.bookshelfMargin
                ),
                onPreviewMarginChange = ::previewBookshelfMargin,
                onApply = { values ->
                    applyBookshelfConfig(
                        previousLayout = bookshelfLayout,
                        previousSort = bookshelfSort,
                        previousShowBookname = showBookname,
                        values = values
                    )
                }
            )
        )
    }

    private fun previewBookshelfMargin(margin: Int) {
        val normalizedMargin = margin.coerceIn(0, 60)
        if (AppConfig.bookshelfMargin == normalizedMargin) {
            return
        }
        AppConfig.bookshelfMargin = normalizedMargin
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun applyBookshelfConfig(
        previousLayout: Int,
        previousSort: Int,
        previousShowBookname: Int,
        values: BookshelfConfigValues
    ) {
        dismissBookshelfTransientUi()
        var notifyMain = false
        var refreshBookshelf = false
        var structureChanged = false
        val groupStyle = values.groupStyle.coerceIn(0, 1)
        val layout = values.layout.coerceIn(0, 6)
        val sort = values.sort.coerceIn(0, 5)
        val showBookname = values.showBookname.coerceIn(0, 2)
        val listItemStyle = values.listItemStyle.coerceIn(0, 2)
        val listIntroLines = values.listIntroLines.coerceIn(0, 3)
        val margin = values.margin.coerceIn(0, 60)
        if (AppConfig.bookGroupStyle != groupStyle) {
            AppConfig.bookGroupStyle = groupStyle
            notifyMain = true
        }
        if (previousShowBookname != showBookname) {
            AppConfig.showBookname = showBookname
            structureChanged = true
        }
        if (AppConfig.bookshelfMargin != margin) {
            AppConfig.bookshelfMargin = margin
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfListItemStyle != listItemStyle) {
            AppConfig.bookshelfListItemStyle = listItemStyle
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfListIntroLines != listIntroLines) {
            AppConfig.bookshelfListIntroLines = listIntroLines
            refreshBookshelf = true
        }
        if (AppConfig.showUnread != values.showUnread) {
            AppConfig.showUnread = values.showUnread
            refreshBookshelf = true
        }
        if (AppConfig.showLastUpdateTime != values.showLastUpdateTime) {
            AppConfig.showLastUpdateTime = values.showLastUpdateTime
            refreshBookshelf = true
        }
        if (AppConfig.showWaitUpCount != values.showWaitUpCount) {
            AppConfig.showWaitUpCount = values.showWaitUpCount
            activityViewModel.postUpBooksLiveData(true)
        }
        if (AppConfig.showBookshelfFastScroller != values.showFastScroller) {
            AppConfig.showBookshelfFastScroller = values.showFastScroller
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfReturnToTopAfterRead != values.returnToTopAfterRead) {
            AppConfig.bookshelfReturnToTopAfterRead = values.returnToTopAfterRead
        }
        if (previousSort != sort) {
            AppConfig.bookshelfSort = sort
            upSort()
        }
        if (previousLayout != layout) {
            AppConfig.bookshelfLayout = layout
            if (AppConfig.bookshelfLayout < 2) {
                activityViewModel.booksGridRecycledViewPool.clear()
            } else {
                activityViewModel.booksListRecycledViewPool.clear()
            }
            structureChanged = true
        }
        if (notifyMain) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        } else if (structureChanged) {
            view?.post {
                postEvent(EventBus.BOOKSHELF_STRUCTURE_CHANGED, "")
            }
        } else if (refreshBookshelf) {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        } else if (notifyMain) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = getString(R.string.import_bookshelf),
                hint = "url/json",
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                neutralText = getString(R.string.select_file),
                onPositive = {
                    viewModel.importBookshelf(it, groupId)
                },
                onNeutral = {
                    importBookshelf.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                }
            )
        )
    }

}
