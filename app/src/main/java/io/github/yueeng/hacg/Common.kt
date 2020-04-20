@file:Suppress("unused", "ObjectPropertyName", "PrivatePropertyName")

package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.*
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
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.jakewharton.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okio.buffer
import okio.sink
import org.jetbrains.anko.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

val gson: Gson = GsonBuilder().create()
val okhttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
        .build()
val okdownload = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
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
        CookieHandler.setDefault(CookieManagerProxy.instance)
    }
}

class CookieManagerProxy(store: CookieStore, policy: CookiePolicy) : CookieManager(store, policy) {

    private val SetCookie: String = "Set-Cookie"

    override fun put(uri: URI, headers: Map<String, List<String>>) {
        super.put(uri, headers)
        cookieStore.get(uri)
                .filter { o -> o.domain != null && !o.domain.startsWith(".") }
                .forEach { o -> o.domain = ".${o.domain}" }
    }

    fun put(uri: URI, cookies: String?) = when (cookies) {
        null -> {
        }
        else ->
            this.put(uri, mapOf(SetCookie to cookies.split(";").map { it.trim() }.toList()))
    }

    companion object {
        val instance = CookieManagerProxy(PersistCookieStore(HAcgApplication.instance), CookiePolicy.ACCEPT_ALL)
    }
}

/**
 * PersistentCookieStore
 * Created by Rain on 2015/7/1.
 */
class PersistCookieStore(context: Context) : CookieStore {
    private val map = mutableMapOf<URI, MutableSet<HttpCookie>>()
    private val pref: SharedPreferences = context.getSharedPreferences("cookies.pref", Context.MODE_PRIVATE)

    init {
        pref.all.mapNotNull { i -> (i.value as? String)?.takeIf { it.isNotEmpty() }?.let { i.key to it.split(',') } }
                .forEach { o ->
                    map[URI.create(o.first)] = o.second.flatMap { c ->
                        try {
                            HttpCookie.parse(c)
                        } catch (_: Throwable) {
                            listOf<HttpCookie>()
                        }
                    }.toMutableSet()
                }
    }

    fun HttpCookie.string(): String {
        if (version != 0) {
            return toString()
        }
        return mapOf(name to value, "domain" to domain)
                .filter { it.value != null }
                .filter { it.value.isNotEmpty() }
                .map { o -> "${o.key}=${o.value}" }
                .joinToString("; ")
    }

    private fun cookiesUri(uri: URI): URI {
        return try {
            URI("http", uri.host, null, null)
        } catch (_: URISyntaxException) {
            uri
        }
    }

    @Synchronized
    override fun add(url: URI, cookie: HttpCookie?) {
        if (cookie == null) {
            throw NullPointerException("cookie == null")
        }
        cookie.domain?.takeIf { !it.startsWith(".") }?.let { cookie.domain = ".$it" }

        val uri = cookiesUri(url)

        if (map.contains(uri)) {
            map[uri]!!.add(cookie)
        } else {
            map[uri] = mutableSetOf(cookie)
        }

        pref.edit().putString(uri.toString(), map[uri]!!.map { it.string() }.toSet().joinToString(",")).apply()
    }

    @Synchronized
    override fun remove(url: URI, cookie: HttpCookie?): Boolean {
        if (cookie == null) {
            throw NullPointerException("cookie == null")
        }
        val uri = cookiesUri(url)
        return map[uri]?.takeIf { it.contains(cookie) }?.let { cookies ->
            pref.edit().putString(uri.toString(), (cookies - cookie).map { it.string() }.toSet().joinToString(",")).apply()
            true
        } ?: false
    }

    @Synchronized
    override fun removeAll(): Boolean {
        val result = map.isNotEmpty()
        map.clear()
        pref.edit().clear().apply()
        return result
    }

    @Synchronized
    override fun getURIs(): List<URI> =
            map.keys.toList()


    private fun expire(uri: URI, cookies: MutableSet<HttpCookie>, edit: SharedPreferences.Editor, fn: (HttpCookie) -> Boolean = { true }) {
        cookies.filter(fn).filter { it.hasExpired() }.takeIf { it.isNotEmpty() }?.let { ex ->
            cookies.removeAll(ex)
            edit.putString(uri.toString(), cookies.map { it.string() }.toSet().joinToString(",")).apply()
        }
    }

    @Synchronized
    override fun getCookies(): List<HttpCookie> {
        pref.edit().apply { map.forEach { expire(it.key, it.value, this) } }.apply()
        return map.values.flatten().toList().distinct()
    }

    @Synchronized
    override fun get(uri: URI?): List<HttpCookie> {
        if (uri == null) {
            throw NullPointerException("uri == null")
        }
        val edit = pref.edit()
        return map[uri]?.let { cookies ->
            expire(uri, cookies, edit)
            map.filter { it.key != uri }.forEach { o ->
                expire(o.key, o.value, edit) { c -> HttpCookie.domainMatches(c.domain, uri.host) }
            }
            edit.apply()
            (map[uri]?.toList()
                    ?: listOf()) + map.values.flatMap { o -> o.filter { c -> HttpCookie.domainMatches(c.domain, uri.host) } }.toList().distinct()
        } ?: listOf()
    }
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

private val img = listOf(".jpg", ".png", ".webp")

@SuppressLint("DefaultLocale")
fun String.isImg(): Boolean = img.any { this.toLowerCase().endsWith(it) }

fun String.httpGet(): Pair<String, String>? = try {
    val request = Request.Builder().get().url(this).build()
    val response = okhttp.newCall(request).execute()
    response.body!!.string() to response.request.url.toString()
} catch (e: Exception) {
    e.printStackTrace(); null
}

fun String.httpGetAsync(context: Context, callback: (Pair<String, String>?) -> Unit): Future<Unit> = context.doAsync {
    val result = this@httpGetAsync.httpGet()
    autoUiThread { callback(result) }
}

fun String.httpPost(post: Map<String, String>): Pair<String, String>? = try {
    val data = post.asSequence().fold(MultipartBody.Builder().setType(MultipartBody.FORM)) { b, o -> b.addFormDataPart(o.key, o.value) }.build()
    val request = Request.Builder().url(this).post(data).build()
    val response = okhttp.newCall(request).execute()
    (response.body!!.string() to response.request.url.toString())
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

fun <T> Gson.fromJsonOrNull(json: String?, clazz: Class<T>): T? = try {
    fromJson<T>(json, clazz)
} catch (_: Exception) {
    null
}

inline fun <reified T> Gson.fromJsonOrNull(json: String?): T? = fromJsonOrNull(json, T::class.java)

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
    val l = local.split("""\.""").map { it.toInt() }.toList()
    val o = online.split("""\.""").map { it.toInt() }.toList()
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

inline fun <reified T : View> View.findViewByViewType(id: Int = 0): Sequence<View> = this.childrenRecursiveSequence()
        .filter { it is T }.filter { id == 0 || id == it.id }

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

open class ViewBinder<T, V : View>(private var value: T, private val func: (V, T) -> Unit) {
    private val view = WeakHashMap<V, Boolean>()
    open operator fun plus(v: V): ViewBinder<T, V> = synchronized(this) {
        view[v] = true
        func(v, value)
        this
    }

    operator fun minus(v: V): ViewBinder<T, V> = synchronized(this) {
        view.remove(v)
        this
    }

    operator fun times(v: T): ViewBinder<T, V> = synchronized(this) {
        if (value != v) {
            value = v
            view.forEach { func(it.key, value) }
        }
        this
    }

    operator fun invoke(): T = value

    fun each(func: (V) -> Unit): ViewBinder<T, V> = synchronized(this) {
        view.forEach { func(it.key) }
        this
    }
}

abstract class ErrorBinder(value: Boolean) : ViewBinder<Boolean, View>(value, { v, t -> v.visibility = if (t) View.VISIBLE else View.INVISIBLE }) {
    override fun plus(v: View): ViewBinder<Boolean, View> {
        v.setOnClickListener { retry() }
        return super.plus(v)
    }

    abstract fun retry()
}

abstract class DataAdapter<V, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    private val _data = mutableListOf<V>()

    override fun getItemCount(): Int = size

    val size: Int get() = _data.size
    val data: List<V> get() = _data
    fun clear(): DataAdapter<V, VH> {
        val size = _data.size
        _data.clear()
        notifyItemRangeRemoved(0, size)
        return this
    }

    fun add(v: V, index: Int = _data.size): DataAdapter<V, VH> {
        _data.add(index, v)
        notifyItemInserted(index)
        return this
    }

    fun addAll(v: List<V>): DataAdapter<V, VH> {
        _data.addAll(v)
        notifyItemRangeInserted(_data.size - v.size, v.size)
        return this
    }

    fun remove(v: V): DataAdapter<V, VH> {
        val index = _data.indexOf(v)
        if (index == -1) return this
        _data.removeAt(index)
        notifyItemRemoved(index)
        return remove(v)
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
                    val v = vis.max() ?: 0
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

data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
) : java.io.Serializable {

    /**
     * Returns string representation of the [Triple] including its [first], [second] and [third] values.
     */
    override fun toString(): String = "($first, $second, $third, $fourth)"
}

/**
 * Converts this triple into a list.
 */
fun <T> Quadruple<T, T, T, T>.toList(): List<T> = listOf(first, second, third, fourth)

data class Quintuple<out A, out B, out C, out D, out E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
) : java.io.Serializable {

    /**
     * Returns string representation of the [Triple] including its [first], [second] and [third] values.
     */
    override fun toString(): String = "($first, $second, $third, $fourth, $fifth)"
}

/**
 * Converts this triple into a list.
 */
fun <T> Quintuple<T, T, T, T, T>.toList(): List<T> = listOf(first, second, third, fourth, fifth)

data class Sextuple<out A, out B, out C, out D, out E, out F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
) : java.io.Serializable {

    /**
     * Returns string representation of the [Triple] including its [first], [second] and [third] values.
     */
    override fun toString(): String = "($first, $second, $third, $fourth, $fifth, $sixth)"
}

/**
 * Converts this triple into a list.
 */
fun <T> Sextuple<T, T, T, T, T, T>.toList(): List<T> = listOf(first, second, third, fourth, fifth, sixth)


class PagerSlidingPaneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : SlidingPaneLayout(context, attrs, defStyle) {
    private var mInitialMotionX: Float = 0F
    private var mInitialMotionY: Float = 0F
    private val mEdgeSlop: Float = ViewConfiguration.get(context).scaledEdgeSlop.toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent?): Boolean = super.onTouchEvent(ev)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                mInitialMotionX = ev.x
                mInitialMotionY = ev.y
                null
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val y = ev.y
                if (mInitialMotionX > mEdgeSlop && !isOpen && canScroll(this, false,
                                (x - mInitialMotionX).roundToInt(), x.roundToInt(), y.roundToInt())) {
                    val me = MotionEvent.obtain(ev)
                    me.action = MotionEvent.ACTION_CANCEL
                    super.onInterceptTouchEvent(me).also {
                        me.recycle()
                    }
                } else null
            }
            else -> null
        } ?: super.onInterceptTouchEvent(ev)
    }
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