package io.github.yueeng.hacg

import java.io.{File, PrintWriter}
import java.net._
import java.security.MessageDigest
import java.text.{ParseException, SimpleDateFormat}
import java.util
import java.util.Date
import java.util.concurrent._

import android.app
import android.app.Activity
import android.content._
import android.graphics.{Canvas, Paint, RectF}
import android.net.Uri
import android.os._
import android.preference.PreferenceManager
import android.support.design.widget.{CoordinatorLayout, Snackbar}
import android.support.multidex.MultiDexApplication
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.text.HtmlCompat
import android.support.v4.widget.SlidingPaneLayout
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.{AlertDialog, AppCompatActivity}
import android.support.v7.widget.{GridLayoutManager, LinearLayoutManager, RecyclerView, StaggeredGridLayoutManager}
import android.text.style.{ClickableSpan, ReplacementSpan}
import android.text.{InputType, SpannableStringBuilder, Spanned, TextPaint}
import android.util.{AttributeSet, DisplayMetrics}
import android.view._
import android.widget.{EditText, FrameLayout, Toast}
import io.github.yueeng.hacg.Common._
import okhttp3.{MultipartBody, OkHttpClient, Request}
import okio.Okio
import org.json.{JSONArray, JSONObject}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.collection.{TraversableOnce, immutable, mutable}
import scala.io.Source
import scala.language.{implicitConversions, postfixOps, reflectiveCalls}
import scala.ref.WeakReference
import scala.util.Random
import scala.util.matching.Regex

object HAcg {
  private val SYSTEM_HOST: String = "system.host"
  private val SYSTEM_HOSTS: String = "system.hosts"

  private def config_file: File = new File(HAcgApplication.instance.getFilesDir, "config.json")

  private def config_string: String = synchronized {
    if (config_file.exists())
      Source.fromFile(config_file).using(_.mkString)
    else HAcgApplication.instance.getAssets.open("config.json").using { s =>
      Source.fromInputStream(s).mkString
    }
  }

  private def default_config: JSONObject = synchronized {
    try new JSONObject(config_string) catch {
      case _: Exception => null
    }
  }

  private def default_hosts(cfg: Option[JSONObject] = None): immutable.Seq[String] = try cfg.getOrElse(default_config).getJSONArray("host").let { h =>
    for (i <- 0 until h.length(); it = h.getString(i)) yield it
  } catch {
    case _: Exception => List("www.hacg.me")
  }

  private def default_category(cfg: Option[JSONObject] = None): immutable.Seq[(String, String)] = try cfg.getOrElse(default_config).getJSONArray("category").let { a =>
    for (i <- 0 until a.length();
         it = a.getJSONObject(i);
         item = (it.getString("url"), it.getString("name")))
      yield item
  } catch {
    case _: Exception => Nil
  }

  private def default_bbs(cfg: Option[JSONObject] = None): String = try cfg.getOrElse(default_config).getString("bbs") catch {
    case _: Exception => "/wp/bbs"
  }

  private val config = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).also { c =>
    val AVC = "app.version.code"
    if (c.getInt(AVC, 0) < BuildConfig.VERSION_CODE) {
      c.edit()
        .remove(SYSTEM_HOST)
        .remove(SYSTEM_HOSTS)
        .putInt(AVC, BuildConfig.VERSION_CODE)
        .apply()
      config_file.delete()
    }
  }

  private def save_hosts: Seq[String] = try config.getString(SYSTEM_HOSTS, null).let {
    s => new JSONArray(s).let { a => (0 until a.length()).map(k => a.getString(k)) }
  } catch {
    case _: Exception => Nil
  }

  def save_hosts_=(hosts: Seq[String]): Unit = config.edit().also { c =>
    c.remove(SYSTEM_HOSTS)
    if (hosts.nonEmpty)
      c.remove(SYSTEM_HOSTS).putString(SYSTEM_HOSTS, (new JSONArray() /: hosts.distinct) ((j, i) => j.put(i)).toString)
  }.apply()

  def hosts: Seq[String] = (save_hosts ++ default_hosts()).distinct

  private def _host: String = config.getString(SYSTEM_HOST, null)

  def host: String = _host.takeIf(_.isNonEmpty).getOrElse(hosts.head)

  def host_=(host: String): Unit = config.edit().also { c =>
    if (host.isNullOrEmpty) c.remove(SYSTEM_HOST) else c.putString(SYSTEM_HOST, host)
  }.apply()

  def bbs: String = default_bbs()

  def category: Seq[(String, String)] = default_category()

  if (_host.isNullOrEmpty) host = hosts.head

  def update(context: Activity, tip: Boolean)(f: => Unit): Unit = {
    "https://raw.githubusercontent.com/yueeng/hacg/master/app/src/main/assets/config.json".httpGetAsync(context) {
      case Some((json, _)) =>
        if (json != config_string) {
          context.snack(context.getString(R.string.settings_config_updating), Snackbar.LENGTH_LONG)
            .setAction(R.string.settings_config_update, viewClick { _ =>
              try {
                val config = new JSONObject(json)
                host = default_hosts(Option(config)).head
                new PrintWriter(config_file).using(_.write(json))
                f
              } catch {
                case _: Exception =>
              }
            }).show()
        } else {
          if (tip) context.toast(R.string.settings_config_newest)
        }
      case _ =>
    }
  }

  val IsHttp: Regex = """^https?://.*$""".r
  val RELEASE = "https://github.com/yueeng/hacg/releases"

  def web = s"https://$host"

  def domain: String = host.indexOf('/') match {
    case i if i >= 0 => host.substring(0, i)
    case _ => host
  }

  def wordpress: String = s"$web/wp"

  def philosophy: String = bbs match {
    case IsHttp() => bbs
    case _ => s"$web$bbs"
  }

  def setHostEdit(context: Context, title: Int, list: () => Seq[String], cur: () => String, set: String => Unit, ok: String => Unit, reset: () => Unit): Unit = {
    val view = LayoutInflater.from(context).inflate(R.layout.alert_host, null, false)
    val edit = view.findViewById[EditText](R.id.edit1)
    edit.setInputType(InputType.TYPE_TEXT_VARIATION_URI)
    new Builder(context)
      .setTitle(title)
      .setView(view)
      .setNegativeButton(R.string.app_cancel, null)
      .setOnDismissListener(dialogDismiss { _ => setHostList(context, title, list, cur, set, ok, reset) })
      .setNeutralButton(R.string.settings_host_reset,
        dialogClick { (_, _) => reset() })
      .setPositiveButton(R.string.app_ok,
        dialogClick { (_, _) =>
          val host = edit.getText.toString
          if (host.isNonEmpty) {
            ok(host)
          }
        })
      .create().show()
  }

  def setHostList(context: Context, title: Int, list: () => Seq[String], cur: () => String, set: String => Unit, ok: String => Unit, reset: () => Unit): Unit = {
    val hosts = list().toList
    new Builder(context)
      .setTitle(title)
      .setSingleChoiceItems(hosts.map(_.asInstanceOf[CharSequence]).toArray, hosts.indexOf(cur()) match { case -1 => 0 case x => x }, null)
      .setNegativeButton(R.string.app_cancel, null)
      .setNeutralButton(R.string.settings_host_more, dialogClick { (_, _) => setHostEdit(context, title, list, cur, set, ok, reset) })
      .setPositiveButton(R.string.app_ok, dialogClick { (d, _) => set(hosts(d.asInstanceOf[AlertDialog].getListView.getCheckedItemPosition).toString) })
      .create().show()
  }

  def setHost(context: Context, ok: String => Unit = null): Unit = {
    setHostList(context,
      R.string.settings_host,
      () => HAcg.hosts,
      () => HAcg.host,
      host => {
        HAcg.host = host
        if (ok != null) ok(host)
      },
      host => HAcg.save_hosts = HAcg.save_hosts ++ Seq(host),
      () => HAcg.save_hosts = Nil
    )
  }
}

object HAcgApplication {
  private var _instance: HAcgApplication = _

  def instance: HAcgApplication = _instance
}

class HAcgApplication extends MultiDexApplication {
  HAcgApplication._instance = this

  override def onCreate(): Unit = {
    super.onCreate()
    CookieHandler.setDefault(CookieManagerProxy.instance)
  }
}

object Common {
  //  implicit def viewTo[T <: View](view: View): T = view.asInstanceOf[T]

  implicit def viewClick(func: View => Unit): View.OnClickListener = (view: View) => func(view)

  implicit def viewLongClick(func: View => Boolean): View.OnLongClickListener = (v: View) => func(v)

  implicit def dialogClick(func: (DialogInterface, Int) => Unit): DialogInterface.OnClickListener = (dialog: DialogInterface, which: Int) => func(dialog, which)

  implicit def dialogDismiss(func: DialogInterface => Unit): DialogInterface.OnDismissListener = (dialog: DialogInterface) => func(dialog)

  implicit def pair[F, S](p: (F, S)): android.support.v4.util.Pair[F, S] =
    new android.support.v4.util.Pair[F, S](p._1, p._2)

  implicit class fragmentex(f: Fragment) {
    def arguments(b: Bundle): Fragment = if (b != null) f.also(_.setArguments(b)) else f
  }

  implicit class bundleex(b: Bundle) {
    def string(key: String, value: String): Bundle = b.also(_.putString(key, value))

    def parcelable(key: String, value: Parcelable): Bundle = b.also(_.putParcelable(key, value))
  }

  implicit class StringUtil(s: String) {
    def isNullOrEmpty: Boolean = s == null || s.isEmpty

    def isNonEmpty: Boolean = !isNullOrEmpty

    def html: Spanned = HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_COMPACT)
  }

  private val datefmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ")

  implicit def string2date(str: String): Option[Date] = try Option(datefmt.parse(str)) catch {
    case _: ParseException => None
  }

  implicit def date2long(date: Date): Long = date.getTime

  implicit def date2string(date: Date): String = datefmt.format(date)

  //noinspection ScalaUnusedSymbol
  type Closable = {def close(): Unit}

  def using[A, B <: Closable](closable: B)(f: B => A): A = try f(closable) finally {
    closable.close()
  }

  def let[A, B](o: A)(f: A => B): B = f(o)

  def also[A](o: A)(f: A => Unit): A = {
    f(o)
    o
  }

  def takeIf[A](o: A)(f: A => Boolean): Option[A] = Option(o).filter(f)

  implicit def any2ex[A](o: A): anyex[A] = new anyex(o)

  class anyex[A](o: A) {
    def let[B](f: A => B): B = Common.let(o)(f)

    def also(f: A => Unit): A = Common.also(o)(f)

    def takeIf(f: A => Boolean): Option[A] = Common.takeIf(o)(f)
  }

  implicit class usingex[B <: Closable](closable: B) {
    def using[A](f: B => A): A = Common.using(closable)(f)
  }

  implicit class digest2string(s: String) {
    def md5: String = MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X".format(_)).mkString

    def sha1: String = MessageDigest.getInstance("SHA1").digest(s.getBytes).map("%02X".format(_)).mkString
  }

  implicit class contextex(context: Context) {

    def toast(msg: Int): Toast = Toast.makeText(context, msg, Toast.LENGTH_SHORT).also(_.show())

    def toast(msg: String): Toast = Toast.makeText(context, msg, Toast.LENGTH_SHORT).also(_.show())

    def clipboard(label: String, text: String): Unit = {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
      val clip = ClipData.newPlainText(label, text)
      clipboard.setPrimaryClip(clip)
      context.toast(context.getString(R.string.app_copied, text))
    }
  }

  implicit class viewgroupex(container: ViewGroup) {
    def inflate(layout: Int, attach: Boolean = false): View = LayoutInflater.from(container.getContext).inflate(layout, container, attach)
  }

  implicit class viewex(view: View) {
    def childrenRecursiveSequence(): Seq[View] = {
      view match {
        case vg: ViewGroup =>
          for (i <- 0 until vg.getChildCount;
               v = vg.getChildAt(i);
               vv <- Seq(v) ++ v.childrenRecursiveSequence())
            yield vv
        case _ => Seq.empty
      }
    }

    import scala.reflect.ClassTag

    def findViewByViewType[T <: View](id: Int = 0)(implicit tag: ClassTag[T]): Seq[View] = view.childrenRecursiveSequence()
      .filter { it => tag.runtimeClass.isInstance(it) }.filter { it => id == 0 || id == it.getId }
  }

  implicit class activityex(activity: Activity) {

    def snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar = activity.getWindow.getDecorView.let { view =>
      view.findViewByViewType[CoordinatorLayout]().headOption.getOrElse(view)
    }.let { it => Snackbar.make(it, text, duration) }

  }

  class Once {
    private var init = false

    def run(call: () => Unit) {
      init.synchronized {
        if (init) return
        init = true
      }
      call()
    }
  }

  implicit class recyclerex(list: RecyclerView) {
    def loading(last: Int = 1)(call: () => Unit) {
      list.addOnScrollListener(new RecyclerView.OnScrollListener() {
        def load(recycler: RecyclerView) {
          val layout = recycler.getLayoutManager
          layout match {
            case layout: StaggeredGridLayoutManager =>
              val vis = layout.findLastVisibleItemPositions(null)
              val v = if (vis.nonEmpty) vis.max else 0
              if (v >= list.getAdapter.getItemCount - last) call()
            case layout: GridLayoutManager =>
              if (layout.findLastVisibleItemPosition() >= list.getAdapter.getItemCount - last) call()
            case layout: LinearLayoutManager =>
              if (layout.findLastVisibleItemPosition() >= list.getAdapter.getItemCount - last) call()
          }
        }

        val once = new Once()

        override def onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
          once.run { () =>
            load(recycler)
          }
        }

        override def onScrollStateChanged(recycler: RecyclerView, state: Int) {
          if (state != RecyclerView.SCROLL_STATE_IDLE) return
          load(recycler)
        }
      })
    }
  }

  private val img = List(".jpg", ".png", ".webp")

  object ContextHelper {
    val handler = new Handler(Looper.getMainLooper)
    val mainThread: Thread = Looper.getMainLooper.getThread

    def ui(call: () => Unit): Unit = if (mainThread == Thread.currentThread()) call()
    else handler.post(() => call())
  }

  class AsyncScalaContext[T <: AnyRef](weak: WeakReference[T]) {
    def ui(func: T => Unit): Boolean = weak.get match {
      case None => false
      case Some(ref) =>
        //noinspection ScalaDeprecation
        if (ref match {
          case f: Fragment if !f.isAdded => false
          case f: app.Fragment if !f.isAdded => false
          case _ => true
        }) try {
          ContextHelper.ui(() => func(ref))
          true
        } catch {
          case _: Throwable => false
        }
        else false
    }
  }

  implicit def fragment2context(f: Fragment): Context = f.getContext

  def async[T <: AnyRef](context: T)(task: AsyncScalaContext[_] => Unit): Future[Unit] = {
    val weak = new AsyncScalaContext(new WeakReference(context))
    BackgroundExecutor.submit { () =>
      try task(weak) catch {
        case _: Throwable =>
      }
    }
  }

  object BackgroundExecutor {
    val executor: ExecutorService =
      Executors.newScheduledThreadPool(2 * Runtime.getRuntime.availableProcessors())

    def submit[T](task: () => T): Future[T] = executor.submit(() => task())
  }

  implicit class httpex(url: String) {
    def isImg: Boolean = img.exists(url.toLowerCase.endsWith)

    def httpGet: Option[(String, String)] = try {
      val http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
      val request = new Request.Builder().get().url(url).build()
      val response = http.newCall(request).execute()
      Option(response.body().string(), response.request().url().toString)
    } catch {
      case e: Exception => e.printStackTrace(); None
    }

    def httpGetAsync(context: Context)(callback: Option[(String, String)] => Unit): Unit = {
      async(context) { c =>
        val result = url.httpGet
        c.ui { _ => callback(result) }
      }
    }

    def httpPost(post: Map[String, String]): Option[(String, String)] = try {
      val http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
      val data = (new MultipartBody.Builder /: post) ((b, o) => b.addFormDataPart(o._1, o._2)).build()
      val request = new Request.Builder().url(url).post(data).build()
      val response = http.newCall(request).execute()
      Option(response.body().string(), response.request().url().toString)
    } catch {
      case _: Exception => None
    }

    def httpDownloadAsync(context: Context, file: String = null)(fn: Option[File] => Unit): Future[Unit] = async(context) { c =>
      val result = url.httpDownload(file)
      c.ui(_ => fn(result))
    }

    def httpDownload(file: String = null): Option[File] = try {
      //      System.out.println(url)
      val http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
      val request = new Request.Builder().get().url(url).build()
      val response = http.newCall(request).execute()

      val target = if (file == null) {
        val path = response.request().url().uri().getPath
        new File(HAcgApplication.instance.getExternalCacheDir, path.substring(path.lastIndexOf('/') + 1))
      } else {
        new File(file)
      }

      val sink = Okio.buffer(Okio.sink(target))
      sink.writeAll(response.body().source())
      sink.close()
      Option(target)
    } catch {
      case e: Exception => e.printStackTrace(); None
    }
  }

  implicit class jsoupex(html: Option[(String, String)]) {
    def jsoup: Option[Document] = html match {
      case Some(h) => Option(Jsoup.parse(h._1, h._2))
      case _ => None
    }

    def jsoup[T](f: Document => T): Option[T] = {
      html.jsoup match {
        case Some(h) => Option(f(h))
        case _ => None
      }
    }
  }

  def version(context: Context): String = try context.getPackageManager.getPackageInfo(context.getPackageName, 0).versionName catch {
    case e: Exception => e.printStackTrace(); ""
  }

  def versionBefore(local: String, online: String): Boolean = try {
    val l = local.split( """\.""").map(_.toInt).toList
    val o = online.split( """\.""").map(_.toInt).toList
    for (i <- 0 until Math.min(l.length, o.length)) {
      l(i) - o(i) match {
        case x if x > 0 => return false
        case x if x < 0 => return true
        case _ =>
      }
    }
    if (o.length > l.length) {
      o.drop(l.length).exists(_ != 0)
    } else false
  } catch {
    case _: Exception => false
  }

  def openWeb(context: Context, uri: String): Unit =
    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

  val random = new Random(System.currentTimeMillis())

  def randomColor(alpha: Int = 0xFF): Int = android.graphics.Color.HSVToColor(alpha, Array[Float](random.nextInt(360), 1, 0.5F))

}

abstract class ScalaTask[A, P, R] extends AsyncTask[A, P, R] {
  final override def doInBackground(params: A*): R = background(params: _*)

  final override def onPreExecute(): Unit = super.onPreExecute()

  final override def onPostExecute(result: R): Unit = post(result)

  final override def onProgressUpdate(values: P*): Unit = progress(values: _*)

  def pre(): Unit = {}

  def post(result: R): Unit = {}

  def progress(values: P*): Unit = {}

  def background(params: A*): R
}

class CookieManagerProxy(store: CookieStore, policy: CookiePolicy) extends CookieManager(store, policy) {

  private val SetCookie: String = "Set-Cookie"

  override def put(uri: URI, headers: util.Map[String, util.List[String]]): Unit = {
    super.put(uri, headers)
    getCookieStore.get(uri)
      .filter(o => o.getDomain != null && !o.getDomain.startsWith("."))
      .foreach(o => o.setDomain(s".${o.getDomain}"))
  }

  def put(uri: URI, cookies: String): Unit = cookies match {
    case null =>
    case _ =>
      this.put(uri, Map[String, util.List[String]](SetCookie -> cookies.split(";").map(_.trim).toList))
  }
}

object CookieManagerProxy {
  val instance = new CookieManagerProxy(new PersistCookieStore(HAcgApplication.instance), CookiePolicy.ACCEPT_ALL)
}

/**
  * PersistentCookieStore
  * Created by Rain on 2015/7/1.
  */
class PersistCookieStore(context: Context) extends CookieStore {
  private final val map = new mutable.HashMap[URI, mutable.HashSet[HttpCookie]]
  private final val pref: SharedPreferences = context.getSharedPreferences("cookies.pref", Context.MODE_PRIVATE)

  pref.getAll.collect { case (k: String, v: String) if !v.isEmpty => (k, v.split(",")) }
    .foreach { o =>
      map(URI.create(o._1)) = mutable.HashSet() ++= o._2.flatMap { c =>
        try HttpCookie.parse(c) catch {
          case _: Throwable => Nil
        }
      }
    }

  implicit class httpCookie(c: HttpCookie) {
    def string: String = {
      if (c.getVersion != 0) {
        return c.toString
      }
      Map(c.getName -> c.getValue, "domain" -> c.getDomain)
        .view
        .filter(_._2 != null)
        .filter(!_._2.isEmpty)
        .map(o => s"${o._1}=${o._2}")
        .mkString("; ")
    }
  }

  private def cookiesUri(uri: URI): URI = {
    if (uri == null) {
      return null
    }
    try new URI("http", uri.getHost, null, null) catch {
      case _: URISyntaxException => uri
    }
  }

  override def add(url: URI, cookie: HttpCookie): Unit = map.synchronized {
    if (cookie == null) {
      throw new NullPointerException("cookie == null")
    }
    cookie.getDomain match {
      case domain if !domain.startsWith(".") => cookie.setDomain(s".$domain")
      case _ =>
    }

    val uri = cookiesUri(url)

    if (map.contains(uri)) {
      map(uri) += cookie
    } else {
      map(uri) = new mutable.HashSet() += cookie
    }

    pref.edit.putString(uri.toString, map(uri).map(_.string).toSet.mkString(",")).apply()
  }

  def remove(url: URI, cookie: HttpCookie): Boolean = map.synchronized {
    if (cookie == null) {
      throw new NullPointerException("cookie == null")
    }
    val uri = cookiesUri(url)
    map.get(uri) match {
      case Some(cookies) if cookies.contains(cookie) =>
        cookies.remove(cookie)
        pref.edit.putString(uri.toString, cookies.map(_.string).toSet.mkString(",")).apply()
        true
      case _ => false
    }
  }

  override def removeAll(): Boolean = map.synchronized {
    val result = map.nonEmpty
    map.clear()
    pref.edit.clear.apply()
    result
  }

  override def getURIs: util.List[URI] = map.synchronized {
    map.keySet.filter(o => o != null).toList
  }

  def expire(uri: URI, cookies: mutable.HashSet[HttpCookie], edit: SharedPreferences.Editor)(fn: HttpCookie => Boolean = _ => true): Unit = {
    cookies.filter(fn).filter(_.hasExpired) match {
      case ex if ex.nonEmpty =>
        cookies --= ex
        edit.putString(uri.toString, cookies.map(_.string).toSet.mkString(",")).apply()
      case _ =>
    }
  }

  override def getCookies: util.List[HttpCookie] = map.synchronized {
    (pref.edit() /: map) { (e, o) => expire(o._1, o._2, e)(); e }.apply()
    map.values.flatten.toList.distinct
  }

  override def get(uri: URI): util.List[HttpCookie] = map.synchronized {
    if (uri == null) {
      throw new NullPointerException("uri == null")
    }
    val edit = pref.edit()
    map.get(uri) match {
      case Some(cookies) => expire(uri, cookies, edit)()
      case _ =>
    }
    map.filter(_._1 != uri).foreach {
      o => expire(o._1, o._2, edit)(c => HttpCookie.domainMatches(c.getDomain, uri.getHost))
    }
    edit.apply()
    (map.getOrElse(uri, Nil) ++ map.values.flatMap(o => o.filter(c => HttpCookie.domainMatches(c.getDomain, uri.getHost)))).toList.distinct
  }
}

object ViewBinder {

  class ViewBinder[T, V <: View](private var _value: T)(private val func: (V, T) => Unit) {
    def apply(): T = _value

    private val _views = ListBuffer.empty[WeakReference[V]]

    def +=(v: V): ViewBinder[T, V] = synchronized {
      _views += new WeakReference(v)
      if (_value != null) func(v, _value)
      this
    }

    def -=(v: V): ViewBinder[T, V] = synchronized {
      _views --= _views.filter(p => p.get.isEmpty || p.get.contains(v))
      this
    }

    def <=(v: T): ViewBinder[T, V] = synchronized {
      _value = v
      _views --= _views.filter(p => p.get.isEmpty)
      if (_value != null) _views.filter(_.get.nonEmpty).foreach(p => func(p.get.get, v))
      this
    }

    def views: List[V] = synchronized(_views.filter(_.get.nonEmpty).map(_.get.get).toList)
  }

  abstract class ErrorBinder(value: Boolean) extends ViewBinder[Boolean, View](value)((v, t) => v.setVisibility(if (t) View.VISIBLE else View.INVISIBLE)) {
    override def +=(v: View): ViewBinder[Boolean, View] = {
      v.setOnClickListener(viewClick(_ => retry()))
      super.+=(v)
    }

    def retry(): Unit
  }

}

abstract class DataAdapter[V, VH <: RecyclerView.ViewHolder] extends RecyclerView.Adapter[VH] {
  val data: ListBuffer[V] = ListBuffer[V]()

  override def getItemCount: Int = size

  def size: Int = data.size

  def clear(): DataAdapter[V, VH] = {
    val size = data.size
    data.clear()
    notifyItemRangeRemoved(0, size)
    this
  }

  def +=(v: V): DataAdapter[V, VH] = {
    data += v
    notifyItemInserted(data.size)
    this
  }

  def ++=(v: TraversableOnce[V]): DataAdapter[V, VH] = {
    data ++= v
    notifyItemRangeInserted(data.size - v.size, v.size)
    this
  }
}

object SpanUtil {

  class RoundedBackgroundColorSpan(private val backgroundColor: Int) extends ReplacementSpan() {
    private val linePadding = 2f // play around with these as needed
    private val sidePadding = 5f // play around with these as needed
    private def MeasureText(paint: Paint, text: CharSequence, start: Int, end: Int): Float = {
      paint.measureText(text, start, end)
    }

    override def getSize(paint: Paint, text: CharSequence, start: Int, end: Int, p4: Paint.FontMetricsInt): Int = {
      Math.round(MeasureText(paint, text, start, end) + (2 * sidePadding))
    }

    override def draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
      //      System.out.println("$start, $end, $x, $top, $y, $bottom, ${paint.fontMetrics.top}, ${paint.fontMetrics.bottom}, ${paint.fontMetrics.leading}, ${paint.fontMetrics.ascent}, ${paint.fontMetrics.descent}, ${paint.fontMetrics.descent - paint.fontMetrics.ascent}")
      val rect = new RectF(x, y + paint.getFontMetrics.top - linePadding,
        x + getSize(paint, text, start, end, paint.getFontMetricsInt()),
        y + paint.getFontMetrics.bottom + linePadding)
      paint.setColor(backgroundColor)
      canvas.drawRoundRect(rect, 5F, 5F, paint)
      paint.setColor(0xFFFFFFFF)
      canvas.drawText(text, start, end, x + sidePadding, y * 1F, paint)
    }

  }

  class TagClickableSpan[T](private val tag: T, private val call: T => Unit = null) extends ClickableSpan() {
    override def onClick(widget: View) {
      if (call != null) call(tag)
    }

    override def updateDrawState(ds: TextPaint) {
      ds.setColor(0xFFFFFFFF)
      ds.setUnderlineText(false)
    }
  }

  def spannable[T](list: List[T])(separator: String = " ", t2str: T => String = { s: T => s"$s" }, call: T => Unit = null): SpannableStringBuilder = {
    val tags = list.map(t2str(_)).mkString(separator)
    val span = new SpannableStringBuilder(tags)
    list.foldLeft(0) { (i, it) =>
      val p = tags.indexOf(t2str(it), i)
      val e = p + t2str(it).length
      if (call != null) span.setSpan(new TagClickableSpan(it, call), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      span.setSpan(new RoundedBackgroundColorSpan(randomColor(0xBF)), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      e
    }
    span
  }
}

class PagerSlidingPaneLayout(context: Context, attrs: AttributeSet, defStyle: Int) extends SlidingPaneLayout(context, attrs, defStyle) {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

  def this(context: Context) = this(context, null)

  private var mInitialMotionX: Float = 0F
  private var mInitialMotionY: Float = 0F
  private val mEdgeSlop: Float = ViewConfiguration.get(context).getScaledEdgeSlop
  var isSwipeEnabled: Boolean = true

  override def onTouchEvent(ev: MotionEvent): Boolean = {
    !isSwipeEnabled || super.onTouchEvent(ev)
  }

  override def onInterceptTouchEvent(ev: MotionEvent): Boolean = {
    if (!isSwipeEnabled) return false
    ev.getAction match {
      case MotionEvent.ACTION_DOWN =>
        mInitialMotionX = ev.getX
        mInitialMotionY = ev.getY
      case MotionEvent.ACTION_MOVE =>
        val x = ev.getX
        val y = ev.getY
        if (mInitialMotionX > mEdgeSlop && !isOpen && canScroll(this, false,
          Math.round(x - mInitialMotionX), Math.round(x), Math.round(y))) {
          return super.onInterceptTouchEvent(MotionEvent.obtain(ev).also { me =>
            me.setAction(MotionEvent.ACTION_CANCEL)
          })
        }
      case _ =>
    }
    super.onInterceptTouchEvent(ev)
  }
}

class BaseSlideCloseActivity extends AppCompatActivity() with SlidingPaneLayout.PanelSlideListener {

  override def onCreate(state: Bundle) {
    swipe()
    super.onCreate(state)
  }

  private def swipe() {
    val swipe = new PagerSlidingPaneLayout(this)
    // 通过反射改变mOverhangSize的值为0，
    // 这个mOverhangSize值为菜单到右边屏幕的最短距离，
    // 默认是32dp，现在给它改成0
    try {
      val overhang = classOf[SlidingPaneLayout].getDeclaredField("mOverhangSize")
      overhang.setAccessible(true)
      overhang.set(swipe, 0)
    } catch {
      case e: Exception => e.printStackTrace()
    }

    swipe.setPanelSlideListener(this)
    swipe.setSliderFadeColor(ContextCompat.getColor(this, android.R.color.transparent))

    // 左侧的透明视图
    val leftView = new View(this)
    leftView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    swipe.addView(leftView, 0)
    swipe.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).also { lp =>
      lp.bottomMargin = getSoftButtonsBarHeight
    })
    val decorView = getWindow.getDecorView.asInstanceOf[ViewGroup]

    // 右侧的内容视图
    val decorChild = decorView.getChildAt(0).asInstanceOf[ViewGroup]
    getTheme.obtainStyledAttributes(Array[Int](android.R.attr.colorBackground)).also { ta =>
      decorChild.setBackgroundColor(ta.getColor(0, 0))
    }.recycle()
    decorChild.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
    decorView.removeView(decorChild)
    decorView.addView(swipe)

    // 为 SlidingPaneLayout 添加内容视图
    swipe.addView(decorChild, 1)
  }

  private def getSoftButtonsBarHeight: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
    val metrics = new DisplayMetrics()
    getWindowManager.getDefaultDisplay.getMetrics(metrics)
    val usableHeight = metrics.heightPixels
    getWindowManager.getDefaultDisplay.getRealMetrics(metrics)
    val realHeight = metrics.heightPixels
    if (realHeight > usableHeight)
      realHeight - usableHeight
    else
      0
  } else 0

  override def onPanelSlide(panel: View, slideOffset: Float) {

  }

  override def onPanelOpened(panel: View) {
    finish()
  }

  override def onPanelClosed(panel: View) {

  }

}