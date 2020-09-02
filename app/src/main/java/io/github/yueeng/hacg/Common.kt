@file:Suppress("unused", "ObjectPropertyName", "PrivatePropertyName")

package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.*
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingSource
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.*
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionMenu
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.jakewharton.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.buffer
import okio.sink
import org.jetbrains.anko.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

var user: Int
    get() = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).getInt("user.id", 0)
    set(value) = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).edit().putInt("user.id", value).apply()

val gson: Gson = GsonBuilder().create()
val okhttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(HAcgApplication.instance.cacheDir, 1024 * 1024 * 256))
        .cookieJar(WebkitCookieJar(CookieManager.getInstance()))
        .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
        .build()
val okdownload = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .cache(Cache(HAcgApplication.instance.cacheDir, 1024 * 1024 * 256))
        .cookieJar(WebkitCookieJar(CookieManager.getInstance()))
        .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
        .build()

class HAcgApplication : Application() {
    companion object {

        private lateinit var _instance: HAcgApplication

        val instance: HAcgApplication get() = _instance
    }

    init {
        _instance = this
    }

    override fun onCreate() {
        super.onCreate()
        Picasso.setSingletonInstance(Picasso.Builder(this)
                .downloader(OkHttp3Downloader(okhttp))
                .build())
    }
}

class WebkitCookieJar(private val cm: CookieManager) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cm.getCookie(url.toString())?.split("; ")?.mapNotNull { Cookie.parse(url, it) }?.toList() ?: emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            cm.setCookie(url.toString(), cookie.toString())
        }
    }
}

suspend fun <T> Call.await(action: (Call, Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isCancelled) continuation.resume(action(call, response))
        }
    })
}

val datefmt get() = SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ", LocaleListCompat.getDefault()[0])
val datefmtcn get() = SimpleDateFormat("yyyy年MM月dd日 ahh:mm", LocaleListCompat.getDefault()[0])
fun String.toDate(fmt: SimpleDateFormat? = null): Date? = try {
    (fmt ?: datefmt).parse(this)
} catch (_: ParseException) {
    null
}

val String.html: Spanned get() = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)

fun openWeb(context: Context, uri: String): Unit =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

val random = Random(System.currentTimeMillis())

fun randomColor(alpha: Int = 0xFF): Int = android.graphics.Color.HSVToColor(alpha, arrayOf(random.nextInt(360).toFloat(), 1F, 0.5F).toFloatArray())

fun Context.toast(msg: Int): Toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }

fun Context.toast(msg: String): Toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }

fun Context.clipboard(label: String, text: String) {
    val clipboard = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    this.toast(this.getString(R.string.app_copied, text))
}

fun ViewGroup.inflate(layout: Int, attach: Boolean = false): View =
        LayoutInflater.from(this.context).inflate(layout, this, attach)

fun FloatingActionButton.setRandomColor(): FloatingActionButton = apply {
    colorNormal = randomColor()
    colorPressed = randomColor()
    colorRipple = randomColor()
}

fun FloatingActionMenu.setRandomColor(): FloatingActionMenu = apply {
    menuButtonColorNormal = randomColor()
    menuButtonColorPressed = randomColor()
    menuButtonColorRipple = randomColor()
}

private val img = listOf(".jpg", ".png", ".webp")

@SuppressLint("DefaultLocale")
fun String.isImg(): Boolean = img.any { this.toLowerCase().endsWith(it) }

fun String.httpGet(): Pair<String, String>? = try {
    val request = Request.Builder().get().url(this).build()
    val response = okhttp.newCall(request).execute()
    val url = response.request.url.toString()
    val html = response.body!!.string()
    if (url.startsWith(HAcg.wordpress)) {
        """"user_id":"(\d+)"""".toRegex().find(html)?.let {
            user = it.groups[1]?.value?.toIntOrNull() ?: 0
        }
    }
    html to url
} catch (e: Exception) {
    e.printStackTrace(); null
}

suspend fun String.httpGetAwait(): Pair<String, String>? = try {
    val request = Request.Builder().get().url(this).build()
    val (html, url) = okhttp.newCall(request).await { _, response -> response.body!!.string() to response.request.url.toString() }
    if (url.startsWith(HAcg.wordpress)) {
        """"user_id":"(\d+)"""".toRegex().find(html)?.let {
            user = it.groups[1]?.value?.toIntOrNull() ?: 0
        }
    }
    html to url
} catch (e: Exception) {
    e.printStackTrace(); null
}

fun String.httpGetAsync(context: Context, callback: (Pair<String, String>?) -> Unit): Future<Unit> = context.doAsync {
    val result = this@httpGetAsync.httpGet()
    autoUiThread { callback(result) }
}

fun String.httpPost(post: Map<String, String>): Pair<String, String>? = try {
    val data = post.toList().fold(MultipartBody.Builder().setType(MultipartBody.FORM)) { b, o -> b.addFormDataPart(o.first, o.second) }.build()
    val request = Request.Builder().url(this).post(data).build()
    val response = okhttp.newCall(request).execute()
    (response.body!!.string() to response.request.url.toString())
} catch (_: Exception) {
    null
}

suspend fun String.httpPostAwait(post: Map<String, String>): Pair<String, String>? = try {
    val data = post.toList().fold(MultipartBody.Builder().setType(MultipartBody.FORM)) { b, o -> b.addFormDataPart(o.first, o.second) }.build()
    val request = Request.Builder().url(this).post(data).build()
    val response = okhttp.newCall(request).await { _, response ->
        (response.body!!.string() to response.request.url.toString())
    }
    response
} catch (_: Exception) {
    null
}

fun String.httpDownloadAsync(context: Context, file: String? = null, fn: (File?) -> Unit): Future<Unit> = context.doAsync {
    val result = httpDownload(file)
    autoUiThread { fn(result) }
}

fun String.httpDownload(file: String? = null): File? = try {
    val request = Request.Builder().get().url(this).build()
    val response = okdownload.newCall(request).execute()

    val target = if (file == null) {
        val path = response.request.url.toUri().path
        File(HAcgApplication.instance.externalCacheDir, path.substring(path.lastIndexOf('/') + 1))
    } else {
        File(file)
    }

    val sink = target.sink().buffer()
    sink.writeAll(response.body!!.source())
    sink.close()
    target
} catch (e: Exception) {
    e.printStackTrace(); null
}

fun String.test(timeout: Int = 1000): Pair<Boolean, Int> = try {
    val uri = URL("https://$this")
    (Socket()).use { socket ->
        val address = InetSocketAddress(InetAddress.getByName(uri.host), uri.port.takeIf { it != -1 }
                ?: 80)
        val begin = System.currentTimeMillis()
        socket.connect(address, timeout)
        (socket.isConnected to (System.currentTimeMillis() - begin).toInt())
    }
} catch (e: Exception) {
    e.printStackTrace(); (false to 0)
}

val rmagnet = """\b([a-zA-Z0-9]{32}|[a-zA-Z0-9]{40})\b""".toRegex()
val rbaidu = """\b([a-zA-Z0-9]{8})\b\s+\b([a-zA-Z0-9]{4})\b""".toRegex()
fun String.magnet(): Sequence<String> = rmagnet.findAll(this).map { it.value } +
        rbaidu.findAll(this).map { m -> "${m.groups[1]!!.value},${m.groups[2]!!.value}" }

fun <T> Gson.fromJsonOrNull(json: String?, clazz: Class<T>): T? = try {
    fromJson(json, clazz)
} catch (_: Exception) {
    null
}

inline fun <reified T> Gson.fromJsonOrNull(json: String?): T? = fromJsonOrNull(json, T::class.java)

fun String.jsoup(uri: String): Document = Jsoup.parse(this, uri)

fun Pair<String, String>.jsoup(): Document = this.let { h ->
    Jsoup.parse(h.first, h.second)
}

fun <T> Pair<String, String>.jsoup(f: (Document) -> T?): T? = f(this.jsoup())
fun version(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (e: Exception) {
    e.printStackTrace(); ""
}

fun versionBefore(local: String, online: String): Boolean = try {
    val l = local.split('.').map { it.toInt() }.toList()
    val o = online.split('.').map { it.toInt() }.toList()
    for (i in 0 until min(l.size, o.size)) {
        (l[i] - o[i]).let { x ->
            when {
                x > 0 -> return false
                x < 0 -> return true
                else -> {
                }
            }
        }
    }
    if (o.size > l.size) {
        o.drop(l.size).any { it != 0 }
    } else false
} catch (_: Exception) {
    false
}

inline fun <reified T : View> View.findViewByViewType(id: Int = 0): Sequence<T> = this.childrenRecursiveSequence()
        .mapNotNull { it as? T }.filter { id == 0 || id == it.id }

fun Activity.snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar = this.window.decorView.let { view ->
    view.findViewByViewType<CoordinatorLayout>().firstOrNull()
            ?: view
}.let { Snackbar.make(it, text, duration) }

fun Fragment.arguments(b: Bundle?): Fragment = this.also { it.arguments = b }

fun Bundle.string(key: String, value: String): Bundle = this.also { it.putString(key, value) }

fun Bundle.parcelable(key: String, value: Parcelable): Bundle = this.also { it.putParcelable(key, value) }

fun <A, B> List<A>.pmap(f: (A) -> B): List<B> = map { doAsyncResult { f(it) } }.map { it.get() }

fun TedPermission.Builder.onPermissionGranted(f: () -> Unit): TedPermission.Builder = setPermissionListener(object : PermissionListener {
    override fun onPermissionGranted() {
        f()
    }

    override fun onPermissionDenied(deniedPermissions: ArrayList<String>?) {
    }
})

fun TedPermission.Builder.onPermissionDenied(f: (ArrayList<String>?) -> Unit): TedPermission.Builder = setPermissionListener(object : PermissionListener {
    override fun onPermissionGranted() {
    }

    override fun onPermissionDenied(deniedPermissions: ArrayList<String>?) {
        f(deniedPermissions)
    }
})

abstract class DataAdapter<V, VH : RecyclerView.ViewHolder>(private val _data: MutableList<V> = mutableListOf()) : RecyclerView.Adapter<VH>() {
    override fun getItemCount(): Int = size

    val size: Int get() = _data.size
    val data: List<V> get() = _data
    open fun clear(): DataAdapter<V, VH> {
        val size = _data.size
        _data.clear()
        notifyItemRangeRemoved(0, size)
        return this
    }

    open fun add(v: V, index: Int = _data.size): DataAdapter<V, VH> {
        _data.add(index, v)
        notifyItemInserted(index)
        return this
    }

    open fun addAll(v: List<V>): DataAdapter<V, VH> {
        _data.addAll(v)
        notifyItemRangeInserted(_data.size - v.size, v.size)
        return this
    }

    open fun remove(v: V): DataAdapter<V, VH> {
        val index = _data.indexOf(v)
        if (index == -1) return this
        _data.removeAt(index)
        notifyItemRemoved(index)
        return this
    }

    open fun getItem(position: Int): V? = data[position]
}

abstract class PagingAdapter<V, VH : RecyclerView.ViewHolder>(_data: MutableList<V> = mutableListOf()) : DataAdapter<V, VH>(_data) {
    private val refreshCh = Channel<Boolean>()
    val refreshFlow = refreshCh.consumeAsFlow()
    val state = MutableLiveData<LoadState>()
    fun withLoadStateFooter(footer: LoadStateAdapter<*>): ConcatAdapter {
        state.observeForever { footer.loadState = it }
        return ConcatAdapter(this, footer)
    }

    override fun add(v: V, index: Int): DataAdapter<V, VH> {
        val fist = size == 0
        super.add(v, index)
        if (fist && size != 0) refreshCh.offer(true)
        return this
    }

    override fun addAll(v: List<V>): DataAdapter<V, VH> {
        val fist = size == 0
        super.addAll(v)
        if (fist && size != 0) refreshCh.offer(true)
        return this
    }
}

class Paging<K : Any, V : Any>(private val handle: SavedStateHandle, private val k: K?, factory: () -> PagingSource<K, V>) {
    private var key: K?
        get() = if (handle.contains("key")) handle["key"] else k
        set(value) = handle.set("key", value)
    val state = MutableLiveData<LoadState>(LoadState.NotLoading(false))
    private val source by lazy(factory)
    private val mutex = Mutex()
    suspend fun query(refresh: Boolean = false): Pair<List<V>?, Throwable?> {
        mutex.withLock {
            if (state.value is LoadState.Loading) return null to null
            if (refresh) handle.remove<String?>("key")
            if (key == null) return null to null
            state.setValue(LoadState.Loading)
        }
        return when (val result = source.load(PagingSource.LoadParams.Append(key!!, 20, false))) {
            is PagingSource.LoadResult.Page -> {
                key = result.nextKey
                state.postValue(LoadState.NotLoading(result.nextKey == null))
                result.data to null
            }
            is PagingSource.LoadResult.Error -> {
                state.postValue(LoadState.Error(result.throwable))
                null to result.throwable
            }
        }
    }
}

class RoundedBackgroundColorSpan(private val backgroundColor: Int) : ReplacementSpan() {
    private val linePadding = 2f // play around with these as needed
    private val sidePadding = 5f // play around with these as needed
    private fun measureText(paint: Paint, text: CharSequence, start: Int, end: Int): Float =
            paint.measureText(text, start, end)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, p4: Paint.FontMetricsInt?): Int =
            (measureText(paint, text, start, end) + (2 * sidePadding)).roundToInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val rect = RectF(x, y + paint.fontMetrics.top - linePadding,
                x + getSize(paint, text, start, end, paint.fontMetricsInt),
                y + paint.fontMetrics.bottom + linePadding)
        paint.color = backgroundColor
        canvas.drawRoundRect(rect, 5F, 5F, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, start, end, x + sidePadding, y * 1F, paint)
    }

}

class TagClickableSpan<T>(private val tag: T, private val call: ((T) -> Unit)? = null) : ClickableSpan() {
    override fun onClick(widget: View) {
        call?.invoke(tag)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = 0xFFFFFFFF.toInt()
        ds.isUnderlineText = false
    }
}

fun <T> List<T>.spannable(separator: CharSequence = " ", string: (T) -> String = { "$it" }, call: ((T) -> Unit)?): SpannableStringBuilder {

    val tags = this.joinToString(separator) { string(it) }
    val span = SpannableStringBuilder(tags)
    fold(0) { i, it ->
        val p = tags.indexOf(string(it), i)
        val e = p + string(it).length
        if (call != null) span.setSpan(TagClickableSpan(it, call), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RoundedBackgroundColorSpan(randomColor(0xBF)), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e
    }
    return span
}

class Once {
    private var init = false

    @Synchronized
    fun run(call: () -> Unit) {
        if (init) return
        init = true
        call()
    }
}

fun RecyclerView.loading(last: Int = 1, call: () -> Unit) {
    this.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        fun load(recycler: RecyclerView) {
            when (val layout = recycler.layoutManager) {
                is StaggeredGridLayoutManager -> {
                    val vis = layout.findLastVisibleItemPositions(null)
                    val v = vis.maxOrNull() ?: 0
                    if (v >= (this@loading.adapter!!.itemCount - last)) call()
                }
                is GridLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= this@loading.adapter!!.itemCount - last) call()
                is LinearLayoutManager ->
                    if (layout.findLastVisibleItemPosition() >= this@loading.adapter!!.itemCount - last) call()
            }
        }

        val once = Once()

        override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
            once.run {
                load(recycler)
            }
        }

        override fun onScrollStateChanged(recycler: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return
            load(recycler)
        }
    })
}

class PagerSlidingPaneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : SlidingPaneLayout(context, attrs, defStyle) {
    private var mInitialMotionX: Float = 0F
    private var mInitialMotionY: Float = 0F
    private val mEdgeSlop: Float = ViewConfiguration.get(context).scaledEdgeSlop.toFloat()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = when (ev.action) {
        MotionEvent.ACTION_DOWN -> {
            mInitialMotionX = ev.x
            mInitialMotionY = ev.y
            null
        }
        MotionEvent.ACTION_MOVE -> {
            val x = ev.x
            val y = ev.y
            val dx = (x - mInitialMotionX).roundToInt()
            when {
                mInitialMotionX <= mEdgeSlop -> null
                isOpen -> null
                dx == 0 -> null
                !canScroll(this, false, dx, x.roundToInt(), y.roundToInt()) -> null
                else -> {
                    val me = MotionEvent.obtain(ev)
                    me.action = MotionEvent.ACTION_CANCEL
                    super.onInterceptTouchEvent(me).also {
                        me.recycle()
                    }
                }
            }
        }
        else -> null
    } ?: super.onInterceptTouchEvent(ev)
}

@SuppressLint("Registered")
open class BaseSlideCloseActivity : AppCompatActivity(), SlidingPaneLayout.PanelSlideListener {

    override fun onCreate(state: Bundle?) {
        swipe()
        super.onCreate(state)
        (window.decorView as? ViewGroup)?.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener, View.OnLayoutChangeListener {
            override fun onChildViewRemoved(parent: View?, child: View?) {
                child?.removeOnLayoutChangeListener(this)
            }

            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child?.id == android.R.id.navigationBarBackground) {
                    reset(child.height)
                    child.addOnLayoutChangeListener(this)
                }
            }

            override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                reset(bottom - top)
            }

            fun reset(height: Int) {
                (window.decorView as? ViewGroup)?.firstChildOrNull { it is PagerSlidingPaneLayout }?.let {
                    it.layoutParams = (it.layoutParams as? FrameLayout.LayoutParams)?.apply {
                        bottomMargin = height
                    }
                }
            }
        })
    }

    private fun swipe() {
        val swipe = PagerSlidingPaneLayout(this)
        // 通过反射改变mOverhangSize的值为0，
        // 这个mOverhangSize值为菜单到右边屏幕的最短距离，
        // 默认是32dp，现在给它改成0
        try {
            val overhang = SlidingPaneLayout::class.java.getDeclaredField("mOverhangSize")
            overhang.isAccessible = true
            overhang.set(swipe, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        swipe.setPanelSlideListener(this)
        swipe.sliderFadeColor = ContextCompat.getColor(this, android.R.color.transparent)

        // 左侧的透明视图
        val leftView = View(this)
        leftView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        swipe.addView(leftView, 0)
        swipe.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val decorView = window.decorView as ViewGroup

        // 右侧的内容视图
        val decorChild = decorView.getChildAt(0) as ViewGroup
        theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).also {
            decorChild.setBackgroundColor(it.getColor(0, 0))
        }.recycle()
        decorView.removeView(decorChild)
        decorView.addView(swipe)

        // 为 SlidingPaneLayout 添加内容视图
        swipe.addView(decorChild, 1)
    }

    override fun onPanelSlide(panel: View, slideOffset: Float) {

    }

    override fun onPanelOpened(panel: View) {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onPanelClosed(panel: View) {

    }
}

fun <T> AnkoAsyncContext<T>.autoUiThread(f: (T) -> Unit): Boolean {
    val context = weakRef.get() ?: return false
    val activity: Activity? = when (context) {
        is Fragment -> if (context.isDetached) null else context.activity
        is Activity -> if (context.isFinishing) null else context
        else -> null
    }
    return activity?.runOnUiThread { f(context) }?.let { true } ?: false
}