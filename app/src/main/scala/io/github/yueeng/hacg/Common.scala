package io.github.yueeng.hacg

import java.io.File
import java.lang.ref.WeakReference
import java.net._
import java.security.MessageDigest
import java.text.{ParseException, SimpleDateFormat}
import java.util
import java.util.Date
import java.util.concurrent._

import android.content.DialogInterface.OnDismissListener
import android.content.{Context, DialogInterface, Intent, SharedPreferences}
import android.net.Uri
import android.os._
import android.preference.PreferenceManager
import android.support.multidex.MultiDexApplication
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AlertDialog.Builder
import android.text.InputType
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.{EditText, Toast}
import io.github.yueeng.hacg.Common._
import okhttp3.{MultipartBody, OkHttpClient, Request}
import okio.Okio
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.{implicitConversions, postfixOps, reflectiveCalls}
import scala.util.Random

object HAcg {
  private val SYSTEM_HOST: String = "system.host"
  private val SYSTEM_HOSTS: String = "system.hosts"
  private val SYSTEM_PHILOSOPHY: String = "system.philosophy"
  private val SYSTEM_PHILOSOPHY_HOSTS: String = "system.philosophy_hosts"

  val DEFAULT_HOSTS = List("www.hacg.me/wp", "www.hacg.li/wp", "www.hacg.be/wp", "www.hacg.club/wp", "www.hacg.lol/wp")
  val DEFAULT_PHILOSOPHY_HOSTS = List("liqu.pro")

  val RELEASE = "https://github.com/yueeng/hacg/releases"

  private val config = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance)

  def host = config.getString(SYSTEM_HOST, DEFAULT_HOSTS.head)

  def host_=(host: String) = config.edit().putString(SYSTEM_HOST, host).commit()

  def hosts = config.getStringSet(SYSTEM_HOSTS, DEFAULT_HOSTS.toSet[String]).toSet

  def hosts_=(hosts: Set[String]) = config.edit().putStringSet(SYSTEM_HOSTS, hosts).commit()

  def web = s"http://$host"

  def domain = host.indexOf('/') match {
    case i if i >= 0 => host.substring(0, i)
    case _ => host
  }

  //  def wordpress = s"$web/wordpress"

  def philosophy_host = config.getString(SYSTEM_PHILOSOPHY, DEFAULT_PHILOSOPHY_HOSTS.head)

  def philosophy_host_=(host: String) = config.edit().putString(SYSTEM_PHILOSOPHY, host).commit()

  def philosophy_hosts = config.getStringSet(SYSTEM_PHILOSOPHY_HOSTS, DEFAULT_PHILOSOPHY_HOSTS.toSet[String]).toSet

  def philosophy_hosts_=(hosts: Set[String]) = config.edit().putStringSet(SYSTEM_PHILOSOPHY_HOSTS, hosts).commit()

  def philosophy = s"http://$philosophy_host"

  def setHosts(context: Context, title: Int, hint: Int, hostlist: () => Set[String], cur: () => String, set: String => Unit, ok: String => Unit, reset: () => Unit): Unit = {
    val edit = new EditText(context)
    if (hint != 0) {
      edit.setHint(hint)
    }
    edit.setInputType(InputType.TYPE_TEXT_VARIATION_URI)
    new Builder(context)
      .setTitle(title)
      .setView(edit)
      .setNegativeButton(R.string.app_cancel, null)
      .setOnDismissListener(dialogDismiss { d => setHostx(context, title, hint, hostlist, cur, set, ok, reset) })
      .setNeutralButton(R.string.settings_host_reset,
        dialogClick { (d, w) => reset() })
      .setPositiveButton(R.string.app_ok,
        dialogClick { (d, w) =>
          val host = edit.getText.toString
          if (host.isNonEmpty) {
            ok(host)
          } else {
            Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
          }
        })
      .create().show()
  }

  def setHostx(context: Context, title: Int, hint: Int, hostlist: () => Set[String], cur: () => String, set: String => Unit, ok: String => Unit, reset: () => Unit): Unit = {
    val hosts = hostlist().toList
    new Builder(context)
      .setTitle(title)
      .setSingleChoiceItems(hosts.map(_.asInstanceOf[CharSequence]).toArray, hosts.indexOf(cur()) match { case -1 => 0 case x => x }, null)
      .setNegativeButton(R.string.app_cancel, null)
      .setNeutralButton(R.string.settings_host_more, dialogClick { (d, w) => setHosts(context, title, hint, hostlist, cur, set, ok, reset) })
      .setPositiveButton(R.string.app_ok, dialogClick { (d, w) => set(hosts(d.asInstanceOf[AlertDialog].getListView.getCheckedItemPosition).toString) })
      .create().show()
  }

  def setHost(context: Context, ok: String => Unit = null): Unit = {
    setHostx(context,
      R.string.settings_host,
      R.string.settings_host_sample,
      () => HAcg.hosts,
      () => HAcg.host,
      host => {
        HAcg.host = host
        if (ok != null) ok(host)
      },
      host => HAcg.hosts = HAcg.hosts + host,
      () => HAcg.hosts = HAcg.DEFAULT_HOSTS.toSet
    )
  }

  def setPhilosophy(context: Context, ok: String => Unit = null): Unit = {
    setHostx(context,
      R.string.settings_philosophy_host,
      R.string.settings_philosophy_sample,
      () => HAcg.philosophy_hosts,
      () => HAcg.philosophy_host,
      host => {
        HAcg.philosophy_host = host
        if (ok != null) ok(host)
      },
      host => HAcg.philosophy_hosts = HAcg.philosophy_hosts + host,
      () => HAcg.philosophy_hosts = HAcg.DEFAULT_PHILOSOPHY_HOSTS.toSet
    )
  }

}

object HAcgApplication {
  private var _instance: HAcgApplication = _

  def instance = _instance
}

class HAcgApplication extends MultiDexApplication {
  HAcgApplication._instance = this

  override def onCreate(): Unit = {
    super.onCreate()
    CookieHandler.setDefault(CookieManagerProxy.instance)
  }
}

object Common {
  implicit def viewTo[T <: View](view: View): T = view.asInstanceOf[T]

  implicit def viewClick(func: View => Unit): View.OnClickListener = new View.OnClickListener {
    override def onClick(view: View): Unit = func(view)
  }

  implicit def viewLongClick(func: View => Boolean): View.OnLongClickListener = new OnLongClickListener {
    override def onLongClick(v: View): Boolean = func(v)
  }

  implicit def dialogClick(func: (DialogInterface, Int) => Unit): DialogInterface.OnClickListener = new DialogInterface.OnClickListener {
    override def onClick(dialog: DialogInterface, which: Int): Unit = func(dialog, which)
  }

  implicit def dialogDismiss(func: DialogInterface => Unit): DialogInterface.OnDismissListener = new OnDismissListener {
    override def onDismiss(dialog: DialogInterface): Unit = func(dialog)
  }

  implicit def runnable(func: () => Unit): Runnable = new Runnable {
    override def run(): Unit = func()
  }

  implicit def pair(p: (View, String)): android.support.v4.util.Pair[View, String] =
    new android.support.v4.util.Pair[View, String](p._1, p._2)

  implicit class fragmentex(f: Fragment) {
    def arguments(b: Bundle) = {
      if (b != null) {
        f.setArguments(b)
      }
      f
    }
  }

  implicit class bundleex(b: Bundle) {
    def string(key: String, value: String) = {
      b.putString(key, value)
      b
    }

    def parcelable(key: String, value: Parcelable) = {
      b.putParcelable(key, value)
      b
    }
  }

  implicit class StringUtil(s: String) {
    def isNullOrEmpty = s == null || s.isEmpty

    def isNonEmpty = !isNullOrEmpty
  }

  private val datefmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ")

  implicit def string2date(str: String): Option[Date] = {
    try {
      Option(datefmt.parse(str))
    } catch {
      case _: ParseException => None
    }
  }

  implicit def date2long(date: Date): Long = date.getTime

  implicit def date2string(date: Date): String = datefmt.format(date)

  def using[A, B <: {def close() : Unit}](closeable: B)(f: B => A): A =
    try {
      f(closeable)
    } finally {
      closeable.close()
    }

  implicit class ReduceMap[T](list: List[T]) {
    def reduceMap(func: T => List[T]): List[T] = {
      list match {
        case Nil => Nil
        case head :: tail => head :: func(head).reduceMap(func) ::: tail.reduceMap(func)
      }
    }
  }

  implicit class digest2string(s: String) {
    def md5 = MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X".format(_)).mkString

    def sha1 = MessageDigest.getInstance("SHA1").digest(s.getBytes).map("%02X".format(_)).mkString
  }

  private val img = List(".jpg", ".png", ".webp")

  implicit class httpex(url: String) {
    def isImg = img.exists(url.toLowerCase.endsWith)

    def httpGet = {
      try {
        val http = new OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .build()
        val request = new Request.Builder().get().url(url).build()
        val response = http.newCall(request).execute()
        Option(response.body().string(), response.request().url().toString)
      } catch {
        case _: Exception => None
      }
    }

    def httpPost(post: Map[String, String]) = {
      try {
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
    }

    def httpDownloadAsync(file: String = null)(fn: Option[File] => Unit) = {
      new ScalaTask[Unit, Unit, Option[File]] {
        override def background(params: Unit*): Option[File] = url.httpDownload(file)

        override def post(result: Option[File]): Unit = fn(result)
      }.execute()
    }

    def httpDownload(file: String = null): Option[File] = {
      try {
        System.out.println(url)
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
  }

  implicit class jsoupex(html: Option[(String, String)]) {
    def jsoup = html match {
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

  def version(context: Context): String = {
    try context.getPackageManager.getPackageInfo(context.getPackageName, 0).versionName catch {
      case e: Exception => e.printStackTrace(); ""
    }
  }

  def versionBefore(local: String, online: String): Boolean = {
    try {
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
        return o.drop(l.length).exists(_ != 0)
      }
    } catch {
      case e: Exception =>
    }
    false
  }

  def openWeb(context: Context, uri: String) =
    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

  val random = new Random(System.currentTimeMillis())

  def randomColor(alpha: Int = 0xFF) = android.graphics.Color.HSVToColor(alpha, Array[Float](random.nextInt(360), 1, 0.5F))
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
      map(URI.create(o._1)) = mutable.HashSet() ++= o._2.flatMap {
        c => try HttpCookie.parse(c) catch {
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
      case e: URISyntaxException => uri
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

  def expire(uri: URI, cookies: mutable.HashSet[HttpCookie], edit: SharedPreferences.Editor)(fn: HttpCookie => Boolean = c => true) = {
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

  class ViewBinder[T, V <: View](private var _value: T, private val func: (V, T) => Unit) {
    def apply(): T = _value

    private val _views = ListBuffer.empty[WeakReference[V]]

    def +=(v: V): ViewBinder[T, V] = synchronized {
      _views += new WeakReference(v)
      if (_value != null) func(v, _value)
      this
    }

    def -=(v: V): ViewBinder[T, V] = synchronized {
      _views --= _views.filter(p => p.get == null || p.get == v)
      this
    }

    def <=(v: T): ViewBinder[T, V] = synchronized {
      _value = v
      _views --= _views.filter(p => p.get == null)
      if (_value != null) _views.foreach(p => func(p.get, v))
      this
    }

    def views = synchronized(_views.filter(_.get != null).map(_.get).toList)
  }

  abstract class ErrorBinder(value: Boolean) extends ViewBinder[Boolean, View](value, (v, t) => v.setVisibility(if (t) View.VISIBLE else View.INVISIBLE)) {
    override def +=(v: View): ViewBinder[Boolean, View] = {
      v.setOnClickListener(viewClick(_ => retry()))
      super.+=(v)
    }

    def retry(): Unit
  }

}