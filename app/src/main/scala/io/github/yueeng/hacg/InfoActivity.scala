package io.github.yueeng.hacg

import java.text.SimpleDateFormat

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.DialogInterface.OnShowListener
import android.content._
import android.net.Uri
import android.os.{Build, Bundle}
import android.preference.PreferenceManager
import android.support.design.widget.CollapsingToolbarLayout
import android.support.v4.app.Fragment
import android.support.v4.view.{GravityCompat, ViewCompat}
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener
import android.support.v4.widget.{DrawerLayout, SwipeRefreshLayout}
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.{AlertDialog, AppCompatActivity}
import android.support.v7.widget.RecyclerView.OnScrollListener
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view._
import android.webkit._
import android.widget._
import com.github.clans.fab.{FloatingActionButton, FloatingActionMenu}
import com.squareup.picasso.Picasso
import io.github.yueeng.hacg.Common._
import io.github.yueeng.hacg.ViewBinder.{ErrorBinder, ViewBinder}

import scala.collection.JavaConversions._

/**
  * Info activity
  * Created by Rain on 2015/5/12.
  */

class InfoActivity extends AppCompatActivity {
  lazy val _article = getIntent.getParcelableExtra[Article]("article")

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_info)

    val manager = getSupportFragmentManager

    val fragment = manager.findFragmentById(R.id.container) match {
      case fragment: InfoFragment => fragment
      case _ => new InfoFragment().arguments(new Bundle().parcelable("article", _article))
    }

    manager.beginTransaction().replace(R.id.container, fragment).commit()
  }

  override def onBackPressed(): Unit = {
    getSupportFragmentManager.findFragmentById(R.id.container) match {
      case fragment: InfoFragment if fragment.onBackPressed =>
      case _ => super.onBackPressed()
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home => onBackPressed(); true
      case _ => super.onOptionsItemSelected(item)
    }
  }
}

class InfoFragment extends Fragment {
  lazy val _article = getArguments.getParcelable[Article]("article")
  lazy val _adapter = new CommentAdapter
  val _web = new ViewBinder[(String, String), WebView](null)((view, value) => view.loadDataWithBaseURL(value._2, value._1, "text/html", "utf-8", null))
  val _error = new ErrorBinder(false) {
    override def retry(): Unit = query(_article.link, QUERY_ALL)
  }
  val _post = new scala.collection.mutable.HashMap[String, String]
  var _url: String = _

  val _click = viewClick {
    v => v.getTag match {
      case c: Comment => comment(c)
      case _ =>
    }
  }
  val CONFIG_AUTHOR = "config.author"
  val CONFIG_EMAIL = "config.email"
  val AUTHOR = "author"
  val EMAIL = "email"
  var COMMENTURL = ""
  var COMMENT = "comment"
  val COMMENTPREFIX = "comment-[a-f0-9]{8}"

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
    setRetainInstance(true)
    val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
    _post +=(AUTHOR -> preference.getString(CONFIG_AUTHOR, ""), EMAIL -> preference.getString(CONFIG_EMAIL, ""))
    query(_article.link, QUERY_ALL)
  }

  lazy val _magnet = new ViewBinder[List[String], View](Nil)((view, value) => view.setVisibility(if (value.nonEmpty) View.VISIBLE else View.GONE))

  lazy val _progress = new ViewBinder[Boolean, ProgressBar](false)((view, value) => {
    view.setIndeterminate(value)
    view.setVisibility(if (value) View.VISIBLE else View.INVISIBLE)
  })

  lazy val _progress2 = new ViewBinder[Boolean, SwipeRefreshLayout](false)((view, value) => view.post(() => view.setRefreshing(value)))

  override def onDestroy(): Unit = {
    super.onDestroy()
    _web.views.foreach(_.destroy())
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.fragment_info, container, false)

    val activity = getActivity.asInstanceOf[AppCompatActivity]
    activity.setSupportActionBar(root.findViewById(R.id.toolbar))
    activity.getSupportActionBar.setLogo(R.mipmap.ic_launcher)
    activity.getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    root.findViewById(R.id.toolbar_collapsing) match {
      case c: CollapsingToolbarLayout =>
        c.setExpandedTitleColor(Common.randomColor())
        c.setTitle(_article.title)
      case _ => activity.setTitle(_article.title)
    }

    root.findViewById(R.id.toolbar_image) match {
      case img: ImageView =>
        Picasso.`with`(getActivity).load(_article.img).error(R.drawable.placeholder).into(img)
        ViewCompat.setTransitionName(img, "image")
      case _ =>
    }

    _error += root.findViewById(R.id.image1)
    val list: RecyclerView = root.findViewById(R.id.list1)
    list.setLayoutManager(new LinearLayoutManager(getActivity))
    list.setHasFixedSize(true)
    list.setAdapter(_adapter)

    val drawer: DrawerLayout = root.findViewById(R.id.drawer)
    list.addOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(recyclerView: RecyclerView, state: Int): Unit = {
        (state, _url, list.getLayoutManager) match {
          case (RecyclerView.SCROLL_STATE_IDLE, url, staggered: LinearLayoutManager)
            if url != null && !url.isEmpty && staggered.findLastVisibleItemPosition() >= _adapter.size - 1 =>
            query(url, QUERY_COMMENT)
          case _ =>
        }
        state match {
          case RecyclerView.SCROLL_STATE_IDLE => drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
          case _ => drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
        }
      }
    })

    val menu: FloatingActionMenu = root.findViewById(R.id.menu1)
    menu.setMenuButtonColorNormal(randomColor())
    menu.setMenuButtonColorPressed(randomColor())
    menu.setMenuButtonColorRipple(randomColor())
    val click = viewClick { v =>
      v.getId match {
        case R.id.button1 => Common.openWeb(getActivity, _article.link)
        case R.id.button2 => drawer.openDrawer(GravityCompat.END)
        case R.id.button3 => comment(null)
        case R.id.button4 => share(_article.image)
      }
      menu.close(true)
    }

    List(R.id.button1, R.id.button2, R.id.button3, R.id.button4, R.id.button5).map(root.findViewById).foreach {
      case b: FloatingActionButton =>
        b.setColorNormal(randomColor())
        b.setColorPressed(randomColor())
        b.setColorRipple(randomColor())
      case _ =>
    }
    List(R.id.button1, R.id.button2, R.id.button3, R.id.button4).map(root.findViewById).foreach {
      case b: FloatingActionButton =>
        b.setOnClickListener(click)
      case _ =>
    }
    _progress += root.findViewById(R.id.progress)
    _progress2 += root.findViewById(R.id.swipe)

    _progress2.views.head.setOnRefreshListener(new OnRefreshListener {
      override def onRefresh(): Unit = {
        _url = null
        _adapter.clear()
        query(_article.link, QUERY_COMMENT)
      }
    })
    _magnet += root.findViewById(R.id.button5)
    _magnet.views.head.setOnClickListener(new View.OnClickListener {
      val max = 3
      var magnet = 0
      var toast: Toast = _

      override def onClick(v: View): Unit = magnet match {
        case `max` if _magnet() != null && _magnet().nonEmpty => new Builder(getActivity)
          .setTitle(R.string.app_magnet)
          .setSingleChoiceItems(_magnet().toArray[CharSequence], 0, null)
          .setNegativeButton(R.string.app_cancel, null)
          .setPositiveButton(R.string.app_open, dialogClick { (d, w) =>
            val pos = d.asInstanceOf[AlertDialog].getListView.getCheckedItemPosition
            val link = s"magnet:?xt=urn:btih:${_magnet()(pos)}"
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(link)), getString(R.string.app_magnet)))
          })
          .setNeutralButton(R.string.app_copy, dialogClick { (d, w) =>
            val pos = d.asInstanceOf[AlertDialog].getListView.getCheckedItemPosition
            val link = s"magnet:?xt=urn:btih:${_magnet()(pos)}"
            val clipboard = getActivity.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
            val clip = ClipData.newPlainText(getString(R.string.app_magnet), link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(getActivity, getActivity.getString(R.string.app_copied, link), Toast.LENGTH_SHORT).show()
          }).create().show()
          menu.close(true)
        case _ if magnet < max => magnet += 1
          if (toast != null) toast.cancel()
          toast = Toast.makeText(getActivity, (0 until magnet).map(_ => "...").mkString, Toast.LENGTH_SHORT)
          toast.show()
        case _ =>
      }
    })

    val web: WebView = root.findViewById(R.id.web)
    val settings = web.getSettings
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW)
    }
    settings.setJavaScriptEnabled(true)
    web.setWebViewClient(new WebViewClient {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        val uri = Uri.parse(url)
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri.getScheme))
        true
      }
    })
    web.addJavascriptInterface(new JsFace(), "hacg")
    _web += web
    root
  }

  def share(url: String) = {
    url.httpDownloadAsync(getContext) {
      case Some(file) =>
        val title = _article.title
        val intro = _article.content
        val url = _article.link
        startActivity(Intent.createChooser(
          new Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, s"$title\n$intro $url")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            .putExtra(Intent.EXTRA_REFERRER, Uri.parse(url)),
          title))
      case _ =>
    }
  }

  class JsFace {
    @JavascriptInterface
    def play(name: String, url: String): Unit = {
      startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), "video/mp4"), name))
    }

    @JavascriptInterface
    def save(url: String): Unit = {
      getActivity.runOnUiThread { () =>
        val uri = Uri.parse(url)
        val image = new ImageView(getActivity)
        image.setAdjustViewBounds(true)
        Picasso.`with`(getActivity).load(uri).placeholder(R.drawable.loading).into(image)
        val alert = new Builder(getActivity)
          .setView(image)
          .setNeutralButton(R.string.app_share, dialogClick { (d, w) => share(url) })
          .setPositiveButton(R.string.app_save,
            dialogClick { (d, w) =>
              val manager = HAcgApplication.instance.getSystemService(Context.DOWNLOAD_SERVICE).asInstanceOf[DownloadManager]
              val task = new Request(uri)
              task.allowScanningByMediaScanner()
              task.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              val ext = MimeTypeMap.getFileExtensionFromUrl(url)
              task.setMimeType(MimeTypeMap.getSingleton.getMimeTypeFromExtension(ext))
              manager.enqueue(task)
            })
          .setNegativeButton(R.string.app_cancel, null)
          .create()
        image.setOnClickListener(viewClick { v => alert.dismiss() })
        alert.show()
      }
    }
  }

  def onBackPressed: Boolean = {
    getView.findViewById(R.id.drawer) match {
      case menu: DrawerLayout if menu.isDrawerOpen(GravityCompat.END) =>
        menu.closeDrawer(GravityCompat.END)
        true
      case _ =>
        _web.views.foreach(_.setVisibility(View.INVISIBLE))
        false
    }
  }

  def comment(c: Comment): Unit = {
    if (c == null) {
      commenting(c)
      return
    }
    val alert = new Builder(getActivity)
      .setTitle(c.user)
      .setMessage(c.content)
      .setPositiveButton(R.string.comment_review, dialogClick { (d, w) => commenting(c) })
      .setNegativeButton(R.string.app_cancel, null)
      .setNeutralButton(R.string.app_copy,
        dialogClick { (d, w) =>
          val clipboard = getActivity.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
          val clip = ClipData.newPlainText(c.user, c.content)
          clipboard.setPrimaryClip(clip)
          Toast.makeText(getActivity, getActivity.getString(R.string.app_copied, c.content), Toast.LENGTH_SHORT).show()
        })
      .create()
    alert.setOnShowListener(new OnShowListener {
      override def onShow(dialog: DialogInterface): Unit = {
        def r(v: Seq[View]): Unit = v match {
          case Nil =>
          case a +: b => rr(a); r(b)
        }
        def rr(v: View): Unit = v match {
          case tv: TextView if !tv.isInstanceOf[Button] =>
            tv.setTextIsSelectable(true)
          case vg: ViewGroup =>
            r(for (i <- 0 until vg.getChildCount; sv = vg.getChildAt(i)) yield sv)
          case _ =>
        }
        rr(alert.getWindow.getDecorView)
      }
    })
    alert.show()
  }

  def commenting(c: Comment): Unit = {
    val input = LayoutInflater.from(getActivity).inflate(R.layout.comment_post, null)
    val author: EditText = input.findViewById(R.id.edit1)
    val email: EditText = input.findViewById(R.id.edit2)
    val content: EditText = input.findViewById(R.id.edit3)
    author.setText(_post(AUTHOR))
    email.setText(_post(EMAIL))
    content.setText(_post.getOrElse(COMMENT, ""))
    _post += ("comment_parent" -> (if (c != null) c.id.toString else "0"))
    def fill = {
      _post +=(AUTHOR -> author.getText.toString, EMAIL -> email.getText.toString, COMMENT -> content.getText.toString)
      val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
      preference.edit().putString(CONFIG_AUTHOR, _post(AUTHOR)).putString(CONFIG_EMAIL, _post(EMAIL)).apply()
    }
    new Builder(getActivity)
      .setTitle(if (c != null) getString(R.string.comment_review_to, c.user) else getString(R.string.comment_title))
      .setView(input)
      .setPositiveButton(R.string.comment_submit,
        dialogClick { (d, w) =>
          fill
          if (COMMENTURL.isEmpty || List(AUTHOR, EMAIL, COMMENT).map(_post.getOrElse(_, null)).exists(_.isNullOrEmpty)) {
            Toast.makeText(getActivity, getString(R.string.comment_verify), Toast.LENGTH_SHORT).show()
          } else {
            _progress2 <= true
            async(this) { c =>
              val result = COMMENTURL.httpPost(_post.toMap).jsoup match {
                case Some(dom) =>
                  dom.select("#error-page").headOption match {
                    case Some(e) => (false, e.text())
                    case _ => (true, getString(R.string.comment_succeeded))
                  }
                case _ => (false, getString(R.string.comment_failed))
              }
              c.ui { _ =>
                _progress2 <= false
                if (result._1) {
                  _post(COMMENT) = ""
                  _url = null
                  _adapter.clear()
                  query(_article.link, QUERY_COMMENT)
                }
                Toast.makeText(getActivity, result._2, Toast.LENGTH_LONG).show()
              }
            }
          }
        })
      .setNegativeButton(R.string.app_cancel, null)
      .setOnDismissListener(dialogDismiss { d => fill })
      .create().show()
  }

  val QUERY_WEB = 1
  val QUERY_COMMENT = QUERY_WEB << 1
  val QUERY_ALL = QUERY_WEB | QUERY_COMMENT

  def query(url: String, op: Int): Unit = {
    if (_progress() || _progress2()) {
      return
    }
    _error <= false
    val content = (op & QUERY_WEB) == QUERY_WEB
    val comment = (op & QUERY_COMMENT) == QUERY_COMMENT
    _progress <= content
    _progress2 <= true
    async(this) { c =>
      val result = url.httpGet.jsoup {
        dom =>
          val entry = dom.select(".entry-content")
          entry.select(".toggle-box").foreach(_.removeAttr("style"))
          entry.select("*[style*=display]").filter(i => i.attr("style").matches("display: ?none;?")).foreach(_.remove())
          entry.select(".wp-polls-loading").remove()
          entry.select("script").filter { e => !e.html().contains("renderVideo();") }.foreach(_.remove())
          entry.select(".wp-polls").foreach(div => {
            val node = if (div.parent.attr("class") != "entry-content") div.parent else div
            val name = div.select("strong").headOption match {
              case Some(strong) => strong.text()
              case _ => "投票推荐"
            }
            node.after( s"""<a href="$url">$name</a>""")
            node.remove()
          })

          entry.select("*").removeAttr("class").removeAttr("style")
          entry.select("a[href=#]").foreach(i => i.attr("href", "javascript:void(0)"))
          entry.select("a[href$=#]").foreach(i => i.attr("href", i.attr("href").replaceAll("(.*?)#*", "$1")))
          entry.select("embed").unwrap()
          entry.select("img").foreach(i => {
            val src = i.attr("src")
            i.parents().find(_.tagName().equalsIgnoreCase("a")) match {
              case Some(a) =>
                a.attr("href") match {
                  case href if src.equals(href) =>
                    a.attr("href", s"javascript:hacg.save('$src');")
                  case href if href.isImg =>
                    a.attr("href", s"javascript:hacg.save('$src');").after( s"""<a href="javascript:hacg.save('$href');"><img data-original="$href" class="lazy" /></a>""")
                  case _ =>
                }
              case _ => i.wrap( s"""<a href="javascript:hacg.save('$src');"></a>""")
            }
            i.attr("data-original", src)
              .addClass("lazy")
              .removeAttr("src")
              .removeAttr("width")
              .removeAttr("height")
          })
          (
            if (content) using(scala.io.Source.fromInputStream(HAcgApplication.instance.getAssets.open("template.html"))) {
              reader => reader.mkString.replace("{{title}}", _article.title).replace("{{body}}", entry.html())
              //                  .replaceAll( """(?<!/|:)\b[a-zA-Z0-9]{40}\b""", """magnet:?xt=urn:btih:$0""")
              //                  .replaceAll( """(?<!['"=])magnet:\?xt=urn:btih:\b[a-zA-Z0-9]{40}\b""", """<a href="$0">$0</a>""")
              //                  .replaceAll( """\b([a-zA-Z0-9]{8})\b(\s)\b([a-zA-Z0-9]{4})\b""", """<a href="http://pan.baidu.com/s/$1">baidu:$1</a>$2$3""")
            } else null,
            if (comment) dom.select("#comments .commentlist>li").map(e => new Comment(e)).toList else null,
            dom.select("#comments #comment-nav-below #comments-nav .next").headOption match {
              case Some(a) => a.attr("abs:href")
              case _ => null
            },
            dom.select("#commentform").select("textarea,input").map(o => (o.attr("name"), o.attr("value"))).toMap,
            dom.select("#commentform").attr("abs:action"),
            if (content) entry.text() else null
            )
      }

      c.ui { _ =>
        result match {
          case Some(data) =>
            if (content) {
              _magnet <= """\b([a-zA-Z0-9]{32}|[a-zA-Z0-9]{40})\b""".r.findAllIn(data._6).toList
              _web <=(data._1, url)
            }
            if (comment) {
              data._2.filter(_.moderation.isNonEmpty).foreach(println)
              _adapter ++= data._2

              _url = data._3
            }
            COMMENT = data._4.find(o => o._1.matches(COMMENTPREFIX)) match {
              case Some(s) => s._1
              case _ => COMMENT
            }
            val filter = List(AUTHOR, EMAIL, COMMENT)
            _post ++= data._4.filter(o => !filter.contains(o._1))

            COMMENTURL = data._5
          case _ => _error <= (_web() == null)
        }
        _progress <= false
        _progress2 <= false
      }
    }
  }

  val datafmt = new SimpleDateFormat("yyyy-MM-dd hh:ss")

  class CommentHolder(view: View) extends RecyclerView.ViewHolder(view) {
    val text1: TextView = view.findViewById(R.id.text1)
    val text2: TextView = view.findViewById(R.id.text2)
    val text3: TextView = view.findViewById(R.id.text3)
    val text4: TextView = view.findViewById(R.id.text4)
    val image: ImageView = view.findViewById(R.id.image1)
    val list: RecyclerView = view.findViewById(R.id.list1)
    val adapter = new CommentAdapter
    val context = view.getContext
    list.setAdapter(adapter)
    list.setLayoutManager(new LinearLayoutManager(context))
    list.setHasFixedSize(true)
    view.setOnClickListener(_click)
  }

  class CommentAdapter extends DataAdapter[Comment, CommentHolder] {

    override def onBindViewHolder(holder: CommentHolder, position: Int): Unit = {
      val item = data(position)
      holder.itemView.setTag(item)
      holder.text1.setText(item.user)
      holder.text2.setText(item.content)
      holder.text3.setText(item.time.map(datafmt.format).orNull)
      holder.text3.setVisibility(if (item.time.isEmpty) View.GONE else View.VISIBLE)
      holder.text4.setText(item.moderation)
      holder.text4.setVisibility(if (item.moderation.isNullOrEmpty) View.GONE else View.VISIBLE)
      holder.adapter.clear ++= item.children

      if (item.face.isEmpty) {
        holder.image.setImageResource(R.mipmap.ic_launcher)
      } else {
        Picasso.`with`(holder.context).load(item.face).placeholder(R.mipmap.ic_launcher).into(holder.image)
      }

    }

    override def onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder =
      new CommentHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.comment_item, parent, false))
  }

}
