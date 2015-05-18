package com.yue.anime.hacg

import android.os.Bundle
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view._
import android.webkit.WebView
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
  lazy val _progress: ProgressBar = findViewById(R.id.progress1)
  lazy val _progress2: ProgressBar = findViewById(R.id.progress2)
  lazy val _adapter = new CommentAdapter
  val _post = new scala.collection.mutable.HashMap[String, String]

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_info)
    setSupportActionBar(findViewById(R.id.toolbar))
    setTitle(_article.title)

    val recycle: RecyclerView = findViewById(R.id.recycler)
    recycle.setLayoutManager(new LinearLayoutManager(this))
    recycle.setAdapter(_adapter)

    recycle.setOnScrollListener(new RecyclerView.OnScrollListener {

      override def onScrollStateChanged(recycler: RecyclerView, state: Int): Unit = {
        super.onScrollStateChanged(recycler, state)
        (recycler.getLayoutManager, state) match {
          case (linear: LinearLayoutManager, RecyclerView.SCROLL_STATE_IDLE) =>
            (_progress2.getTag, linear.findLastVisibleItemPosition()) match {
              case (url: String, pos: Int) if !url.isEmpty && pos >= _adapter.data.size - 2 => query(url)
              case _ =>
            }
          case _ =>
        }
      }
    })
    _progress.busy = false
    _progress2.busy = false
    query(_article.link, content = true)
  }

  val MENU_COMMENT = 0x1001

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    MenuItemCompat.setShowAsAction(menu.add(Menu.NONE, MENU_COMMENT, Menu.NONE, R.string.comment_title), MenuItemCompat.SHOW_AS_ACTION_ALWAYS)
    super.onCreateOptionsMenu(menu)
  }


  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case MENU_COMMENT =>
        val input = LayoutInflater.from(this).inflate(R.layout.comment_post, null)
        val author: EditText = input.findViewById(R.id.edit1)
        val email: EditText = input.findViewById(R.id.edit2)
        val content: EditText = input.findViewById(R.id.edit3)
        author.setText(_post("author"))
        email.setText(_post("email"))
        content.setText(_post("comment"))
        def fill = _post +=("author" -> author.getText.toString, "email" -> email.getText.toString, "comment" -> content.getText.toString)
        val alert = new Builder(this)
          .setTitle(R.string.comment_title)
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
                    Toast.makeText(InfoActivity.this, result, Toast.LENGTH_LONG).show()
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
        true
      case _ =>
        super.onOptionsItemSelected(item)
    }
  }

  class CommentHolder(val view: View) extends RecyclerView.ViewHolder(view) {
    val context = view.getContext
    val text1: TextView = view.findViewById(R.id.text1)
    val text2: TextView = view.findViewById(R.id.text2)
    val image: ImageView = view.findViewById(R.id.image1)
    val recycle: RecyclerView = view.findViewById(R.id.recycler)
    val adapter = new CommentAdapter
    recycle.setLayoutManager(new UnScrolledLinearLayoutManager(context))
    recycle.setAdapter(adapter)
  }

  class CommentAdapter extends RecyclerView.Adapter[CommentHolder] {
    val data = new ArrayBuffer[Comment]()

    override def getItemCount: Int = data.size

    override def onBindViewHolder(holder: CommentHolder, position: Int): Unit = {
      val item = data(position)
      holder.text1.setText(item.user)
      holder.text2.setText(item.content)
      holder.adapter.data.clear()
      holder.adapter.data.append(item.children: _*)
      holder.adapter.notifyDataSetChanged()
      if (item.face.isEmpty) {
        holder.image.setImageResource(R.mipmap.ic_launcher)
      } else {
        Picasso.`with`(holder.context).load(item.face).placeholder(R.mipmap.placeholder).into(holder.image)
      }
    }

    override def onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder =
      new CommentHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.comment_item, parent, false))
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
                .replaceAll( """</?embed.*?>""", ""))
            //          .replaceAll( """(?<!\w+=['"]?)(?:https?://|magnet:\?xt=)[^\s\"\'\<]+""", """<a href="$0">$0</a>""")
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
        if (content) {
          val web: WebView = findViewById(R.id.webview)
          web.loadDataWithBaseURL(url, result._1, "text/html", "utf-8", null)
        }
        _post ++= result._4
        _progress2.setTag(result._2)

        _adapter.data.append(result._3: _*)
        _adapter.notifyItemRangeInserted(_adapter.data.size - result._3.size, result._3.size)
        _progress.busy = false
        _progress2.busy = false
      }
    }.execute()
  }

}
