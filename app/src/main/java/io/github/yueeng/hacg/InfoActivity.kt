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
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gun0912.tedpermission.TedPermission
import com.squareup.picasso.Picasso
import io.github.yueeng.hacg.databinding.FragmentInfoBinding
import io.github.yueeng.hacg.databinding.FragmentInfoWebBinding
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
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_info)
        val manager = supportFragmentManager

        val fragment = manager.findFragmentById(R.id.container)?.takeIf { it is InfoFragment }
                ?: InfoFragment().arguments(intent.extras)

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FragmentInfoBinding.inflate(inflater, container, false).also { binding ->
                val activity = activity as AppCompatActivity
                activity.setSupportActionBar(binding.toolbar)
                activity.supportActionBar?.setLogo(R.mipmap.ic_launcher)
                activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
                binding.container.adapter = InfoAdapter(this)
            }.root

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

    fun onBackPressed(): Boolean = FragmentInfoBinding.bind(requireView()).container
            .takeIf { it.currentItem > 0 }?.let { it.currentItem = 0; true } ?: false
}

class InfoWebViewModel : ViewModel() {
    val web = MutableLiveData<Pair<String, String>>()
    val error = MutableLiveData(false)
    val magnet = MutableLiveData<List<String>>()
    val progress = MutableLiveData(false)
}

class InfoWebViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = InfoWebViewModel() as T
}

class InfoWebFragment : Fragment() {
    private val viewModel: InfoWebViewModel by viewModels { InfoWebViewModelFactory(this) }
    private val _article by lazy { MutableLiveData<Article>(requireArguments().getParcelable("article")) }
    private val _url by lazy { _article.value?.link ?: requireArguments().getString("url")!! }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            FragmentInfoWebBinding.inflate(inflater, container, false).also { binding ->
                _article.observe(viewLifecycleOwner, { it?.title?.let { t -> requireActivity().title = t } })
                viewModel.error.observe(viewLifecycleOwner, { binding.image1.visibility = if (it) View.VISIBLE else View.INVISIBLE })
                binding.image1.setOnClickListener { query(_url) }
                binding.menu1.menuButtonColorNormal = randomColor()
                binding.menu1.menuButtonColorPressed = randomColor()
                binding.menu1.menuButtonColorRipple = randomColor()
                val click = View.OnClickListener { v ->
                    when (v.id) {
                        R.id.button1 -> openWeb(requireActivity(), _url)
                        R.id.button2 -> activity?.window?.decorView
                                ?.findViewByViewType<ViewPager2>(R.id.container)?.firstOrNull()?.currentItem = 1
                        R.id.button4 -> share()
                    }
                    view?.findViewById<FloatingActionMenu>(R.id.menu1)?.close(true)
                }
                listOf(binding.button1, binding.button2, binding.button4).forEach { it.setOnClickListener(click) }
                viewModel.progress.observe(viewLifecycleOwner, {
                    binding.progress.isIndeterminate = it
                    binding.progress.visibility = if (it) View.VISIBLE else View.INVISIBLE
                })
                viewModel.magnet.observe(viewLifecycleOwner, {
                    binding.button5.visibility = if (it.isNotEmpty()) View.VISIBLE else View.GONE
                })
                binding.button5.setOnClickListener(object : View.OnClickListener {
                    val max = 3
                    var magnet = 3
                    var toast: Toast? = null

                    override fun onClick(v: View): Unit = when {
                        magnet == max -> {
                            val magnets = viewModel.magnet.value ?: emptyList()
                            MaterialAlertDialogBuilder(activity!!)
                                    .setTitle(R.string.app_magnet)
                                    .setSingleChoiceItems(magnets.map { m -> "${if (m.contains(",")) "baidu" else "magnet"}:$m" }.toTypedArray(), 0, null)
                                    .setNegativeButton(R.string.app_cancel, null)
                                    .setPositiveButton(R.string.app_open) { d, _ ->
                                        val pos = (d as AlertDialog).listView.checkedItemPosition
                                        val item = magnets[pos]
                                        val link = if (item.contains(",")) {
                                            val baidu = item.split(",")
                                            context?.clipboard(getString(R.string.app_magnet), baidu.last())
                                            "https://yun.baidu.com/s/${baidu.first()}"
                                        } else "magnet:?xt=urn:btih:${magnets[pos]}"
                                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(link)), getString(R.string.app_magnet)))
                                    }
                                    .setNeutralButton(R.string.app_copy) { d, _ ->
                                        val pos = (d as AlertDialog).listView.checkedItemPosition
                                        val item = magnets[pos]
                                        val link = if (item.contains(",")) "https://yun.baidu.com/s/${item.split(",").first()}" else "magnet:?xt=urn:btih:${magnets[pos]}"
                                        context?.clipboard(getString(R.string.app_magnet), link)
                                    }.create().show()
                            binding.menu1.close(true)
                        }
                        magnet < max -> {
                            magnet += 1
                            toast?.cancel()
                            toast = Toast.makeText(activity!!, (0 until magnet).joinToString("") { "..." }, Toast.LENGTH_SHORT).also { t -> t.show() }
                        }
                        else -> Unit
                    }
                })
                CookieManager.getInstance().acceptThirdPartyCookies(binding.web)
                val settings = binding.web.settings
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                binding.web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (Article.getIdFromUrl(url) != null) {
                            startActivity(Intent(activity, InfoActivity::class.java).putExtra("url", url))
                        } else {
                            val uri = Uri.parse(url)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri.scheme))
                        }
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
                binding.web.addJavascriptInterface(JsFace(), "hacg")
                listOf(binding.button1, binding.button2, binding.button4, binding.button5).forEach { b ->
                    b.colorNormal = randomColor()
                    b.colorPressed = randomColor()
                    b.colorRipple = randomColor()
                }
                viewModel.web.observe(viewLifecycleOwner, { value ->
                    if (value != null) binding.web.loadDataWithBaseURL(value.second, value.first, "text/html", "utf-8", null)
                })
            }.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query(_url)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        FragmentInfoWebBinding.bind(requireView()).web.destroy()
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun share(url: String? = null) {
        fun share(uri: Uri? = null) {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri?.toString() ?: _url)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.isNotEmpty() }
                    ?: "text/plain"
            val title = _article.value?.title ?: ""
            val intro = _article.value?.content ?: ""
            val link = _url
            val share = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, title)
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, "$title\n$intro $link")
                    .putExtra(Intent.EXTRA_REFERRER, Uri.parse(link))
            uri?.let { share.putExtra(Intent.EXTRA_STREAM, uri) }
            startActivity(Intent.createChooser(share, title))
        }
        url?.httpDownloadAsync(requireContext()) {
            it?.let { file ->
                share(FileProvider.getUriForFile(requireActivity(), "${BuildConfig.APPLICATION_ID}.fileprovider", file))
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
                val alert = MaterialAlertDialogBuilder(activity!!)
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

    private fun query(url: String) {
        if (viewModel.progress.value == true) return
        viewModel.error.postValue(false)
        viewModel.progress.postValue(true)
        doAsync {
            val dom = url.httpGet()?.jsoup()
            val article = dom?.select("article")?.firstOrNull()?.let { Article(it) }
            val entry = dom?.select(".entry-content")?.let { entry ->
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
            val html = entry?.let {
                activity?.resources?.openRawResource(R.raw.template)?.bufferedReader()?.readText()
                        ?.replace("{{title}}", article?.title ?: "")
                        ?.replace("{{body}}", entry.html())
            }
            val magnet = entry?.text()?.magnet()?.toList() ?: emptyList()
            autoUiThread {
                if (article != null) _article.postValue(article)
                when (html) {
                    null -> {
                        viewModel.error.postValue(viewModel.web.value == null)
                    }
                    else -> {
                        viewModel.magnet.postValue(magnet)
                        viewModel.web.postValue(html to url)
                    }
                }
                viewModel.progress.postValue(false)
            }
        }
    }
}

class InfoCommentFragment : Fragment() {
    private val _article by lazy { MutableLiveData<Article>(requireArguments().getParcelable("article")) }
    private val _url by lazy { _article.value?.link ?: requireArguments().getString("url")!! }
    private val _id by lazy { _article.value?.id ?: Article.getIdFromUrl(_url) ?: 0 }
    private val _adapter by lazy { CommentAdapter() }

    private var _postParentId: Int? = 0
    private var _postOffset: Int = 0
    private val CONFIG_AUTHOR = "config.author"
    private val CONFIG_EMAIL = "config.email"
    private val CONFIG_COMMENT = "config.comment"
    private val AUTHOR = "wc_name"
    private val EMAIL = "wc_email"
    private var COMMENT = "wc_comment"
    private var sorting: Sorting = Sorting.Vote

    enum class Sorting(val sort: String) {
        Vote("by_vote"), Newest("newest"), Oldest("oldest")
    }

    private val Wpdiscuz
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

    inner class CommentHolder(view: View, func: () -> CommentAdapter) : RecyclerView.ViewHolder(view) {
        private val text1: TextView = view.findViewById(R.id.text1)
        private val text2: TextView = view.findViewById(R.id.text2)
        private val text3: TextView = view.findViewById(R.id.text3)
        private val text4: TextView = view.findViewById(R.id.text4)
        private val image: ImageView = view.findViewById(R.id.image1)
        private val button1: ImageView = view.findViewById(R.id.button1)
        private val button2: ImageView = view.findViewById(R.id.button2)

        private val list: RecyclerView = view.findViewById(R.id.list1)
        private val adapter = CommentAdapter()
        private val context: Context? get() = view?.context

        init {
            list.adapter = adapter
            list.layoutManager = LinearLayoutManager(context)
            list.setHasFixedSize(true)
            listOf(button1, button2).forEach { b ->
                b.setOnClickListener { view ->
                    val v = if (view.id == R.id.button1) -1 else 1
                    val item = itemView.tag as? Comment ?: return@setOnClickListener
                    vote(item, v) {
                        item.moderation = it
                        func().notifyItemChanged(adapterPosition, "moderation")
                    }
                }
            }
            view.setOnClickListener { v -> v.tag?.let { it as Comment }?.let { comment(it, adapterPosition) } }
        }

        fun bind(item: Comment, payloads: MutableList<Any>) {
            if (payloads.contains("moderation")) {
                text4.text = "${item.moderation}"
                return
            }
            itemView.tag = item
            text1.text = item.user
            text2.text = item.content
            text3.text = item.time
            text4.text = "${item.moderation}"
            adapter.clear()
            adapter.addAll(item.children)

            if (item.face.isEmpty()) {
                image.setImageResource(R.mipmap.ic_launcher)
            } else {
                Picasso.with(context).load(item.face).placeholder(R.mipmap.ic_launcher).into(image)
            }
        }
    }

    class MsgHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.text1)
    }

    inner class CommentAdapter : DataAdapter<Any, RecyclerView.ViewHolder>() {
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            when (holder) {
                is CommentHolder -> holder.bind(data[position] as Comment, payloads)
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
            CommentTypeComment -> CommentHolder(parent.inflate(R.layout.comment_item)) { return@CommentHolder this }
            else -> MsgHolder(parent.inflate(R.layout.list_msg_item))
        }
    }

    private fun query() {
        if (_progress() || _postParentId == null) {
            return
        }
        _progress * true
        doAsync {
            val json = Wpdiscuz.httpPost(mapOf(
                    "action" to "wpdLoadMoreComments",
                    "sorting" to sorting.sort,
                    "offset" to "$_postOffset",
                    "lastParentId" to "$_postParentId",
                    "isFirstLoad" to (if (_postOffset == 0) "1" else "0"),
                    "wpdType" to "",
                    "postId" to "$_id"))
            val comments = gson.fromJsonOrNull<JWpdiscuzComment>(json?.first)
            val list = Jsoup.parse(comments?.data?.commentList ?: "", json?.second ?: "")
                    .select("body>.wpd-comment").map { Comment(it) }.toList()
            autoUiThread {
                if (comments?.data != null) {
                    if (comments.data.isShowLoadMore) {
                        _postParentId = comments.data.lastParentId.toIntOrNull()
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

    fun vote(c: Comment?, v: Int, call: (Int) -> Unit) {
        if (c == null) return
        doAsync {
            val result = Wpdiscuz.httpPost(mapOf(
                    "action" to "wpdVoteOnComment",
                    "commentId" to "${c.id}",
                    "voteType" to "$v",
                    "postId" to "$_id"))
            autoUiThread {
                val succeed = gson.fromJsonOrNull<JWpdiscuzVoteSucceed>(result?.first ?: "")
                if (succeed?.success != true) {
                    val json = gson.fromJsonOrNull<JWpdiscuzVote>(result?.first ?: "")
                    Toast.makeText(requireActivity(), json?.data ?: result?.first, Toast.LENGTH_LONG).show()
                    return@autoUiThread
                }
                call(succeed.data.votes.toIntOrNull() ?: 0)
            }
        }
    }

    fun comment(c: Comment?, pos: Int? = null) {
        if (c == null) {
            commenting(c, pos)
            return
        }
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(c.user)
                .setMessage(c.content)
                .setPositiveButton(R.string.comment_review) { _, _ -> commenting(c, pos) }
                .setNegativeButton(R.string.app_cancel, null)
                .setNeutralButton(R.string.app_copy) { _, _ ->
                    val clipboard = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(c.user, c.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(activity, requireActivity().getString(R.string.app_copied, c.content), Toast.LENGTH_SHORT).show()
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
        val url = Wpdiscuz
        val input = LayoutInflater.from(requireActivity()).inflate(R.layout.comment_post, null)
        val author: EditText = input.findViewById(R.id.edit1)
        val email: EditText = input.findViewById(R.id.edit2)
        val content: EditText = input.findViewById(R.id.edit3)
        val post = mutableMapOf<String, String>()
        val preference = PreferenceManager.getDefaultSharedPreferences(activity)
        if (user != 0) {
            (author.parent as? View)?.visibility = View.GONE
            (email.parent as? View)?.visibility = View.GONE
        } else {
            post += (AUTHOR to preference.getString(CONFIG_AUTHOR, "")!!)
            post += (EMAIL to preference.getString(CONFIG_EMAIL, "")!!)
            author.setText(post[AUTHOR])
            email.setText(post[EMAIL])
        }
        post += (COMMENT to preference.getString(CONFIG_COMMENT, "")!!)
        content.setText(post[COMMENT] ?: "")
        post["action"] = "wpdAddComment"
        post["submit"] = "发表评论"
        post["postId"] = "$_id"
        post["wpdiscuz_unique_id"] = (c?.uniqueId ?: "0_0")
        post["wc_comment_depth"] = "${(c?.depth ?: 1)}"

        fun fill() {
            post[AUTHOR] = author.text.toString()
            post[EMAIL] = email.text.toString()
            post[COMMENT] = content.text.toString()
            preference.edit().putString(CONFIG_AUTHOR, post[AUTHOR])
                    .putString(CONFIG_EMAIL, post[EMAIL])
                    .putString(CONFIG_COMMENT, post[COMMENT]).apply()
        }

        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(if (c != null) getString(R.string.comment_review_to, c.user) else getString(R.string.comment_title))
                .setView(input)
                .setPositiveButton(R.string.comment_submit) { _, _ ->
                    fill()
                    if (post[COMMENT].isNullOrBlank() || (user == 0 && (post[AUTHOR].isNullOrBlank() || post[EMAIL].isNullOrBlank()))) {
                        Toast.makeText(requireActivity(), getString(R.string.comment_verify), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    _progress * true
                    doAsync {
                        val result = url.httpPost(post.toMap())
                        val json = gson.fromJsonOrNull<JWpdiscuzCommentResult>(result?.first)
                        val review = Jsoup.parse(json?.data?.message ?: "", result?.second ?: "")
                                .select("body>.wpd-comment").map { Comment(it) }.firstOrNull()
                        autoUiThread {
                            _progress * false
                            if (review == null) {
                                Toast.makeText(requireActivity(), json?.data?.code ?: result?.first, Toast.LENGTH_LONG).show()
                                return@autoUiThread
                            }
                            post[COMMENT] = ""
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
                .apply {
                    if (user != 0) return@apply
                    setNeutralButton(R.string.app_user_login) { _, _ ->
                        startActivity(Intent(requireActivity(), WebActivity::class.java).putExtra("login", true))
                    }
                }
                .setOnDismissListener { fill() }
                .create().show()
    }
}