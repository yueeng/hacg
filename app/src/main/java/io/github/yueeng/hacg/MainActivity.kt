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
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.squareup.picasso.Picasso
import io.github.yueeng.hacg.databinding.*
import kotlinx.android.parcel.Parcelize
import org.jetbrains.anko.doAsync
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityMainBinding.inflate(layoutInflater).apply {
            setSupportActionBar(toolbar)
            supportActionBar?.setLogo(R.mipmap.ic_launcher)
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

    private fun checkVersion(toast: Boolean = false): Future<Unit> = doAsync {
        val result = "${HAcg.RELEASE}/latest".httpGet()?.jsoup { dom ->
            Triple(
                    dom.select("span.css-truncate-target").firstOrNull()?.text() ?: "",
                    dom.select(".markdown-body").html().trim(),
                    dom.select(".release a[href$=.apk]").firstOrNull()?.attr("abs:href")
            )
        }?.let { (v: String, t: String, u: String?) ->
            if (versionBefore(version(this@MainActivity), v)) Triple(v, t, u) else null
        }
        autoUiThread {
            result?.let { (v: String, t: String, u: String?) ->
                MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.app_update_new, version(this@MainActivity), v))
                        .setMessage(t.html)
                        .setPositiveButton(R.string.app_update) { _, _ -> openWeb(this@MainActivity, u!!) }
                        .setNeutralButton(R.string.app_publish) { _, _ -> openWeb(this@MainActivity, HAcg.RELEASE) }
                        .setNegativeButton(R.string.app_cancel, null)
                        .create().show()
            } ?: {
                if (toast) {
                    Toast.makeText(this@MainActivity, getString(R.string.app_update_none, version(this@MainActivity)), Toast.LENGTH_SHORT).show()
                }
                checkConfig()
            }()
        }
    }

    private fun checkConfig(toast: Boolean = false): Unit = HAcg.update(this, toast) {
        reload()
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
                doAsync {
                    val good = HAcg.hosts().toList().pmap { u -> (u to u.test()) }.filter { it.second.first }.minByOrNull { it.second.second }
                    autoUiThread {
                        if (good != null) {
                            HAcg.host = good.first
                            toast(getString(R.string.settings_config_auto_choose, good.first))
                            reload()
                        } else {
                            toast(R.string.settings_config_auto_failed)
                        }
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
                        .setTitle("${getString(R.string.app_name)} ${version(this)}")
                        .setItems(arrayOf(getString(R.string.app_name))) { _, _ -> openWeb(this@MainActivity, HAcg.wordpress) }
                        .setPositiveButton(R.string.app_publish) { _, _ -> openWeb(this@MainActivity, HAcg.RELEASE) }
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
        supportActionBar?.setLogo(R.mipmap.ic_launcher)
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

class ArticleViewModel(handle: SavedStateHandle) : ViewModel() {
    val busy = handle.getLiveData("busy", false)
    val error = handle.getLiveData("error", false)
}

class ArticleViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ArticleViewModel(handle) as T
}

class ArticleFragment : Fragment() {
    private val viewModel: ArticleViewModel by viewModels { ArticleViewModelFactory(this) }
    private val adapter by lazy { ArticleAdapter() }
    private var url: String? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (saved != null) {
            val data = saved.getParcelableArray("data")
            if (data != null && data.isNotEmpty()) {
                adapter.addAll(data.toList())
                return
            }
        }
        query(defurl)
    }

    private val defurl: String
        get() = requireArguments().getString("url")!!.let { uri ->
            if (uri.startsWith("/")) "${HAcg.web}$uri" else uri
        }

    override fun onSaveInstanceState(out: Bundle) {
        out.putParcelableArray("data", adapter.data.toTypedArray())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FragmentListBinding.inflate(inflater, container, false).apply {
                viewModel.busy.observe(viewLifecycleOwner, { swipe.isRefreshing = it })
                viewModel.error.observe(viewLifecycleOwner, { image1.visibility = if (it) View.VISIBLE else View.INVISIBLE })
                image1.setOnClickListener { query(defurl, retry = true) }
                swipe.setOnRefreshListener {
                    adapter.clear()
                    query(defurl)
                }
                recycler.setHasFixedSize(true)
                recycler.adapter = adapter
                recycler.loading { query(url) }
            }.root

    private fun query(uri: String?, retry: Boolean = false) {
        if (viewModel.busy.value == true || uri.isNullOrEmpty()) return
        viewModel.busy.postValue(true)
        viewModel.error.postValue(false)
        doAsync {
            val result = uri.httpGet()?.jsoup { dom ->
                dom.select("article").map { o -> Article(o) }.toList() to (dom.select("#wp_page_numbers a").lastOrNull()
                        ?.takeIf { ">" == it.text() }?.attr("abs:href")
                        ?: dom.select("#nav-below .nav-previous a").firstOrNull()?.attr("abs:href"))
            }

            autoUiThread {
                when (result) {
                    null -> {
                        viewModel.error.postValue(adapter.size == 0)
                        if (adapter.size == 0) if (retry) activity?.openOptionsMenu() else activity?.toast(R.string.app_network_retry)
                    }
                    else -> {
                        url = result.second
                        adapter.data.lastOrNull()?.let { it as? MsgItem }?.let {
                            adapter.remove(it)
                        }
                        adapter.addAll(result.first)
                        val (d, u) = adapter.data.isEmpty() to url.isNullOrEmpty()
                        val msg = when {
                            d && u -> R.string.app_list_empty
                            !d && u -> R.string.app_list_complete
                            else -> R.string.app_list_loading
                        }
                        adapter.add(MsgItem(getString(msg)))
                    }
                }
                viewModel.busy.postValue(false)
            }
        }
    }

    inner class ArticleHolder(private val binding: ArticleItemBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {
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
                val span = item.expend.spannable(string = { it.name }, call = { tag -> startActivity(Intent(activity, ListActivity::class.java).putExtra("url", tag.url).putExtra("name", tag.name)) })
                binding.text3.text = span
                binding.text3.visibility = if (item.tags.isNotEmpty()) View.VISIBLE else View.GONE
                binding.text4.text = getString(R.string.app_list_time, datafmt.format(item.time ?: Date()), item.author?.name ?: "", item.comments)
                binding.text4.setTextColor(color)
                binding.text4.visibility = if (binding.text4.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                Picasso.with(requireContext()).load(item.img).placeholder(R.drawable.loading).error(R.drawable.placeholder).into(binding.image1)
            }

        init {
            binding.root.setOnClickListener(this)
            binding.root.tag = this
            binding.text3.movementMethod = LinkMovementMethod.getInstance()
        }

        override fun onClick(p0: View?) {
            startActivity(Intent(activity, InfoActivity::class.java).putExtra("article", article as Parcelable))
        }
    }

    @Parcelize
    class MsgItem(val msg: String) : Parcelable

    class MsgHolder(val binding: ListMsgItemBinding) : RecyclerView.ViewHolder(binding.root)

    val articleTypeArticle: Int = 0
    val articleTypeMsg: Int = 1
    val datafmt = SimpleDateFormat("yyyy-MM-dd hh:ss", Locale.getDefault())

    inner class ArticleAdapter : DataAdapter<Parcelable, RecyclerView.ViewHolder>() {
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ArticleHolder) {
                holder.article = data[position] as Article
            } else if (holder is MsgHolder) {
                holder.binding.text1.text = (data[position] as MsgItem).msg
            }
            if (position > last) {
                last = holder.bindingAdapterPosition
                ObjectAnimator.ofFloat(holder.itemView, "translationY", from, 0F)
                        .setDuration(1000).also { it.interpolator = interpolator }.start()
            }
        }

        private var last: Int = -1
        private val interpolator = DecelerateInterpolator(3F)
        private val from: Float = activity?.window?.decorView?.let { max(it.width, it.height) / 4F } ?: 300F

        override fun getItemViewType(position: Int): Int = when (data[position]) {
            is Article -> articleTypeArticle
            else -> articleTypeMsg
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            articleTypeArticle -> ArticleHolder(ArticleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> MsgHolder(ListMsgItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }
}
