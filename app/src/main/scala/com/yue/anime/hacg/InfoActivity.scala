package com.yue.anime.hacg

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.{LayoutInflater, View, ViewGroup}
import android.webkit.WebView
import android.widget.{ImageView, ProgressBar, TextView}
import com.squareup.okhttp.{OkHttpClient, Request}
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
  lazy val article = getIntent.getParcelableExtra[Article]("article")
  lazy val progress: ProgressBar = findViewById(R.id.progress1)
  lazy val progress2: ProgressBar = findViewById(R.id.progress2)
  lazy val adapter = new CommentAdapter

  var _busy = false
  var _busy2 = false

  def busy = _busy

  def busy_=(b: Boolean) {
    _busy = b
    progress.setVisibility(if (b) View.VISIBLE else View.INVISIBLE)
    progress.setIndeterminate(b)
  }

  def busy2 = _busy2

  def busy2_=(b: Boolean) {
    _busy2 = b
    progress2.setVisibility(if (b) View.VISIBLE else View.INVISIBLE)
    progress2.setIndeterminate(b)
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_info)
    setSupportActionBar(findViewById(R.id.toolbar))
    setTitle(article.title)

    val recycle: RecyclerView = findViewById(R.id.recycler)
    recycle.setLayoutManager(new LinearLayoutManager(this))
    recycle.setAdapter(adapter)

    recycle.setOnScrollListener(new RecyclerView.OnScrollListener {

      override def onScrollStateChanged(recycler: RecyclerView, state: Int): Unit = {
        super.onScrollStateChanged(recycler, state)
        (recycler.getLayoutManager, state) match {
          case (linear: LinearLayoutManager, RecyclerView.SCROLL_STATE_IDLE) =>
            (progress2.getTag, linear.findLastVisibleItemPosition()) match {
              case (url: String, pos: Int) if !url.isEmpty && pos >= adapter.data.size - 2 => query(url)
              case _ =>
            }
          case _ =>
        }
      }
    })
    busy = false
    busy2 = false
    query(article.link, content = true)
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
    if (busy || busy2) {
      return
    }
    busy = content
    busy2 = true
    new ScalaTask[Void, Void, (String, String, List[Comment])] {
      override def background(params: Void*): (String, String, List[Comment]) = {
        val http = new OkHttpClient()
        val request = new Request.Builder().get().url(url).build()
        val response = http.newCall(request).execute()
        val html = response.body().string()
        val dom = Jsoup.parse(html, url)

        Tuple3(
          if (content) using(io.Source.fromInputStream(getResources.openRawResource(R.raw.template))) {
            reader => reader.mkString.replace("{{title}}", article.title).replace("{{body}}",
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
          dom.select("#comments .commentlist>li").map(e => new Comment(e)).toList
        )
      }

      override def post(result: (String, String, List[Comment])): Unit = {
        if (content) {
          val web: WebView = findViewById(R.id.webview)
          web.loadDataWithBaseURL(url, result._1, "text/html", "utf-8", null)
        }
        progress2.setTag(result._2)

        adapter.data.append(result._3: _*)
        adapter.notifyItemRangeInserted(adapter.data.size - result._3.size, result._3.size)
        busy = false
        busy2 = false
      }
    }.execute()
  }

}
