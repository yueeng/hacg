@file:Suppress("PrivatePropertyName")

package io.github.yueeng.hacg

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionMenu
import com.gun0912.tedpermission.TedPermission
import com.squareup.picasso.Picasso
import org.jetbrains.anko.childrenRecursiveSequence
import org.jetbrains.anko.doAsync
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import java.util.*

/**
 * Info activity
 * Created by Rain on 2015/5/12.
 */

class InfoActivity : BaseSlideCloseActivity() {
    private val _article: Article by lazy { intent.getParcelableExtra<Article>("article")!! }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_info)

        val manager = supportFragmentManager

        val fragment = manager.findFragmentById(R.id.container)?.takeIf { it is InfoFragment }
                ?: InfoFragment().arguments(Bundle().parcelable("article", _article))

        manager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    override fun onBackPressed() {
        supportFragmentManager.findFragmentById(R.id.container)?.let { it as InfoFragment }?.takeIf { it.onBackPressed() }
                ?: super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class InfoFragment : Fragment() {
    private val _article: Article by lazy { arguments!!.getParcelable<Article>("article")!! }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.fragment_info, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val activity = activity as AppCompatActivity
        activity.setSupportActionBar(view.findViewById(R.id.toolbar))
        activity.supportActionBar?.setLogo(R.mipmap.ic_launcher)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        activity.title = _article.title
        view.findViewById<ViewPager2>(R.id.container).adapter = InfoAdapter(this)
    }

    inner class InfoAdapter(fm: Fragment) : FragmentStateAdapter(fm) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> InfoWebFragment().arguments(arguments)
                1 -> InfoCommentFragment().arguments(arguments)
                else -> throw IllegalArgumentException()
            }
        }

    }

    fun onBackPressed(): Boolean =
            view?.findViewById<View>(R.id.container /*drawer*/)?.let { it as? ViewPager2 }?.takeIf { it.currentItem > 0 }
                    ?.let { it.currentItem = 0; true } ?: false
}

class InfoWebFragment : Fragment() {
    private val _article: Article by lazy { arguments!!.getParcelable<Article>("article")!! }
    private val _web = ViewBinder<Pair<String, String>?, WebView>(null) { view, value -> if (value != null) view.loadDataWithBaseURL(value.second, value.first, "text/html", "utf-8", null) }
    private val _error = object : ErrorBinder(false) {
        override fun retry(): Unit = query(_article.link!!)
    }
    private val _magnet = ViewBinder<List<String>, View>(listOf()) { view, value -> view.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE }

    private val _progress = ViewBinder<Boolean, ProgressBar>(false) { view, value ->
        view.isIndeterminate = value
        view.visibility = if (value) View.VISIBLE else View.INVISIBLE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_info_web, container, false).also { root ->
                _error + root.findViewById(R.id.image1)
                val menu: FloatingActionMenu = root.findViewById(R.id.menu1)
                menu.menuButtonColorNormal = randomColor()
                menu.menuButtonColorPressed = randomColor()
                menu.menuButtonColorRipple = randomColor()
                val click = View.OnClickListener { v ->
                    when (v.id) {
                        R.id.button1 -> openWeb(activity!!, _article.link!!)
                        R.id.button2 -> view?.findViewById<ViewPager2>(R.id.container)?.currentItem = 1
                        R.id.button4 -> share()
                    }
                    view?.findViewById<FloatingActionMenu>(R.id.menu1)?.close(true)
                }
                listOf(R.id.button1, R.id.button2, R.id.button4)
                        .map { root.findViewById<View>(it) }.forEach {
                            it.setOnClickListener(click)
                        }

                _progress + root.findViewById(R.id.progress)
                _magnet + root.findViewById<View>(R.id.button5).also {

                    it.setOnClickListener(object : View.OnClickListener {
                        val max = 3
                        var magnet = 3
                        var toast: Toast? = null

                        override fun onClick(v: View): Unit = when {
                            magnet == max && _magnet().isNotEmpty() -> {
                                AlertDialog.Builder(activity!!)
                                        .setTitle(R.string.app_magnet)
                                        .setSingleChoiceItems(_magnet().map { m -> "${if (m.contains(",")) "baidu" else "magnet"}:$m" }.toTypedArray(), 0, null)
                                        .setNegativeButton(R.string.app_cancel, null)
                                        .setPositiveButton(R.string.app_open) { d, _ ->
                                            val pos = (d as AlertDialog).listView.checkedItemPosition
                                            val item = _magnet()[pos]
                                            val link = if (item.contains(",")) {
                                                val baidu = item.split(",")
                                                context?.clipboard(getString(R.string.app_magnet), baidu.last())
                                                "https://yun.baidu.com/s/${baidu.first()}"
                                            } else "magnet:?xt=urn:btih:${_magnet()[pos]}"
                                            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(link)), getString(R.string.app_magnet)))
                                        }
                                        .setNeutralButton(R.string.app_copy) { d, _ ->
                                            val pos = (d as AlertDialog).listView.checkedItemPosition
                                            val item = _magnet()[pos]
                                            val link = if (item.contains(",")) "https://yun.baidu.com/s/${item.split(",").first()}" else "magnet:?xt=urn:btih:${_magnet()[pos]}"
                                            context?.clipboard(getString(R.string.app_magnet), link)
                                        }.create().show()
                                menu.close(true)
                            }
                            magnet < max -> {
                                magnet += 1
                                toast?.cancel()
                                toast = Toast.makeText(activity!!, (0 until magnet).joinToString("") { "..." }, Toast.LENGTH_SHORT).also { t -> t.show() }
                            }
                            else -> Unit
                        }
                    })
                }
                val web: WebView = root.findViewById(R.id.web)
                val settings = web.settings
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val uri = Uri.parse(url)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri.scheme))
                        return true
                    }

                    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                            when (request?.url?.scheme?.toLowerCase(Locale.getDefault())) {
                                "http", "https" -> {
                                    val call = okhttp3.Request.Builder().method(request.method, null).url(request.url.toString()).apply {
                                        request.requestHeaders?.forEach { header(it.key, it.value) }
                                    }.build()
                                    try {
                                        val response = okhttp.newCall(call).execute()
                                        WebResourceResponse(response.header("content-type", "text/html; charset=UTF-8"),
                                                response.header("content-encoding", "utf-8"),
                                                response.body?.byteStream())
                                    } catch (_: Exception) {
                                        super.shouldInterceptRequest(view, request)
                                    }
                                }
                                else -> super.shouldInterceptRequest(view, request)
                            }
                }
                web.addJavascriptInterface(JsFace(), "hacg")
                _web + web
                listOf(R.id.button1, R.id.button2, R.id.button4, R.id.button5)
                        .map { root.findViewById<View>(it) }.mapNotNull { it as? FloatingActionButton }.forEach { b ->
                            b.colorNormal = randomColor()
                            b.colorPressed = randomColor()
                            b.colorRipple = randomColor()
                        }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query(_article.link!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        _web.each { it.destroy() }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun share(url: String? = null) {
        fun share(uri: Uri? = null) {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri?.toString() ?: _article.link)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.isNotEmpty() }
                    ?: "text/plain"
            val title = _article.title
            val intro = _article.content
            val link = _article.link
            val share = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, title)
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, "$title\n$intro $link")
                    .putExtra(Intent.EXTRA_REFERRER, Uri.parse(link))
            uri?.let { share.putExtra(Intent.EXTRA_STREAM, uri) }
            startActivity(Intent.createChooser(share, title))
        }
        url?.httpDownloadAsync(context!!) {
            it?.let { file ->
                share(FileProvider.getUriForFile(activity!!, "${BuildConfig.APPLICATION_ID}.fileprovider", file))
            } ?: share()
        } ?: share()
    }

    @Suppress("unused")
    inner class JsFace {
        @JavascriptInterface
        fun play(name: String, url: String) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(url), "video/mp4"), name))
        }

        @JavascriptInterface
        fun save(url: String) {
            activity?.runOnUiThread {
                val uri = Uri.parse(url)
                val image = ImageView(activity)
                image.adjustViewBounds = true
                Picasso.with(activity).load(uri).placeholder(R.drawable.loading).into(image)
                val alert = AlertDialog.Builder(activity!!)
                        .setView(image)
                        .setNeutralButton(R.string.app_share) { _, _ -> share(url) }
                        .setPositiveButton(R.string.app_save) { _, _ ->
                            TedPermission.with(activity)
                                    .onPermissionGranted {
                                        val name = uri.path?.split("/")?.last()
                                                ?: UUID.randomUUID().toString()
                                        val ext = MimeTypeMap.getFileExtensionFromUrl(name)
                                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                                        val manager = HAcgApplication.instance.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        manager.enqueue(Request(uri).apply {
                                            setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "hacg/$name")
                                            setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            setTitle(name)
                                            setMimeType(mime)
                                        })
                                    }
                                    .setDeniedCloseButtonText(R.string.app_close)
                                    .setGotoSettingButtonText(R.string.app_settings)
                                    .setDeniedMessage(R.string.permission_write_external_storage)
                                    .setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    .check()
                        }
                        .setNegativeButton(R.string.app_cancel, null)
                        .create()
                image.setOnClickListener { alert.dismiss() }
                alert.show()
            }
        }
    }

    fun query(url: String) {
        if (_progress()) {
            return
        }
        _error * false
        _progress * true
        doAsync {
            val entry = url.httpGet()?.jsoup { dom ->
                dom.select(".entry-content").let { entry ->
                    val clean = Jsoup.clean(entry.html(), url, Whitelist.basicWithImages()
                            .addTags("audio", "video", "source")
                            .addAttributes("audio", "controls", "src")
                            .addAttributes("video", "controls", "src")
                            .addAttributes("source", "type", "src", "media"))

                    Jsoup.parse(clean, url).select("body").also { e ->
                        e.select("[width],[height]").forEach { it.removeAttr("width").removeAttr("height") }
                        e.select("img[src]").forEach {
                            it.attr("data-original", it.attr("src"))
                                    .addClass("lazy")
                                    .removeAttr("src")
                                    .after("""<a href="javascript:hacg.save('${it.attr("data-original")}');">下载此图</a>""")
                        }
                    }
                }
            }
            val html = entry?.let {
                HAcgApplication.instance.assets.open("template.html").bufferedReader()
                        .readText().replace("{{title}}", _article.title).replace("{{body}}", entry.html())
            }
            val magnet = entry?.text()?.magnet()?.toList() ?: emptyList()
            autoUiThread {
                when (html) {
                    null -> {
                        _error * (_web() == null)
                    }
                    else -> {
                        _magnet * magnet
                        _web * (html to url)
                    }
                }
                _progress * false
            }
        }
    }
}

class InfoCommentFragment : Fragment() {
    private val _article: Article by lazy { arguments!!.getParcelable<Article>("article")!! }
    private val _adapter by lazy { CommentAdapter() }

    private val _post = mutableMapOf<String, String>()
    private var _postParentId: Int? = 0
    private var _postOffset: Int = 0
    private val CONFIG_AUTHOR = "config.author"
    private val CONFIG_EMAIL = "config.email"
    private val AUTHOR = "wc_name"
    private val EMAIL = "wc_email"
    private var COMMENT = "wc_comment"
    private var sorting: Sorting = Sorting.Vote

    enum class Sorting(val sort: String) {
        Vote("by_vote"), Newest("newest"), Oldest("oldest")
    }

    private val COMMENTURL
        get() = "${HAcg.wordpress}/wp-content/plugins/wpdiscuz/utils/ajax/wpdiscuz-ajax.php"

    private val _progress = ViewBinder<Boolean, SwipeRefreshLayout>(false) { view, value -> view.post { view.isRefreshing = value } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_info_list, container, false).also { root ->
            val list: RecyclerView = root.findViewById(R.id.list1)
            list.layoutManager = LinearLayoutManager(activity)
            list.setHasFixedSize(true)
            list.adapter = _adapter
            list.loading { query() }

            _progress + root.findViewById(R.id.swipe)
            _progress.each {
                it.setOnRefreshListener {
                    _postOffset = 0
                    _postParentId = 0
                    _adapter.clear()
                    query()
                }
            }
            root.findViewById<FloatingActionButton>(R.id.button3).apply {
                setOnClickListener { comment(null) }
                colorNormal = randomColor()
                colorPressed = randomColor()
                colorRipple = randomColor()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        val preference = PreferenceManager.getDefaultSharedPreferences(activity)
        _post += (AUTHOR to preference.getString(CONFIG_AUTHOR, "")!!)
        _post += (EMAIL to preference.getString(CONFIG_EMAIL, "")!!)
        _post["action"] = "wpdAddComment"
        _post["submit"] = "发表评论"
        _post["postId"] = "${_article.id}"
        query()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_comment, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.vote, R.id.newest, R.id.oldest -> {
            sorting = when (item.itemId) {
                R.id.oldest -> Sorting.Oldest
                R.id.newest -> Sorting.Newest
                else -> Sorting.Vote
            }
            _postOffset = 0
            _postParentId = 0
            _adapter.clear()
            query()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    inner class CommentHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.text1)
        val text2: TextView = view.findViewById(R.id.text2)
        val text3: TextView = view.findViewById(R.id.text3)
        val text4: TextView = view.findViewById(R.id.text4)
        val image: ImageView = view.findViewById(R.id.image1)
        private val list: RecyclerView = view.findViewById(R.id.list1)
        val adapter = CommentAdapter()
        val context: Context = view.context

        init {
            list.adapter = adapter
            list.layoutManager = LinearLayoutManager(context)
            list.setHasFixedSize(true)
            view.setOnClickListener { v ->
                v.tag?.let { it as Comment }?.let { comment(it, adapterPosition) }
            }
        }
    }

    inner class MsgHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.text1)
    }

    inner class CommentAdapter : DataAdapter<Any, RecyclerView.ViewHolder>() {

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is CommentHolder -> {
                    val item = data[position] as Comment
                    holder.itemView.tag = item
                    holder.text1.text = item.user
                    holder.text2.text = item.content
                    holder.text3.text = item.time
                    holder.text4.text = item.moderation
                    holder.text4.visibility = if (item.moderation.isEmpty()) View.GONE else View.VISIBLE
                    holder.adapter.clear()
                    holder.adapter.addAll(item.children)

                    if (item.face.isEmpty()) {
                        holder.image.setImageResource(R.mipmap.ic_launcher)
                    } else {
                        Picasso.with(holder.context).load(item.face).placeholder(R.mipmap.ic_launcher).into(holder.image)
                    }
                }
                is MsgHolder -> holder.text1.text = data[position] as String
            }
        }

        private val CommentTypeComment = 0
        private val CommentTypeMsg = 1
        override fun getItemViewType(position: Int): Int = when (data[position]) {
            is Comment -> CommentTypeComment
            else -> CommentTypeMsg
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            CommentTypeComment -> CommentHolder(parent.inflate(R.layout.comment_item))
            else -> MsgHolder(parent.inflate(R.layout.list_msg_item))
        }
    }

    private fun query() {
        if (_progress() || _postParentId == null) {
            return
        }
        _progress * true
        doAsync {
            val json = COMMENTURL.httpPost(mapOf(
                    "action" to "wpdLoadMoreComments",
                    "sorting" to sorting.sort,
                    "offset" to "$_postOffset",
                    "lastParentId" to "$_postParentId",
                    "isFirstLoad" to (if (_postOffset == 0) "1" else "0"),
                    "wpdType" to "",
                    "postId" to "${_article.id}"))
            val comments = gson.fromJsonOrNull<JWpdiscuzComment>(json?.first)
            val list = Jsoup.parse(comments?.data?.comment_list ?: "", json?.second ?: "")
                    .select("body>.wpd-comment").map { Comment(it) }.toList()
            autoUiThread {
                if (comments?.data != null) {
                    if (comments.data.is_show_load_more) {
                        _postParentId = comments.data.last_parent_id.toIntOrNull()
                        _postOffset++
                    } else {
                        _postParentId = null
                        _postOffset = 0
                    }
                }
                _adapter.data.lastOrNull()?.let { it as String }?.let {
                    _adapter.remove(it)
                }
                _adapter.addAll(list)
                val (d, u) = (_adapter.size == 0) to (_postParentId == null)
                _adapter.add(when {
                    d && u -> getString(R.string.app_list_empty)
                    u -> getString(R.string.app_list_complete)
                    else -> getString(R.string.app_list_loading)
                })
                _progress * false
            }
        }
    }

    fun comment(c: Comment?, pos: Int? = null) {
        if (c == null) {
            commenting(c, pos)
            return
        }
        AlertDialog.Builder(activity!!)
                .setTitle(c.user)
                .setMessage(c.content)
                .setPositiveButton(R.string.comment_review) { _, _ -> commenting(c, pos) }
                .setNegativeButton(R.string.app_cancel, null)
                .setNeutralButton(R.string.app_copy) { _, _ ->
                    val clipboard = activity!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(c.user, c.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(activity, activity!!.getString(R.string.app_copied, c.content), Toast.LENGTH_SHORT).show()
                }.create().apply {
                    setOnShowListener { dialog ->
                        dialog.let { it as? AlertDialog }?.window?.decorView?.childrenRecursiveSequence()
                                ?.mapNotNull { it as? TextView }?.filter { it !is Button }
                                ?.forEach { it.setTextIsSelectable(true) }
                    }
                }.show()
    }

    @SuppressLint("InflateParams")
    private fun commenting(c: Comment?, pos: Int? = null) {
        val url = COMMENTURL
        val input = LayoutInflater.from(activity!!).inflate(R.layout.comment_post, null)
        val author: EditText = input.findViewById(R.id.edit1)
        val email: EditText = input.findViewById(R.id.edit2)
        val content: EditText = input.findViewById(R.id.edit3)
        author.setText(_post[AUTHOR])
        email.setText(_post[EMAIL])
        content.setText(_post[COMMENT] ?: "")
        _post["wpdiscuz_unique_id"] = (c?.id ?: "0_0")
        _post["wc_comment_depth"] = "${(c?.depth ?: 1)}"

        fun fill() {
            _post[AUTHOR] = author.text.toString()
            _post[EMAIL] = email.text.toString()
            _post[COMMENT] = content.text.toString()
            val preference = PreferenceManager.getDefaultSharedPreferences(activity!!)
            preference.edit().putString(CONFIG_AUTHOR, _post[AUTHOR]).putString(CONFIG_EMAIL, _post[EMAIL]).apply()
        }

        AlertDialog.Builder(activity!!)
                .setTitle(if (c != null) getString(R.string.comment_review_to, c.user) else getString(R.string.comment_title))
                .setView(input)
                .setPositiveButton(R.string.comment_submit) { _, _ ->
                    fill()
                    if (url.isEmpty() || listOf(AUTHOR, EMAIL, COMMENT).map { _post[it] }.any { it.isNullOrEmpty() }) {
                        Toast.makeText(activity!!, getString(R.string.comment_verify), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    _progress * true
                    doAsync {
                        val result = url.httpPost(_post.toMap())
                        val json = gson.fromJsonOrNull<JWpdiscuzCommentResult>(result?.first)
                        val review = Jsoup.parse(json?.data?.message ?: "", result?.second ?: "")
                                .select("body>.wpd-comment").map { Comment(it) }.firstOrNull()
                        autoUiThread {
                            _progress * false
                            if (review == null) {
                                Toast.makeText(activity!!, json?.data?.code ?: result?.first, Toast.LENGTH_LONG).show()
                                return@autoUiThread
                            }
                            _post[COMMENT] = ""
                            if (c != null) {
                                c.children.add(review)
                                _adapter.notifyItemChanged(pos!!)
                            } else {
                                _adapter.add(review, 0)
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.app_cancel, null)
                .setOnDismissListener { fill() }
                .create().show()
    }
}