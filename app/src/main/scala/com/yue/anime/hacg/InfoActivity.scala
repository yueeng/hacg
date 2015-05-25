package com.yue.anime.hacg

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.{Fragment, NavUtils, TaskStackBuilder}
import android.support.v4.view.GravityCompat
import android.support.v4.widget.DrawerLayout
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view._
import android.webkit.{ConsoleMessage, WebChromeClient, WebView}
import android.widget.AdapterView.OnItemClickListener
import android.widget._
import com.github.clans.fab.FloatingActionMenu
import com.squareup.picasso.Picasso
import com.yue.anime.hacg.Common._

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
    getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    setTitle(_article.title)

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
  val _progress: Busy = new Busy {}
  val _progress2: Busy = new Busy {}
  val _web = new ViewEx[(String, String), WebView] {
    override def refresh(): Unit = {
      view.loadDataWithBaseURL(value._2, value._1, "text/html", "utf-8", null)
    }
  }
  val _post = new scala.collection.mutable.HashMap[String, String]
  var commentUrl: String = null
  //  var html: String = null
  val commentClick = new OnItemClickListener {
    override def onItemClick(parent: AdapterView[_], view: View, position: Int, id: Long): Unit = {
      parent.getAdapter match {
        case adapter: CommentAdapter =>
          val item = adapter.getItem(position)
          comment(item.id, getString(R.string.comment_review, item.user))
        case _ =>
      }
    }
  }


  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
    setRetainInstance(true)
    val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
    _post +=("author" -> preference.getString("author", ""), "email" -> preference.getString("email", ""))
    query(_article.link, content = true)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.activity_info, container, false)
    val list: ListView = root.findViewById(R.id.list1)
    list.setAdapter(_adapter)
    list.setOnItemClickListener(commentClick)
    list.setOnScrollListener(new AbsListView.OnScrollListener {
      override def onScrollStateChanged(view: AbsListView, scrollState: Int): Unit = {}

      override def onScroll(view: AbsListView, first: Int, visible: Int, total: Int): Unit = {
        (first + visible >= total, commentUrl) match {
          case (true, url) if url != null && !url.isEmpty => query(url)
          case _ =>
        }
      }
    })

    List(R.id.button1, R.id.button2, R.id.button3).map(root.findViewById).foreach(_.setOnClickListener(click))

    _progress.progress = root.findViewById(R.id.progress1)
    _progress2.progress = root.findViewById(R.id.progress2)

    val web: WebView = root.findViewById(R.id.webview)
    web.getSettings.setJavaScriptEnabled(true)
    web.setWebChromeClient(new WebChromeClient {
      override def onConsoleMessage(cm: ConsoleMessage): Boolean = {
        Log.d("HAcg", cm.message() + " -- From line "
          + cm.lineNumber() + " of "
          + cm.sourceId())
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
      case R.id.button3 => comment(0)
    }
    getView.findViewById(R.id.menu1).asInstanceOf[FloatingActionMenu].close(true)
  }

  def comment(id: Int, title: String = null) = {
    val input = LayoutInflater.from(getActivity).inflate(R.layout.comment_post, null)
    val author: EditText = input.findViewById(R.id.edit1)
    val email: EditText = input.findViewById(R.id.edit2)
    val content: EditText = input.findViewById(R.id.edit3)
    author.setText(_post("author"))
    email.setText(_post("email"))
    content.setText(_post("comment"))
    _post += ("comment_parent" -> id.toString)
    def fill = {
      _post +=("author" -> author.getText.toString, "email" -> email.getText.toString, "comment" -> content.getText.toString)
      val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
      preference.edit().putString("author", _post("author")).putString("email", _post("email")).commit()
    }
    val alert = new Builder(getActivity)
      .setTitle(if (title != null) title else getString(R.string.comment_title))
      .setView(input)
      .setPositiveButton(R.string.comment_submit,
        dialogClick {
          (d, w) =>
            fill
            _progress2.busy = true
            new ScalaTask[Map[String, String], Void, String] {
              override def background(params: Map[String, String]*): String = {
                val data = params.head
                val dom = "http://www.hacg.be/wordpress/wp-comments-post.php".httpPost(data).jsoup
                dom.select("#error-page").headOption match {
                  case Some(e) => e.text()
                  case _ => getString(R.string.comment_succeeded)
                }
              }

              override def post(result: String): Unit = {
                _progress2.busy = false
                Toast.makeText(getActivity, result, Toast.LENGTH_LONG).show()
              }
            }.execute(_post.toMap)
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


  def query(url: String, content: Boolean = false): Unit = {
    if (_progress.busy || _progress2.busy) {
      return
    }
    _progress.busy = content
    _progress2.busy = true
    new ScalaTask[Void, Void, (String, String, List[Comment], Map[String, String])] {
      override def background(params: Void*): (String, String, List[Comment], Map[String, String]) = {
        val dom = url.httpGet.jsoup
        val entry = dom.select(".entry-content")
        entry.select("script,#polls-15-loading").remove()
        entry.select("#polls-15").headOption match {
          case Some(div) =>
            val node = if (div.parent.attr("class") != "entry-content") div.parent else div
            val name = div.select("strong").headOption match {
              case Some(strong) => strong.text()
              case _ => "投票推荐"
            }
            node.after( s"""<a href="$url">$name</a>""")
            node.remove()
          case _ =>
        }
        entry.select("*").removeAttr("class").removeAttr("style")
        entry.select("a[href=#]").remove()
        entry.select("embed").unwrap()
        entry.select("img").foreach(i => {
          i.attr("data-original", i.attr("src"))
            .addClass("lazy")
            .removeAttr("src")
            .removeAttr("width")
            .removeAttr("height")
        })
        (
          if (content) using(io.Source.fromInputStream(getActivity.getAssets.open("template.html"))) {
            reader => reader.mkString.replace("{{title}}", _article.title).replace("{{body}}",
              entry.html()
                .replaceAll( """(?<!magnet:\?xt=urn:btih:)\b[a-zA-Z0-9]{40}\b""", """magnet:?xt=urn:btih:$0""")
                .replaceAll( """(?<!\w{0,8}=['"]?)(?:magnet:\?xt=urn:btih:)[^\s\"\'\<]+""", """<a href="$0">$0</a>"""))
          } else null,
          dom.select("#comments #comment-nav-below #comments-nav .next").headOption match {
            case Some(a) => a.attr("abs:href")
            case _ => null
          },
          dom.select("#comments .commentlist>li").map(e => new Comment(e)).toList,
          dom.select("#commentform").select("textarea,input").map(o => (o.attr("name"), o.attr("value"))).toMap
          )
      }

      override def post(result: (String, String, List[Comment], Map[String, String])): Unit = {
        if (_web.value == null) {
          _web.value = (result._1, url)
        }
        _post ++= result._4
        commentUrl = result._2

        _adapter.data ++= result._3
        _adapter.notifyDataSetChanged()
        _progress.busy = false
        _progress2.busy = false
      }
    }.execute()
  }

  class CommentAdapter extends BaseAdapter {
    val data = new ArrayBuffer[Comment]()

    override def getItemId(position: Int): Long = position

    override def getCount: Int = data.size

    override def getItem(position: Int): Comment = data(position)

    override def getView(position: Int, convert: View, parent: ViewGroup): View = {

      val view = convert match {
        case null => LayoutInflater.from(parent.getContext).inflate(R.layout.comment_item, parent, false)
        case _ => convert
      }

      val text1: TextView = view.findViewById(R.id.text1)
      val text2: TextView = view.findViewById(R.id.text2)
      val image: ImageView = view.findViewById(R.id.image1)
      val list: ListView = view.findViewById(R.id.list1)

      val adapter = list.getAdapter match {
        case adapter: CommentAdapter => adapter
        case _ =>
          list.setOnItemClickListener(commentClick)
          val adapter = new CommentAdapter
          list.setAdapter(adapter)
          adapter
      }


      val item = getItem(position)
      text1.setText(item.user)
      text2.setText(item.content)

      adapter.data.clear()
      adapter.data ++= item.children
      adapter.notifyDataSetChanged()

      if (item.face.isEmpty) {
        image.setImageResource(R.mipmap.ic_launcher)
      } else {
        Picasso.`with`(parent.getContext).load(item.face).placeholder(R.drawable.loading).into(image)
      }

      view
    }
  }

}
