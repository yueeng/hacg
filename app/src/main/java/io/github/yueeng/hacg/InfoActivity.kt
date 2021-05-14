@file:Suppress("PrivatePropertyName")

package io.github.yueeng.hacg

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.clans.fab.FloatingActionMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gun0912.tedpermission.TedPermission
import io.github.yueeng.hacg.databinding.*
import kotlinx.coroutines.flow.collectLatest
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

class InfoWebViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val web = handle.getLiveData<Pair<String, String>>("web")
    val error = handle.getLiveData("error", false)
    val magnet = handle.getLiveData<List<String>>("magnet", emptyList())
    val progress = handle.getLiveData("progress", false)
    val article: MutableLiveData<Article> = handle.getLiveData("article", args?.getParcelable("article"))
}

class InfoWebViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = InfoWebViewModel(handle, defaultArgs) as T
}

class InfoWebFragment : Fragment() {
    private val viewModel: InfoWebViewModel by viewModels { InfoWebViewModelFactory(this, arguments) }
    private val _url by lazy { viewModel.article.value?.link ?: requireArguments().getString("url")!! }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FragmentInfoWebBinding.inflate(inflater, container, false).also { binding ->
                viewModel.article.observe(viewLifecycleOwner, Observer { it?.title?.takeIf { i -> i.isNotEmpty() }?.let { t -> requireActivity().title = t } })
                viewModel.error.observe(viewLifecycleOwner, Observer { binding.image1.visibility = if (it) View.VISIBLE else View.INVISIBLE })
                binding.image1.setOnClickListener { query(_url) }
                binding.menu1.setRandomColor()
                val click = View.OnClickListener { v ->
                    when (v.id) {
                        R.id.button1 -> activity?.openUri(_url)
                        R.id.button2 -> activity?.window?.decorView
                                ?.findViewByViewType<ViewPager2>(R.id.container)?.firstOrNull()?.currentItem = 1
                        R.id.button4 -> share()
                    }
                    view?.findViewById<FloatingActionMenu>(R.id.menu1)?.close(true)
                }
                listOf(binding.button1, binding.button2, binding.button4).forEach { it.setOnClickListener(click) }
                viewModel.progress.observe(viewLifecycleOwner, Observer {
                    binding.progress.isIndeterminate = it
                    binding.progress.visibility = if (it) View.VISIBLE else View.INVISIBLE
                })
                viewModel.magnet.observe(viewLifecycleOwner, Observer {
                    binding.button5.visibility = if (it.isNotEmpty()) View.VISIBLE else View.GONE
                })
                binding.button5.setOnClickListener(object : View.OnClickListener {
                    val max = 3
                    var magnet = 1
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
                        activity?.openUri(url)
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                            when (request?.url?.scheme?.lowercase(Locale.getDefault())) {
                                "http", "https" -> try {
                                    val call = okhttp3.Request.Builder().method(request.method, null).url(request.url.toString()).apply {
                                        request.requestHeaders?.forEach { header(it.key, it.value) }
                                    }.build()
                                    val response = okhttp.newCall(call).execute()
                                    WebResourceResponse(response.header("Content-Type", "text/html; charset=UTF-8"),
                                            response.header("Content-Encoding", "utf-8"),
                                            response.code,
                                            response.message,
                                            response.headers.toMap(),
                                            response.body?.byteStream())
                                } catch (_: Exception) {
                                    null
                                }
                                else -> null
                            } ?: super.shouldInterceptRequest(view, request)

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        binding.scroll.removeView(binding.web)
                        binding.web.destroy()
                        return true
                    }
                }
                binding.web.addJavascriptInterface(JsFace(), "hacg")
                listOf(binding.button1, binding.button2, binding.button4, binding.button5).forEach { b ->
                    b.setRandomColor()
                }
                viewModel.web.observe(viewLifecycleOwner, Observer { value ->
                    if (value != null) binding.web.loadDataWithBaseURL(value.second, value.first, "text/html", "utf-8", null)
                })
            }.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (viewModel.web.value == null) query(_url)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        view?.findViewById<WebView>(R.id.web)?.destroy()
    }

    fun share(url: String? = null) {
        fun share(uri: Uri? = null) {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri?.toString() ?: _url)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.isNotEmpty() }
                    ?: "text/plain"
            val title = viewModel.article.value?.title ?: ""
            val intro = viewModel.article.value?.content ?: ""
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
        lifecycleScope.launchWhenCreated {
            url?.httpDownloadAwait()?.let { file ->
                share(FileProvider.getUriForFile(requireActivity(), "${BuildConfig.APPLICATION_ID}.fileprovider", file))
            } ?: share()
        }
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
                GlideApp.with(requireActivity()).load(uri).placeholder(R.drawable.loading).into(image)
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
        lifecycleScope.launchWhenCreated {
            val dom = url.httpGetAwait()?.jsoup()
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
            if (article != null) viewModel.article.postValue(article)
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

class InfoCommentPagingSource(private val _id: Int, private val sorting: () -> InfoCommentViewModel.Sorting) : PagingSource<Pair<Int?, Int>, Comment>() {
    override suspend fun load(params: LoadParams<Pair<Int?, Int>>): LoadResult<Pair<Int?, Int>, Comment> = try {
        val (_postParentId, _postOffset) = params.key!!
        val data = mapOf(
                "action" to "wpdLoadMoreComments",
                "sorting" to sorting().sort,
                "offset" to "$_postOffset",
                "lastParentId" to "$_postParentId",
                "isFirstLoad" to (if (_postOffset == 0) "1" else "0"),
                "wpdType" to "",
                "postId" to "$_id")
        val json = HAcg.wpdiscuz.httpPostAwait(data)
        val comments = gson.fromJsonOrNull<JWpdiscuzComment>(json?.first)
        val list = Jsoup.parse(comments!!.data.commentList ?: "", HAcg.wpdiscuz)
                .select("body>.wpd-comment").map { Comment(it) }.toList()
        val next = if (comments.data.isShowLoadMore) {
            comments.data.lastParentId.toIntOrNull() to (_postOffset + 1)
        } else {
            null
        }
        LoadResult.Page(list, null, next)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Pair<Int?, Int>, Comment>): Pair<Int?, Int>? = null
}

class InfoCommentViewModel(id: Int, handle: SavedStateHandle) : ViewModel() {
    enum class Sorting(val sort: String) {
        Vote("by_vote"), Newest("newest"), Oldest("oldest")
    }

    val progress = handle.getLiveData("progress", false)
    val sorting = handle.getLiveData("sorting", Sorting.Vote)
    val source = Paging(handle, 0 to 0) { InfoCommentPagingSource(id) { sorting.value!! } }
    val data = handle.getLiveData<List<Comment>>("data")
}

class InfoCommentViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = InfoCommentViewModel(args?.getInt("id") ?: 0, handle) as T
}

class InfoCommentFragment : Fragment() {
    private val viewModel: InfoCommentViewModel by viewModels { InfoCommentViewModelFactory(this, bundleOf("id" to _id)) }
    private val _article by lazy { requireArguments().getParcelable<Article>("article") }
    private val _url by lazy { _article?.link ?: requireArguments().getString("url")!! }
    private val _id by lazy { _article?.id ?: Article.getIdFromUrl(_url) ?: 0 }
    private val _adapter by lazy { CommentAdapter() }
    private val adapterPool = RecyclerView.RecycledViewPool()
    private val CONFIG_AUTHOR = "config.author"
    private val CONFIG_EMAIL = "config.email"
    private val CONFIG_COMMENT = "config.comment"
    private val AUTHOR = "wc_name"
    private val EMAIL = "wc_email"
    private var COMMENT = "wc_comment"

    private fun query(refresh: Boolean = false) {
        lifecycleScope.launchWhenCreated {
            if (refresh) _adapter.clear()
            val (list, _) = viewModel.source.query(refresh)
            if (list != null) _adapter.addAll(list)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FragmentInfoListBinding.inflate(inflater, container, false).also { binding ->
                binding.list1.adapter = _adapter.withLoadStateFooter(FooterAdapter({ _adapter.itemCount }) { query() })
                viewModel.progress.observe(viewLifecycleOwner, Observer { binding.swipe.isRefreshing = it })
                viewModel.source.state.observe(viewLifecycleOwner, Observer {
                    _adapter.state.postValue(it)
                    binding.swipe.isRefreshing = it is LoadState.Loading
                })
                lifecycleScope.launchWhenCreated {
                    _adapter.refreshFlow.collectLatest {
                        binding.list1.scrollToPosition(0)
                    }
                }
                binding.list1.loading {
                    when (viewModel.source.state.value) {
                        LoadState.NotLoading(false) -> query()
                    }
                }
                binding.swipe.setOnRefreshListener { query(true) }
                binding.button3.setRandomColor().setOnClickListener {
                    comment(null) {
                        _adapter.add(it, 0)
                        binding.list1.smoothScrollToPosition(0)
                    }
                }
            }.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel.data.value?.let { _adapter.addAll(it) }
        if (_adapter.itemCount == 0) query()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.data.value = _adapter.data
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_comment, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        when (viewModel.sorting.value) {
            InfoCommentViewModel.Sorting.Newest -> menu.findItem(R.id.newest).isChecked = true
            InfoCommentViewModel.Sorting.Oldest -> menu.findItem(R.id.oldest).isChecked = true
            else -> menu.findItem(R.id.vote).isChecked = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.vote, R.id.newest, R.id.oldest -> {
            viewModel.sorting.postValue(when (item.itemId) {
                R.id.oldest -> InfoCommentViewModel.Sorting.Oldest
                R.id.newest -> InfoCommentViewModel.Sorting.Newest
                else -> InfoCommentViewModel.Sorting.Vote
            })
            query(true)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    inner class CommentHolder(private val binding: CommentItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val adapter = CommentAdapter()
        private var comment: Comment? = null

        init {
            binding.list1.setRecycledViewPool(adapterPool)
            binding.list1.adapter = adapter
            listOf(binding.button1, binding.button2).forEach { b ->
                b.setOnClickListener { view ->
                    val v = if (view.id == R.id.button1) -1 else 1
                    val item = comment ?: return@setOnClickListener
                    val pos = bindingAdapterPosition
                    vote(item, v) {
                        item.moderation = it
                        bindingAdapter?.notifyItemChanged(pos, "moderation")
                    }
                }
            }
            binding.root.setOnClickListener {
                comment(comment!!) {
                    adapter.add(it)
                }
            }
        }

        fun bind(item: Comment, payloads: MutableList<Any>) {
            if (payloads.contains("moderation")) {
                binding.text4.text = "${item.moderation}"
                return
            }
            comment = item
            itemView.tag = item
            binding.text1.text = item.user
            binding.text2.text = item.content
            binding.text3.text = item.time
            binding.text4.text = "${item.moderation}"
            adapter.clear()
            adapter.addAll(item.children)
            if (item.face.isEmpty()) {
                binding.image1.setImageResource(R.mipmap.ic_launcher)
            } else {
                GlideApp.with(requireContext()).load(item.face).placeholder(R.mipmap.ic_launcher).into(binding.image1)
            }
        }
    }

    class CommentDiffCallback : DiffUtil.ItemCallback<Comment>() {
        override fun areItemsTheSame(oldItem: Comment, newItem: Comment): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Comment, newItem: Comment): Boolean = oldItem == newItem
    }

    inner class CommentAdapter : PagingAdapter<Comment, CommentHolder>(CommentDiffCallback()) {
        override fun onBindViewHolder(holder: CommentHolder, position: Int) {}

        override fun onBindViewHolder(holder: CommentHolder, position: Int, payloads: MutableList<Any>) {
            holder.bind(getItem(position)!!, payloads)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder =
                CommentHolder(CommentItemBinding.inflate(layoutInflater, parent, false))
    }

    fun vote(c: Comment?, v: Int, call: (Int) -> Unit) {
        if (c == null) return
        lifecycleScope.launchWhenCreated {
            val result = HAcg.wpdiscuz.httpPostAwait(mapOf(
                    "action" to "wpdVoteOnComment",
                    "commentId" to "${c.id}",
                    "voteType" to "$v",
                    "postId" to "$_id"))
            val succeed = gson.fromJsonOrNull<JWpdiscuzVoteSucceed>(result?.first ?: "")
            if (succeed?.success != true) {
                val json = gson.fromJsonOrNull<JWpdiscuzVote>(result?.first ?: "")
                Toast.makeText(requireActivity(), json?.data ?: result?.first, Toast.LENGTH_LONG).show()
                return@launchWhenCreated
            }
            call(succeed.data.votes.toIntOrNull() ?: 0)
        }
    }

    fun comment(c: Comment?, succeed: (Comment) -> Unit) {
        if (c == null) {
            commenting(c, succeed)
            return
        }
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(c.user)
                .setMessage(c.content)
                .setPositiveButton(R.string.comment_review) { _, _ -> commenting(c, succeed) }
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
    private fun commenting(c: Comment?, succeed: (Comment) -> Unit) {
        val input = CommentPostBinding.inflate(layoutInflater)
        val author: EditText = input.edit1
        val email: EditText = input.edit2
        val content: EditText = input.edit3
        val post = mutableMapOf<String, String>()
        val preference = PreferenceManager.getDefaultSharedPreferences(activity)
        if (user != 0) {
            input.input1.visibility = View.GONE
            input.input2.visibility = View.GONE
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
                .setView(input.root)
                .setPositiveButton(R.string.comment_submit) { _, _ ->
                    fill()
                    if (post[COMMENT].isNullOrBlank() || (user == 0 && (post[AUTHOR].isNullOrBlank() || post[EMAIL].isNullOrBlank()))) {
                        Toast.makeText(requireActivity(), getString(R.string.comment_verify), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewModel.progress.postValue(true)
                    lifecycleScope.launchWhenCreated {
//                        delay(100)
//                        succeed(Comment(random.nextInt(), c?.id ?: 0, "Test", "ZS", "", 0, datefmt.format(Date()), mutableListOf()))
                        val result = HAcg.wpdiscuz.httpPostAwait(post.toMap())
                        val json = gson.fromJsonOrNull<JWpdiscuzCommentResult>(result?.first)
                        val review = Jsoup.parse(json?.data?.message ?: "", result?.second ?: "")
                                .select("body>.wpd-comment").map { Comment(it) }.firstOrNull()
                        if (review == null) {
                            Toast.makeText(requireActivity(), json?.data?.code ?: result?.first, Toast.LENGTH_LONG).show()
                        } else {
                            post[COMMENT] = ""
                            succeed(review)
                        }
                        viewModel.progress.postValue(false)
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