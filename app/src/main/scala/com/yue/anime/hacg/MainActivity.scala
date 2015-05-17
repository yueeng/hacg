package com.yue.anime.hacg

import android.content.Intent
import android.net.Uri
import android.os.{Bundle, Parcelable}
import android.support.v4.app.{Fragment, FragmentManager, FragmentStatePagerAdapter}
import android.support.v4.view.ViewPager
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.View.OnClickListener
import android.view._
import android.widget.{ImageView, ProgressBar, TextView}
import com.astuetz.PagerSlidingTabStrip
import com.squareup.okhttp.{OkHttpClient, Request}
import com.squareup.picasso.Picasso
import com.yue.anime.hacg.Common.viewTo
import org.jsoup.Jsoup

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class MainActivity extends AppCompatActivity {

  protected override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(findViewById(R.id.toolbar))
    val pager: ViewPager = findViewById(R.id.container)
    val tabs: PagerSlidingTabStrip = findViewById(R.id.tab)
    pager.setAdapter(new ArticleFragmentAdapter(getSupportFragmentManager))
    tabs.setViewPager(pager)
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    getMenuInflater.inflate(R.menu.menu_main, menu)
    true
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    val id: Int = item.getItemId
    if (id == R.id.action_settings) {
      return true
    }
    super.onOptionsItemSelected(item)
  }

  class ArticleFragmentAdapter(fm: FragmentManager) extends FragmentStatePagerAdapter(fm) {
    val data = List("/wordpress", "/wordpress/anime.html", "/wordpress/comic.html", "/wordpress/erogame.html", "/wordpress/age.html", "/wordpress/op.html")
    val title = List("最新投稿", "动画", "漫画", "游戏", "文章", "音乐")

    override def getItem(position: Int): Fragment = {
      val fragment = new ArticleFragment()
      val args = new Bundle()
      args.putString("uri", "http://www.hacg.be" + data(position))
      fragment.setArguments(args)
      fragment
    }

    override def getCount: Int = data.size

    override def getPageTitle(position: Int): CharSequence = title(position)
  }

  class ArticleFragment extends Fragment {
    lazy val adapter = new ArticleAdapter()
    lazy val uri = getArguments.getString("uri")
    lazy val progress: ProgressBar = getView.findViewById(R.id.progress1)

    override def onCreate(savedInstanceState: Bundle): Unit = {
      super.onCreate(savedInstanceState)
      setRetainInstance(true)
    }

    def busy(b: Boolean): Unit = {
      progress.setVisibility(if (b) View.VISIBLE else View.INVISIBLE)
      progress.setIndeterminate(b)
    }

    override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
      val root = inflater.inflate(R.layout.fragment_list, container, false)
      val recycler: RecyclerView = root.findViewById(R.id.recycler)
      val layout = new LinearLayoutManager(container.getContext)
      recycler.setLayoutManager(layout)
      recycler.setAdapter(adapter)
      root
    }

    override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
      super.onViewCreated(view, savedInstanceState)
      if (adapter.data.nonEmpty) return
      busy(b = true)
      new ScalaTask[String, Void, List[Article]]() {
        override def background(params: String*): List[Article] = {
          val uri = params.head
          val http = new OkHttpClient()
          val request = new Request.Builder().get().url(uri).build()
          val response = http.newCall(request).execute()
          val html = response.body().string()
          val dom = Jsoup.parse(html)
          dom.select("article").map(o => new Article(o)).toList
        }

        override def post(result: List[Article]): Unit = {
          adapter.data.append(result: _*)
          adapter.notifyDataSetChanged()
          busy(b = false)
        }
      }.execute(uri)
    }

    val click = new OnClickListener {
      override def onClick(v: View): Unit = {
        val article = v.getTag.asInstanceOf[Article]
        startActivity(new Intent(MainActivity.this, classOf[InfoActivity]).putExtra("article", article.asInstanceOf[Parcelable]))
      }
    }

    class ArticleHolder(val view: View) extends RecyclerView.ViewHolder(view) {
      view.setOnClickListener(click)
      val context = view.getContext
      val text1: TextView = view.findViewById(R.id.text1)
      val image1: ImageView = view.findViewById(R.id.image1)
    }

    class ArticleAdapter extends RecyclerView.Adapter[ArticleHolder] {
      val data = new ArrayBuffer[Article]()

      override def getItemCount: Int = data.size

      override def onBindViewHolder(holder: ArticleHolder, position: Int): Unit = {
        val item = data(position)
        holder.view.setTag(item)
        holder.text1.setText(item.content)
        if (item.img())
          Picasso.`with`(holder.context).load(Uri.parse(item.image)).placeholder(R.mipmap.placeholder).into(holder.image1)
        else
          Picasso.`with`(holder.context).load(R.mipmap.placeholder).into(holder.image1)
      }

      override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleHolder =
        new ArticleHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.article_item, parent, false))
    }

  }

}