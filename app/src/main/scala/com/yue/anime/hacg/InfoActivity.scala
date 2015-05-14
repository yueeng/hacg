package com.yue.anime.hacg

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.RecyclerView.OnScrollListener
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.{LayoutInflater, ViewGroup, View}
import android.webkit.WebView
import android.widget.{ImageView, TextView, ProgressBar}
import com.mugen.{MugenCallbacks, Mugen}
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
  lazy val web: WebView = findViewById(R.id.webview)
  lazy val progress: ProgressBar = findViewById(R.id.progress1)
  lazy val recycle: RecyclerView = findViewById(R.id.recycler)
  lazy val adapter = new CommentAdapter

  def busy(b: Boolean): Unit = {
    progress.setVisibility(if (b) View.VISIBLE else View.INVISIBLE)
    progress.setIndeterminate(b)
  }

  class CommentHolder(val view: View) extends RecyclerView.ViewHolder(view) {
    val context = view.getContext
    val text1: TextView = view.findViewById(R.id.text1)
    val text2: TextView = view.findViewById(R.id.text2)
    val image: ImageView = view.findViewById(R.id.image1)
  }

  class CommentAdapter extends RecyclerView.Adapter[CommentHolder] {
    val data = new ArrayBuffer[Comment]()

    override def getItemCount: Int = data.size

    override def onBindViewHolder(holder: CommentHolder, position: Int): Unit = {
      val item = data(position)
      holder.text1.setText(item.user)
      holder.text2.setText(item.content)
      if (item.face.isEmpty) {
        holder.image.setImageResource(R.mipmap.ic_launcher)
      } else {
        Picasso.`with`(holder.context).load(item.face).placeholder(R.mipmap.ic_launcher).into(holder.image)
      }
    }

    override def onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder =
      new CommentHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.comment_item, parent, false))
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_info)
    setSupportActionBar(findViewById(R.id.toolbar))
    setTitle(article.title)

    recycle.setLayoutManager(new LinearLayoutManager(this))
    recycle.setAdapter(adapter)

    //    recycle.setOnScrollListener(new RecyclerView.OnScrollListener {
    //      override def onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int): Unit =
    //        super.onScrolled(recyclerView, dx, dy)
    //
    //      override def onScrollStateChanged(recyclerView: RecyclerView, newState: Int): Unit =
    //        super.onScrollStateChanged(recyclerView, newState)
    //    })

    busy(b = true)
    new ScalaTask[Void, Void, (String, List[Comment])] {
      override def background(params: Void*): (String, List[Comment]) = {
        val http = new OkHttpClient()
        val request = new Request.Builder().get().url(article.link).build()
        val response = http.newCall(request).execute()
        val html = response.body().string()
        val dom = Jsoup.parse(html, article.link)
        val content = dom.select(".entry-content")
        val body = content.html().replaceAll( """\s{0,1}style=".*?"""", "")
          .replaceAll( """\s{0,1}class=".*?"""", "")
          .replaceAll( """<a href="#">.*?</a>""", "")
          .replaceAll( """</?embed.*?>""", "")
        //          .replaceAll( """(?<!\w+=['"]?)(?:https?://|magnet:\?xt=)[^\s\"\'\<]+""", """<a href="$0">$0</a>""")
        Tuple2(
          using(io.Source.fromInputStream(getResources.openRawResource(R.raw.template))) {
            reader => reader.mkString.replace("{{title}}", article.title).replace("{{body}}", body)
          },
          dom.select("#comments .commentlist>li").map(e => new Comment(e)).toList
        )
      }

      override def post(result: (String, List[Comment])): Unit = {
        web.loadDataWithBaseURL(article.link, result._1, "text/html", "utf-8", null)
        adapter.data.append(result._2: _*)
        adapter.notifyDataSetChanged()
        busy(b = false)
      }
    }.execute()
  }
}
