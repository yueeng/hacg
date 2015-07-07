package io.github.yueeng.hacg

import java.text.SimpleDateFormat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.{Fragment, NavUtils, TaskStackBuilder}
import android.support.v4.view.GravityCompat
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener
import android.support.v4.widget.{DrawerLayout, SwipeRefreshLayout}
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.RecyclerView.OnScrollListener
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view._
import android.webkit.{WebView, WebViewClient}
import android.widget._
import com.github.clans.fab.{FloatingActionButton, FloatingActionMenu}
import com.squareup.picasso.Picasso
import io.github.yueeng.hacg.Common._
import io.github.yueeng.hacg.ViewEx.ViewEx

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
 * Info activity
 * Created by Rain on 2015/5/12.
 */

class InfoActivity extends AppCompatActivity {
  lazy val _article = getIntent.getParcelableExtra[Article]("article")

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_fragment)
    setSupportActionBar(findViewById(R.id.toolbar))
    getSupportActionBar.setLogo(R.mipmap.ic_launcher)
    getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    setTitle(_article.title)
    //    ViewCompat.setTransitionName(findViewById(R.id.toolbar), "article")
    val transaction = getSupportFragmentManager.beginTransaction()

    val fragment = getSupportFragmentManager.findFragmentById(R.id.container) match {
      case fragment: InfoFragment => fragment
      case _ =>
        val fragment = new InfoFragment
        val extra = new Bundle()
        extra.putParcelable("article", _article)
        fragment.setArguments(extra)
        fragment
    }

    transaction.replace(R.id.container, fragment)

    transaction.commit()
  }

  override def onBackPressed(): Unit = {
    getSupportFragmentManager.findFragmentById(R.id.container) match {
      case fragment: InfoFragment if fragment.onBackPressed =>
      case _ => super.onBackPressed()
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case R.id.home =>
        val upIntent = NavUtils.getParentActivityIntent(this)
        if (NavUtils.shouldUpRecreateTask(this, upIntent)) {
          TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(upIntent)
            .startActivities()
        } else {
          NavUtils.navigateUpTo(this, upIntent)
        }
        true
      case _ => super.onOptionsItemSelected(item)
    }

  }
}

class InfoFragment extends Fragment {
  lazy val _article = getArguments.getParcelable[Article]("article")
  lazy val _adapter = new CommentAdapter
  val _web = new ViewEx.ViewEx[(String, String), WebView] {
    override def refresh(): Unit =
      view.loadDataWithBaseURL(value._2, value._1, "text/html", "utf-8", null)
  }
  val _error = new ViewEx.Error {
    override def retry(): Unit = query(_article.link, QUERY_WEB)
  }
  val _post = new scala.collection.mutable.HashMap[String, String]
  var _url: String = null

  val _click = viewClick {
    v => v.getTag match {
      case c: Comment => comment(c)
      case _ =>
    }
  }
  val AUTHOR = "author"
  val EMAIL = "email"
  val CONFIG_AUTHOR = "config.author"
  val CONFIG_EMAIL = "config.email"
  val COMMENT = "comment"

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
    setRetainInstance(true)
    val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
    _post +=(AUTHOR -> preference.getString(CONFIG_AUTHOR, ""), EMAIL -> preference.getString(CONFIG_EMAIL, ""))
    query(_article.link, QUERY_ALL)
  }

  lazy val _progress = new ViewEx[Boolean, SwipeRefreshLayout] {
    override def refresh(): Unit = view.post(runnable { () => view.setRefreshing(value) })
  }
  lazy val _progress2 = new ViewEx[Boolean, SwipeRefreshLayout] {
    override def refresh(): Unit = view.post(runnable { () => view.setRefreshing(value) })
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.activity_info, container, false)
    _error.image = root.findViewById(R.id.image1)
    val list: RecyclerView = root.findViewById(R.id.list1)
    list.setLayoutManager(new FullyLinearLayoutManager(getActivity))
    list.setAdapter(_adapter)

    list.addOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(recyclerView: RecyclerView, state: Int): Unit = {
        (state, _url, list.getLayoutManager) match {
          case (RecyclerView.SCROLL_STATE_IDLE, url, staggered: LinearLayoutManager)
            if url != null && !url.isEmpty && staggered.findLastVisibleItemPosition() >= _adapter.data.size - 1 =>
            query(url, QUERY_COMMENT)
          case _ =>
        }
      }
    })

    List(R.id.button1, R.id.button2, R.id.button3, R.id.menu1).map(root.findViewById).foreach {
      case m: FloatingActionMenu =>
        m.setMenuButtonColorNormal(randomColor())
        m.setMenuButtonColorPressed(randomColor())
        m.setMenuButtonColorRipple(randomColor())
      case b: FloatingActionButton =>
        b.setColorNormal(randomColor())
        b.setColorPressed(randomColor())
        b.setColorRipple(randomColor())
        b.setOnClickListener(click)
    }

    _progress.view = root.findViewById(R.id.swipe)
    _progress2.view = root.findViewById(R.id.swipe2)

    _progress.view.setOnRefreshListener(new OnRefreshListener {
      override def onRefresh(): Unit = query(_article.link, QUERY_WEB)
    })
    _progress2.view.setOnRefreshListener(new OnRefreshListener {
      override def onRefresh(): Unit = {
        _url = null
        _adapter.data.clear()
        _adapter.notifyDataSetChanged()
        query(_article.link, QUERY_COMMENT)
      }
    })

    val web: WebView = root.findViewById(R.id.webview)
    web.getSettings.setJavaScriptEnabled(true)
    web.setWebViewClient(new WebViewClient {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        val uri = Uri.parse(url)
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri.getScheme))
        true
      }
    })
    _web.view = web
    root
  }

  lazy val click = viewClick { v =>
    v.getId match {
      case R.id.button1 => startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(_article.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      case R.id.button2 => getView.findViewById(R.id.drawer).asInstanceOf[DrawerLayout].openDrawer(GravityCompat.END)
      case R.id.button3 => comment(null)
    }
    getView.findViewById(R.id.menu1).asInstanceOf[FloatingActionMenu].close(true)
  }

  def onBackPressed: Boolean = {
    getView.findViewById(R.id.drawer) match {
      case menu: DrawerLayout if menu.isDrawerOpen(GravityCompat.END) =>
        menu.closeDrawer(GravityCompat.END)
        true
      case _ => false
    }
  }

  def comment(c: Comment) = {
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
      preference.edit().putString(CONFIG_AUTHOR, _post(AUTHOR)).putString(CONFIG_EMAIL, _post(EMAIL)).commit()
    }
    val alert = new Builder(getActivity)
      .setTitle(if (c != null) getString(R.string.comment_review, c.user) else getString(R.string.comment_title))
      .setView(input)
      .setPositiveButton(R.string.comment_submit,
        dialogClick { (d, w) =>
          fill
          if (List(AUTHOR, EMAIL, COMMENT).map(_post.getOrElse(_, null)).exists(_.isNullOrEmpty)) {
            Toast.makeText(getActivity, getString(R.string.comment_verify), Toast.LENGTH_SHORT).show()
          } else {
            _progress2.value = true
            type R = (Boolean, String)
            new ScalaTask[Map[String, String], Void, R] {
              override def background(params: Map[String, String]*): R = {
                val data = params.head
                s"${HAcg.WORDPRESS}/wp-comments-post.php".httpPost(data).jsoup match {
                  case Some(dom) =>
                    dom.select("#error-page").headOption match {
                      case Some(e) => (false, e.text())
                      case _ => (true, getString(R.string.comment_succeeded))
                    }
                  case _ => (false, getString(R.string.comment_failed))
                }
              }

              override def post(result: R): Unit = {
                _progress2.value = false
                if (result._1) {
                  _post(COMMENT) = ""
                }
                Toast.makeText(getActivity, result._2, Toast.LENGTH_LONG).show()
              }
            }.execute(_post.toMap)
          }
        })
      .setNegativeButton(R.string.app_cancel, null)
      .setOnDismissListener(
        dialogDismiss {
          d => fill
        }
      )
      .create()
    alert.show()
  }

  val QUERY_WEB = 1
  val QUERY_COMMENT = QUERY_WEB << 1
  val QUERY_ALL = QUERY_WEB | QUERY_COMMENT

  def query(url: String, op: Int): Unit = {
    if (_progress.value || _progress2.value) {
      return
    }
    _error.error = false
    val content = (op & QUERY_WEB) == QUERY_WEB
    val comment = (op & QUERY_COMMENT) == QUERY_COMMENT
    _progress.value = content
    _progress2.value = true
    type R = Option[(String, List[Comment], String, Map[String, String])]
    new ScalaTask[Void, Void, R] {
      override def background(params: Void*): R = {
        url.httpGet.jsoup {
          dom =>
            val entry = dom.select(".entry-content")
            entry.select("*[style*=display]").filter(i => i.attr("style").matches("display: ?none;?")).foreach(_.remove())
            entry.select("script,.wp-polls-loading").remove()
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
            entry.select("a[href=#]").remove()
            entry.select("a[href$=#]").foreach(i => i.attr("href", i.attr("href").replaceAll("(.*?)#*", "$1")))
            entry.select("embed").unwrap()
            entry.select("img").foreach(i => {
              val src = i.attr("src")
              i.parents().find(_.tagName().equalsIgnoreCase("a")) match {
                case Some(a) =>
                  a.attr("href") match {
                    case href if src.equals(href) =>
                    case href if href.isImg =>
                      a.attr("href", src).after( s"""<a href="$href"><img data-original="$href" class="lazy" /></a>""")
                    case _ =>
                  }
                case _ => i.wrap( s"""<a href="$src"></a>""")
              }
              i.attr("data-original", src)
                .addClass("lazy")
                .removeAttr("src")
                .removeAttr("width")
                .removeAttr("height")
            })
            (
              if (content) using(scala.io.Source.fromInputStream(HAcgApplication.context.getAssets.open("template.html"))) {
                reader => reader.mkString.replace("{{title}}", _article.title).replace("{{body}}", entry.html())
              } else null,
              if (comment) dom.select("#comments .commentlist>li").map(e => new Comment(e)).toList else null,
              dom.select("#comments #comment-nav-below #comments-nav .next").headOption match {
                case Some(a) => a.attr("abs:href")
                case _ => null
              },
              dom.select("#commentform").select("textarea,input").map(o => (o.attr("name"), o.attr("value"))).toMap
              )
        }
      }

      override def post(result: R): Unit = {
        result match {
          case Some(data) =>
            if (content) {
              _web.value = (data._1, url)
            }
            if (comment) {
              _adapter.data ++= data._2
              _adapter.notifyDataSetChanged()

              _url = data._3
            }
            val filter = List(AUTHOR, EMAIL, COMMENT)
            _post ++= data._4.filter(o => !filter.contains(o._1))
          case _ => _error.error = _web.value == null
        }
        _progress.value = false
        _progress2.value = false
      }
    }.execute()
  }

  val datafmt = new SimpleDateFormat("yyyy-MM-dd hh:ss")

  class CommentHolder(view: View) extends RecyclerView.ViewHolder(view) {
    val text1: TextView = view.findViewById(R.id.text1)
    val text2: TextView = view.findViewById(R.id.text2)
    val text3: TextView = view.findViewById(R.id.text3)
    val image: ImageView = view.findViewById(R.id.image1)
    val list: RecyclerView = view.findViewById(R.id.list1)
    val adapter = new CommentAdapter
    val context = view.getContext
    list.setAdapter(adapter)
    list.setLayoutManager(new FullyLinearLayoutManager(context))
    view.setOnClickListener(_click)
  }

  class CommentAdapter extends RecyclerView.Adapter[CommentHolder] {
    val data = new ArrayBuffer[Comment]()

    override def getItemCount: Int = data.size

    override def onBindViewHolder(holder: CommentHolder, position: Int): Unit = {
      val item = data(position)
      holder.itemView.setTag(item)
      holder.text1.setText(item.user)
      holder.text2.setText(item.content)
      holder.text3.setText(item.time.map(datafmt.format).orNull)
      holder.text3.setVisibility(if (item.time.nonEmpty) View.VISIBLE else View.GONE)
      holder.adapter.data.clear()
      holder.adapter.data ++= item.children
      holder.adapter.notifyDataSetChanged()

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
