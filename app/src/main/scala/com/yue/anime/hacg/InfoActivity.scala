package com.yue.anime.hacg

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import com.squareup.okhttp.{OkHttpClient, Request}
import com.yue.anime.hacg.Common._
import org.jsoup.Jsoup

/**
 * Info activity
 * Created by Rain on 2015/5/12.
 */
class InfoActivity extends AppCompatActivity {
  lazy val article = getIntent.getParcelableExtra[Article]("article")
  lazy val web: WebView = findViewById(R.id.webview)
  lazy val progress: ProgressBar = findViewById(R.id.progress1)

  def busy(b: Boolean): Unit = {
    progress.setVisibility(if (b) View.VISIBLE else View.INVISIBLE)
    progress.setIndeterminate(b)
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_info)
    setSupportActionBar(findViewById(R.id.toolbar))
    setTitle(article.title)
    busy(b = true)
    new ScalaTask[Void, Void, String] {
      override def background(params: Void*): String = {
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
        using(io.Source.fromInputStream(getResources.openRawResource(R.raw.template))) {
          reader => reader.mkString.replace("{{title}}", article.title).replace("{{body}}", body)
        }
      }

      override def post(html: String): Unit = {
        web.loadDataWithBaseURL(article.link, html, "text/html", "UTF-8", null)
        busy(b = false)
      }
    }.execute()
  }
}
