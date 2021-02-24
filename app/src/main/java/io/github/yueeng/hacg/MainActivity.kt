package io.github.yueeng.hacg

import android.animation.ObjectAnimator
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SearchRecentSuggestionsProvider
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.SearchRecentSuggestions
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import io.github.yueeng.hacg.databinding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityMainBinding.inflate(layoutInflater).apply {
            setSupportActionBar(toolbar)
            container.adapter = ArticleFragmentAdapter(this@MainActivity)
            TabLayoutMediator(tab, container) { tab, position -> tab.text = (container.adapter as ArticleFragmentAdapter).getPageTitle(position) }.attach()
        }
        setContentView(binding.root)
        if (state == null) checkVersion()
    }

    private var last = 0L

    override fun onBackPressed() {
        if (System.currentTimeMillis() - last > 1500) {
            last = System.currentTimeMillis()
            this.toast(R.string.app_exit_confirm)
            return
        }
        finish()
    }

    private fun checkVersion(toast: Boolean = false) = lifecycleScope.launchWhenCreated {
        val release = "https://api.github.com/repos/yueeng/hacg/releases/latest".httpGetAwait()?.let {
            gson.fromJson(it.first, JGitHubRelease::class.java)
        }
        val ver = Version.from(release?.tagName)
        val apk = release?.assets?.firstOrNull { it.name == "app-release.apk" }?.browserDownloadUrl
        val local = version()
        if (local != null && ver != null && local < ver) {
            MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.app_update_new, local, ver))
                    .setMessage(release?.body ?: "")
                    .setPositiveButton(R.string.app_update) { _, _ -> openUri(apk) }
                    .setNeutralButton(R.string.app_publish) { _, _ -> openUri(HAcg.RELEASE) }
                    .setNegativeButton(R.string.app_cancel, null)
                    .create().show()
        } else {
            if (toast) Toast.makeText(this@MainActivity, getString(R.string.app_update_none, local), Toast.LENGTH_SHORT).show()
            checkConfig()
        }
    }

    private fun checkConfig(toast: Boolean = false): Job = lifecycleScope.launchWhenCreated {
        HAcg.update(this@MainActivity, toast) {
            reload()
        }
    }

    private fun reload() {
        ActivityMainBinding.bind(findViewById(R.id.coordinator)).container.adapter = ArticleFragmentAdapter(this)
    }

    class ArticleFragmentAdapter(fm: FragmentActivity) : FragmentStateAdapter(fm) {
        private val data = HAcg.categories.toList()

        fun getPageTitle(position: Int): CharSequence = data[position].second

        override fun getItemCount(): Int = data.size

        override fun createFragment(position: Int): Fragment =
                ArticleFragment().arguments(Bundle().string("url", data[position].first))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val search = menu.findItem(R.id.search).actionView as SearchView
        val manager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val info = manager.getSearchableInfo(ComponentName(this, ListActivity::class.java))
        search.setSearchableInfo(info)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.search_clear -> {
                val suggestions = SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
                suggestions.clearHistory()
                true
            }
            R.id.config -> {
                checkConfig(true)
                true
            }
            R.id.settings -> {
                HAcg.setHost(this) { reload() }
                true
            }
            R.id.auto -> {
                lifecycleScope.launchWhenCreated {
                    val good = withContext(Dispatchers.IO) { HAcg.hosts().pmap { u -> (u to u.test()) }.filter { it.second.first }.minByOrNull { it.second.second } }
                    if (good != null) {
                        HAcg.host = good.first
                        toast(getString(R.string.settings_config_auto_choose, good.first))
                        reload()
                    } else {
                        toast(R.string.settings_config_auto_failed)
                    }
                }
                true
            }
            R.id.user -> {
                startActivity(Intent(this, WebActivity::class.java).apply {
                    if (user != 0) putExtra("url", "${HAcg.philosophy}/profile/$user")
                    else putExtra("login", true)
                })
                true
            }
            R.id.philosophy -> {
                startActivity(Intent(this, WebActivity::class.java))
                true
            }
            R.id.about -> {
                MaterialAlertDialogBuilder(this)
                        .setTitle("${getString(R.string.app_name)} ${version()}")
                        .setItems(arrayOf(getString(R.string.app_name))) { _, _ -> openUri(HAcg.wordpress) }
                        .setPositiveButton(R.string.app_publish) { _, _ -> openUri(HAcg.RELEASE) }
                        .setNeutralButton(R.string.app_update_check) { _, _ -> checkVersion(true) }
                        .setNegativeButton(R.string.app_cancel, null)
                        .create().show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

class ListActivity : BaseSlideCloseActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val (url: String?, name: String?) = intent.let { i ->
            when {
                i.hasExtra("url") -> (i.getStringExtra("url") to i.getStringExtra("name"))
                i.hasExtra(SearchManager.QUERY) -> {
                    val key = i.getStringExtra(SearchManager.QUERY)
                    val suggestions = SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
                    suggestions.saveRecentQuery(key, null)
                    ("""${HAcg.wordpress}/?s=${Uri.encode(key)}&submit=%E6%90%9C%E7%B4%A2""" to key)
                }
                else -> null to null
            }
        }
        if (url == null) {
            finish()
            return
        }
        title = name
        val transaction = supportFragmentManager.beginTransaction()
        val fragment = supportFragmentManager.findFragmentById(R.id.container).takeIf { it is ArticleFragment }
                ?: ArticleFragment().arguments(Bundle().string("url", url))
        transaction.replace(R.id.container, fragment)
        transaction.commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

class SearchHistoryProvider : SearchRecentSuggestionsProvider() {
    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.SuggestionProvider"
        const val MODE: Int = DATABASE_MODE_QUERIES
    }

    init {
        setupSuggestions(AUTHORITY, MODE)
    }
}

class ArticlePagingSource(private val title: (String) -> Unit) : PagingSource<String, Article>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Article> = try {
        val dom = params.key!!.httpGetAwait()!!.jsoup()
        listOf("h1.page-title>span", "h1#site-title", "title").asSequence().map { dom.select(it).text() }
                .firstOrNull { it.isNotEmpty() }?.let(title::invoke)
        val articles = dom.select("article").map { o -> Article(o) }.toList()
        val next = (dom.select("#wp_page_numbers a").lastOrNull()
                ?.takeIf { ">" == it.text() }?.attr("abs:href")
                ?: dom.select("#nav-below .nav-previous a").firstOrNull()?.attr("abs:href"))
        LoadResult.Page(articles, null, next)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<String, Article>): String? = null
}

class ArticleViewModel(private val handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    var retry: Boolean
        get() = handle.get("retry") ?: false
        set(value) = handle.set("retry", value)
    val title = handle.getLiveData<String>("title")
    val source = Paging(handle, args?.getString("url")) { ArticlePagingSource { title.postValue(it) } }
    val data = handle.getLiveData<List<Article>>("data")
    val last = handle.getLiveData("last", -1)
}

class ArticleViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ArticleViewModel(handle, args) as T
}

class ArticleFragment : Fragment() {
    private val viewModel: ArticleViewModel by viewModels { ArticleViewModelFactory(this, bundleOf("url" to defurl)) }
    private val adapter by lazy { ArticleAdapter() }

    private val defurl: String
        get() = requireArguments().getString("url")!!.let { uri -> if (uri.startsWith("/")) "${HAcg.web}$uri" else uri }

    private fun query(refresh: Boolean = false) {
        lifecycleScope.launchWhenCreated {
            if (refresh) adapter.clear()
            val (list, _) = viewModel.source.query(refresh)
            if (list != null) adapter.addAll(list)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.last.value = adapter.last
        viewModel.data.value = adapter.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.last.value?.let { adapter.last = it }
        viewModel.data.value?.let { adapter.addAll(it) }
        if (adapter.itemCount == 0) query()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FragmentListBinding.inflate(inflater, container, false).apply {
                viewModel.source.state.observe(viewLifecycleOwner, {
                    adapter.state.postValue(it)
                    swipe.isRefreshing = it is LoadState.Loading
                    image1.visibility = if (it is LoadState.Error && adapter.itemCount == 0) View.VISIBLE else View.INVISIBLE
                    if (it is LoadState.Error && adapter.itemCount == 0) if (viewModel.retry) activity?.openOptionsMenu() else activity?.toast(R.string.app_network_retry)
                })
                if (requireActivity().title.isNullOrEmpty()) {
                    requireActivity().title = getString(R.string.app_name)
                    viewModel.title.observe(viewLifecycleOwner, {
                        requireActivity().title = it
                    })
                }
                image1.setOnClickListener {
                    viewModel.retry = true
                    query(true)
                }
                swipe.setOnRefreshListener { query(true) }
                recycler.setHasFixedSize(true)
                recycler.adapter = adapter.withLoadStateFooter(FooterAdapter({ adapter.itemCount }) {
                    query()
                })
                recycler.loading {
                    when (viewModel.source.state.value) {
                        LoadState.NotLoading(false) -> query()
                    }
                }
                lifecycleScope.launchWhenCreated {
                    adapter.refreshFlow.collectLatest {
                        recycler.scrollToPosition(0)
                    }
                }
            }.root

    class ArticleHolder(private val binding: ArticleItemBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        private val datafmt = SimpleDateFormat("yyyy-MM-dd hh:ss", Locale.getDefault())
        private val context = binding.root.context
        var article: Article? = null
            set(value) {
                field = value
                val item = value!!
                binding.text1.text = item.title
                binding.text1.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
                val color = randomColor()
                binding.text1.setTextColor(color)
                binding.text2.text = item.content
                binding.text2.visibility = if (item.content?.isNotEmpty() == true) View.VISIBLE else View.GONE
                val span = item.expend.spannable(string = { it.name }, call = { tag -> context.startActivity(Intent(context, ListActivity::class.java).putExtra("url", tag.url).putExtra("name", tag.name)) })
                binding.text3.text = span
                binding.text3.visibility = if (item.tags.isNotEmpty()) View.VISIBLE else View.GONE
                binding.text4.text = context.getString(R.string.app_list_time, datafmt.format(item.time ?: Date()), item.author?.name ?: "", item.comments)
                binding.text4.setTextColor(color)
                binding.text4.visibility = if (binding.text4.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                GlideApp.with(context).load(item.img).placeholder(R.drawable.loading).error(R.drawable.placeholder).into(binding.image1)
            }

        init {
            binding.root.setOnClickListener(this)
            binding.root.tag = this
            binding.text3.movementMethod = LinkMovementMethod.getInstance()
        }

        override fun onClick(p0: View?) {
            context.startActivity(Intent(context, InfoActivity::class.java).putExtra("article", article as Parcelable))
        }
    }

    class ArticleDiffCallback : DiffUtil.ItemCallback<Article>() {
        override fun areItemsTheSame(oldItem: Article, newItem: Article): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Article, newItem: Article): Boolean = oldItem == newItem
    }

    class ArticleAdapter : PagingAdapter<Article, ArticleHolder>(ArticleDiffCallback()) {
        var last: Int = -1
        private val interpolator = DecelerateInterpolator(3F)
        private val from: Float by lazy {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, HAcgApplication.instance.resources.displayMetrics)
        }

        override fun onBindViewHolder(holder: ArticleHolder, position: Int) {
            holder.article = data[position]
            if (position > last) {
                last = position
                ObjectAnimator.ofFloat(holder.itemView, View.TRANSLATION_Y.name, from, 0F)
                        .setDuration(1000).also { it.interpolator = interpolator }.start()
            }
        }

        override fun clear(): DataAdapter<Article, ArticleHolder> = super.clear().apply { last = -1 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleHolder =
                ArticleHolder(ArticleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}

class MsgHolder(private val binding: ListMsgItemBinding, retry: () -> Unit) : RecyclerView.ViewHolder(binding.root) {
    init {
        binding.root.setOnClickListener { if (state is LoadState.Error) retry() }
    }

    private var state: LoadState? = null
    fun bind(value: LoadState, empty: () -> Boolean) {
        state = value
        binding.text1.setText(when (value) {
            is LoadState.NotLoading -> when {
                value.endOfPaginationReached && empty() -> R.string.app_list_empty
                value.endOfPaginationReached -> R.string.app_list_complete
                else -> R.string.app_list_loadmore
            }
            is LoadState.Error -> R.string.app_list_failed
            else -> R.string.app_list_loading
        })
    }
}

class FooterAdapter(private val count: () -> Int, private val retry: () -> Unit) : LoadStateAdapter<MsgHolder>() {
    override fun displayLoadStateAsItem(loadState: LoadState): Boolean = when (loadState) {
        is LoadState.NotLoading -> count() != 0 || loadState.endOfPaginationReached
        is LoadState.Loading -> count() != 0
        else -> true
    }

    override fun onBindViewHolder(holder: MsgHolder, loadState: LoadState) {
        holder.bind(loadState) { count() == 0 }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): MsgHolder =
            MsgHolder(ListMsgItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)) { retry() }
}