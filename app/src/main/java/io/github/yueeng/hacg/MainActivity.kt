package io.github.yueeng.hacg

import android.animation.ObjectAnimator
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SearchRecentSuggestionsProvider
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.SearchRecentSuggestions
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.squareup.picasso.Picasso
import org.jetbrains.anko.doAsync
import java.util.concurrent.Future
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val pager: ViewPager2 by lazy { findViewById<ViewPager2>(R.id.container) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setLogo(R.mipmap.ic_launcher)
        val tabs: TabLayout = findViewById(R.id.tab)
        pager.adapter = ArticleFragmentAdapter(this)
        TabLayoutMediator(tabs, pager, TabLayoutMediator.TabConfigurationStrategy { tab, position -> tab.text = (pager.adapter as ArticleFragmentAdapter).getPageTitle(position) })
                .attach()

        if (state == null) {
            checkVersion()
        }
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
                AlertDialog.Builder(this@MainActivity)
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
        pager.adapter = ArticleFragmentAdapter(this)
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
                    val good = HAcg.hosts().toList().pmap { u -> (u to u.test()) }.filter { it.second.first }.minBy { it.second.second }
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
            R.id.philosophy -> {
                startActivity(Intent(this, WebActivity::class.java))
                true
            }
            R.id.about -> {
                AlertDialog.Builder(this)
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
        setContentView(R.layout.activity_list)
        setSupportActionBar(findViewById(R.id.toolbar))
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

class ArticleFragment : Fragment() {
    private var busy = ViewBinder<Boolean, SwipeRefreshLayout>(false) { view, value -> view.post { view.isRefreshing = value } }
    private val adapter by lazy { ArticleAdapter() }
    private var url: String? = null
    private val error = object : ErrorBinder(false) {
        override fun retry(): Unit = query(defurl, retry = true)
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        retainInstance = true

        if (saved != null) {
            val data = saved.getParcelableArray("data")
            if (data != null && data.isNotEmpty()) {
                adapter.addAll(data.map { it as Article })
                return
            }
            error * saved.getBoolean("error", false)
        }
        query(defurl)
    }

    private val defurl: String
        get() = arguments!!.getString("url")!!.let { uri ->
            if (uri.startsWith("/")) "${HAcg.web}$uri" else uri
        }

    override fun onSaveInstanceState(out: Bundle) {
        out.putParcelableArray("data", adapter.data.toTypedArray())
        out.putBoolean("error", error())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_list, container, false)
        error + root.findViewById(R.id.image1)
        busy + root.findViewById(R.id.swipe)
        busy.each {
            it.setOnRefreshListener {
                adapter.clear()
                query(defurl)
            }
        }
        val recycler: RecyclerView = root.findViewById(R.id.recycler)
        val layout = StaggeredGridLayoutManager(resources.getInteger(R.integer.main_list_column), StaggeredGridLayoutManager.VERTICAL)
        recycler.layoutManager = layout
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter
        recycler.loading { query(url) }

        return root
    }

    fun query(uri: String?, retry: Boolean = false) {
        if (busy() || uri.isNullOrEmpty()) return
        busy * true
        error * false
        doAsync {
            val result = uri.httpGet()?.jsoup { dom ->
                dom.select("article").map { o -> Article(o) }.toList() to
                        (dom.select("#wp_page_numbers a").lastOrNull()
                                ?.takeIf { ">" == it.text() }?.attr("abs:href")
                                ?: dom.select("#nav-below .nav-previous a").firstOrNull()?.attr("abs:href")
                                )
            }

            autoUiThread {
                when (result) {
                    null -> {
                        error * (adapter.size == 0)
                        if (error()) if (retry) activity?.openOptionsMenu() else activity?.toast(R.string.app_network_retry)
                    }
                    else -> {

                        url = result.second
                        adapter.data.lastOrNull()?.takeIf { it.link.isNullOrEmpty() }?.let {
                            adapter.remove(it)
                        }
                        adapter.addAll(result.first)
                        val (d, u) = adapter.data.isEmpty() to url.isNullOrEmpty()
                        val msg = when {
                            d && u -> R.string.app_list_empty
                            !d && u -> R.string.app_list_complete
                            else -> R.string.app_list_loading
                        }
                        adapter.add(Article(getString(msg)))
                    }
                }
                busy * false
            }
        }
    }

    private val click: View.OnClickListener = View.OnClickListener { v ->
        (v?.tag as? ArticleHolder)?.let { h ->
            startActivity(Intent(activity, InfoActivity::class.java).putExtra("article", h.article as Parcelable))
            //        ActivityCompat.startActivityForResult(getActivity,
            //          new Intent(getActivity, classOf[InfoActivity]).putExtra("article", h.article.asInstanceOf[Parcelable]),
            //          0,
            //          ActivityOptionsCompat.makeSceneTransitionAnimation(getActivity, (h.image1, "image")).toBundle)
        }
    }

    inner class ArticleHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        val context: Context get() = view.context
        val text1: TextView = view.findViewById(R.id.text1)
        val text2: TextView = view.findViewById(R.id.text2)
        val text3: TextView = view.findViewById(R.id.text3)
        val image1: ImageView = view.findViewById(R.id.image1)
        var article: Article? = null

        init {
            view.setOnClickListener(click)
            view.tag = this
            text3.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    inner class MsgHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.text1)
    }

    val articleTypeArticle: Int = 0
    val articleTypeMsg: Int = 1

    inner class ArticleAdapter : DataAdapter<Article, RecyclerView.ViewHolder>() {
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ArticleHolder) {
                val item = data[position]
                holder.article = item
                holder.text1.text = item.title
                holder.text1.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
                holder.text1.setTextColor(randomColor())
                holder.text2.text = item.content
                holder.text2.visibility = if (item.content?.isNotEmpty() == true) View.VISIBLE else View.GONE

                val span = item.expend.spannable(string = { it.name }, call = { tag -> startActivity(Intent(activity, ListActivity::class.java).putExtra("url", tag.url).putExtra("name", tag.name)) })
                holder.text3.text = span
                holder.text3.visibility = if (item.tags.isNotEmpty()) View.VISIBLE else View.GONE

                Picasso.with(holder.context).load(item.img).placeholder(R.drawable.loading).error(R.drawable.placeholder).into(holder.image1)

            } else if (holder is MsgHolder) {

                holder.text1.text = data[position].title
            }
            if (position > last) {
                last = holder.adapterPosition
                val anim = ObjectAnimator.ofFloat(holder.itemView, "translationY", from, 0F)
                        .setDuration(1000)
                anim.interpolator = interpolator
                anim.start()
            }
        }

        private var last: Int = -1
        private val interpolator = DecelerateInterpolator(3F)
        private val from: Float = activity?.windowManager?.defaultDisplay?.let { it as? Display }?.let { d ->
            val p = Point()
            d.getSize(p)
            max(p.x, p.y) / 4F
        } ?: 300F

        override fun getItemViewType(position: Int): Int = data[position]
                .takeIf { it.link?.isNotEmpty() == true }
                ?.let { articleTypeArticle }
                ?: articleTypeMsg

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            articleTypeArticle -> ArticleHolder(parent.inflate(R.layout.article_item))
            else -> MsgHolder(parent.inflate(R.layout.list_msg_item))
        }
    }

}
