package com.yue.anime.hacg

import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.Fragment
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.AppCompatActivity
import android.view._
import android.webkit.WebView
import android.widget.AdapterView.OnItemClickListener
import android.widget._
import com.squareup.okhttp.{FormEncodingBuilder, OkHttpClient, Request, RequestBody}
import com.squareup.picasso.Picasso
import com.yue.anime.hacg.Common._
import org.jsoup.Jsoup

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
}

class InfoFragment extends Fragment {
  lazy val _article = getArguments.getParcelable[Article]("article")
  lazy val _adapter = new CommentAdapter
  val _progress: Busy = new Busy {}
  val _progress2: Busy = new Busy {}
  val _post = new scala.collection.mutable.HashMap[String, String]
  var commentUrl: String = null
  var html: String = null
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

    _progress.progress = root.findViewById(R.id.progress1)
    _progress2.progress = root.findViewById(R.id.progress2)

    if (html.isNonEmpty) {
      val web: WebView = root.findViewById(R.id.webview)
      web.loadDataWithBaseURL(_article.link, html, "text/html", "utf-8", null)
    }
    root
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
    setRetainInstance(true)
    val preference = PreferenceManager.getDefaultSharedPreferences(getActivity)
    _post +=("author" -> preference.getString("author", ""), "email" -> preference.getString("email", ""))
    query(_article.link, content = true)
  }

  val MENU_COMMENT = 0x1001

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater): Unit = {
    MenuItemCompat.setShowAsAction(menu.add(Menu.NONE, MENU_COMMENT, Menu.NONE, R.string.comment_title), MenuItemCompat.SHOW_AS_ACTION_ALWAYS)
    super.onCreateOptionsMenu(menu, inflater)
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case MENU_COMMENT =>
        comment(0)
        true
      case _ =>
        super.onOptionsItemSelected(item)
    }
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
            new ScalaTask[RequestBody, Void, String] {
              override def background(params: RequestBody*): String = {
                val data = params.head
                val http = new OkHttpClient()
                val post = new Request.Builder().url("http://www.hacg.be/wordpress/wp-comments-post.php").post(data).build()
                val response = http.newCall(post).execute()
                val html = response.body().string()
                val dom = Jsoup.parse(html, response.request().urlString())
                dom.select("#error-page").headOption match {
                  case Some(e) => e.text()
                  case _ => getString(R.string.comment_succeeded)
                }
              }

              override def post(result: String): Unit = {
                _progress2.busy = false
                Toast.makeText(getActivity, result, Toast.LENGTH_LONG).show()
              }
            }.execute((new FormEncodingBuilder /: _post)((b, o) => b.add(o._1, o._2)).build())
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
        val http = new OkHttpClient()
        val request = new Request.Builder().get().url(url).build()
        val response = http.newCall(request).execute()
        val html = response.body().string()
        val dom = Jsoup.parse(html, url)

        Tuple4(
          if (content) using(io.Source.fromInputStream(getResources.openRawResource(R.raw.template))) {
            reader => reader.mkString.replace("{{title}}", _article.title).replace("{{body}}",
              dom.select(".entry-content").html().replaceAll( """\s{0,1}style=".*?"""", "")
                .replaceAll( """\s{0,1}class=".*?"""", "")
                .replaceAll( """<a href="#">.*?</a>""", "")
                .replaceAll( """</?embed.*?>""", "")
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
        if (html.isNullOrEmpty) {
          html = result._1
          if (getView != null) {
            val web: WebView = getView.findViewById(R.id.webview)
            web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
          }
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
        Picasso.`with`(parent.getContext).load(item.face).placeholder(R.mipmap.placeholder).into(image)
      }

      view
    }
  }

}
