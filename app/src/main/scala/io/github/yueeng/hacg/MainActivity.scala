package io.github.yueeng.hacg

import android.app.SearchManager
import android.content._
import android.net.Uri
import android.os.{Bundle, Parcelable}
import android.provider.SearchRecentSuggestions
import android.support.design.widget.TabLayout
import android.support.v4.app._
import android.support.v4.view.ViewPager.SimpleOnPageChangeListener
import android.support.v4.view.{MenuItemCompat, ViewPager}
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.{AlertDialog, AppCompatActivity}
import android.support.v7.widget.RecyclerView.OnScrollListener
import android.support.v7.widget.{RecyclerView, SearchView, StaggeredGridLayoutManager}
import android.text.method.LinkMovementMethod
import android.text.style.{BackgroundColorSpan, ClickableSpan}
import android.text.{SpannableStringBuilder, Spanned, TextPaint}
import android.view.View.OnClickListener
import android.view._
import android.webkit.{WebChromeClient, WebView, WebViewClient}
import android.widget._
import com.squareup.picasso.Picasso
import io.github.yueeng.hacg.Common._

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class MainActivity extends AppCompatActivity {
  lazy val pager: ViewPager = findViewById(R.id.container)

  protected override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(findViewById(R.id.toolbar))
    getSupportActionBar.setLogo(R.mipmap.ic_launcher)

    val tabs: TabLayout = findViewById(R.id.tab)
    val adapter = new ArticleFragmentAdapter(getSupportFragmentManager)
    pager.setAdapter(adapter)
    tabs.setupWithViewPager(pager)

    val button = findViewById(R.id.button1)

    def reset() =
      button.setVisibility(if (pager.getCurrentItem == pager.getAdapter.getCount - 1) View.VISIBLE else View.GONE)

    reset()
    pager.addOnPageChangeListener(new SimpleOnPageChangeListener() {
      override def onPageSelected(position: Int): Unit = reset()
    })

    button.setOnClickListener(
      viewClick {
        v => adapter.current match {
          case web: WebFragment => web.back()
          case _ =>
        }
      }
    )
    checkVersion(false)
  }

  def checkVersion(toast: Boolean = false) = {
    new ScalaTask[Void, Void, Option[(String, String)]] {
      override def background(params: Void*): Option[(String, String)] = {
        s"${HAcg.release}/latest".httpGet.jsoup {
          dom => (
            dom.select(".css-truncate-target").text(),
            dom.select(".release-downloads a[href$=.apk]").headOption match {
              case Some(a) => a.attr("abs:href")
              case _ => null
            }
            )
        } match {
          case Some((v: String, u: String)) if Common.versionBefore(Common.version(MainActivity.this), v) => Option(v, u)
          case _ => None
        }
      }

      override def post(result: Option[(String, String)]): Unit = {
        result match {
          case Some((v: String, u: String)) =>
            new Builder(MainActivity.this)
              .setTitle(R.string.app_update)
              .setMessage(getString(R.string.app_update_new, Common.version(MainActivity.this), v))
              .setPositiveButton(R.string.app_update, dialogClick { (d, w) => openWeb(MainActivity.this, u) })
              .setNeutralButton(R.string.app_publish, dialogClick { (d, w) => openWeb(MainActivity.this, HAcg.release) })
              .setNegativeButton(R.string.app_cancel, null)
              .create().show()
          case _ =>
            if (toast) {
              Toast.makeText(MainActivity.this, getString(R.string.app_update_none, Common.version(MainActivity.this)), Toast.LENGTH_SHORT).show()
            }
        }
      }
    }.execute()

  }

  class ArticleFragmentAdapter(fm: FragmentManager) extends FragmentStatePagerAdapter(fm) {
    lazy val data = List("/", "/anime.html", "/comic.html", "/erogame.html", "/age.html", "/op.html", "/category/rou", HAcg.philosophy)
    lazy val title = getResources.getStringArray(R.array.article_categories)

    override def getItem(position: Int): Fragment =
      (data(position) match {
        case HAcg.`philosophy` => new WebFragment()
        case _ => new ArticleFragment()
      }).arguments(new Bundle().string("url", data(position)))

    override def getCount: Int = data.size

    override def getPageTitle(position: Int): CharSequence = title(position)

    var current: Fragment = _

    override def setPrimaryItem(container: ViewGroup, position: Int, `object`: scala.Any): Unit = {
      super.setPrimaryItem(container, position, `object`)
      current = `object`.asInstanceOf[Fragment]
    }
  }


  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    getMenuInflater.inflate(R.menu.search, menu)
    val search = MenuItemCompat.getActionView(menu.findItem(R.id.search)).asInstanceOf[SearchView]
    val manager = getSystemService(Context.SEARCH_SERVICE).asInstanceOf[SearchManager]
    val info = manager.getSearchableInfo(new ComponentName(this, classOf[ListActivity]))
    search.setSearchableInfo(info)
    super.onCreateOptionsMenu(menu)
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case R.id.search_clear =>
        val suggestions = new SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
        suggestions.clearHistory()
        true
      case R.id.settings => setHost(); true
      case R.id.philosophy => pager.setCurrentItem(pager.getAdapter.getCount - 1); true
      case R.id.about =>
        new Builder(this)
          .setTitle(s"${getString(R.string.app_name)} ${Common.version(this)}")
          .setItems(Array[CharSequence](getString(R.string.app_name)),
            dialogClick { (d, w) => openWeb(MainActivity.this, HAcg.WEB) })
          .setPositiveButton(R.string.app_publish,
            dialogClick { (d, w) => openWeb(MainActivity.this, HAcg.release) })
          .setNeutralButton(R.string.app_update_check,
            dialogClick { (d, w) => checkVersion(true) })
          .setNegativeButton(R.string.app_cancel, null)
          .create().show()
        true
      case _ => super.onOptionsItemSelected(item)
    }
  }

  def setHosts(): Unit = {
    val edit = new EditText(this)
    edit.setHint(R.string.settings_host_sample)
    new Builder(this)
      .setTitle(R.string.settings_host)
      .setView(edit)
      .setNegativeButton(R.string.app_cancel, null)
      .setOnDismissListener(dialogDismiss { d => setHost() })
      .setNeutralButton(R.string.settings_host_reset,
        dialogClick { (d, w) => HAcg.HOSTS = HAcg.DEFAULT_HOSTS })
      .setPositiveButton(R.string.app_ok,
        dialogClick { (d, w) =>
          val host = edit.getText.toString
          if (host.matches( """^[a-zA-Z0-9][a-zA-Z0-9-]{1,61}[a-zA-Z0-9]\.[a-zA-Z]{2,}$""")) {
            HAcg.HOSTS = (HAcg.HOSTS + edit.getText.toString).toSet
          } else {
            Toast.makeText(MainActivity.this, R.string.settings_host_sample, Toast.LENGTH_SHORT).show()
          }
        })
      .create().show()
  }

  def setHost(): Unit = {
    val hosts = HAcg.HOSTS.map(_.asInstanceOf[CharSequence]).toArray
    new Builder(this)
      .setTitle(R.string.settings_host)
      .setSingleChoiceItems(hosts, hosts.indexOf(HAcg.HOST), null)
      .setNegativeButton(R.string.app_cancel, null)
      .setNeutralButton(R.string.settings_host_more, dialogClick { (d, w) => setHosts() })
      .setPositiveButton(R.string.app_ok, dialogClick { (d, w) => HAcg.HOST = hosts(d.asInstanceOf[AlertDialog].getListView.getCheckedItemPosition).toString })
      .create().show()
  }
}

class WebFragment extends Fragment {

  var uri: String = _
  lazy val defuri = getArguments.getString("url")
  lazy val web: WebView = getView.findViewById(R.id.web)
  lazy val progress: ProgressBar = getView.findViewById(R.id.progress)

  override def onCreate(state: Bundle): Unit = {
    super.onCreate(state)
    setRetainInstance(true)
    uri = if (state != null && state.containsKey("url")) state.getString("url") else defuri
  }

  def back() = (web.canGoBack, uri) match {
    case (_, `defuri`) =>
    case (true, _) => web.goBack()
    case _ => web.loadUrl(defuri)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.fragment_web, container, false)
    val web: WebView = root.findViewById(R.id.web)
    web.getSettings.setJavaScriptEnabled(true)
    web.setWebViewClient(new WebViewClient() {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        view.loadUrl(url)
        progress.setProgress(0)
        true
      }

      override def onPageFinished(view: WebView, url: String): Unit = {
        super.onPageFinished(view, url)
        uri = url
        progress.setProgress(100)
      }
    })
    web.setWebChromeClient(new WebChromeClient() {
      override def onProgressChanged(view: WebView, newProgress: Int): Unit = {
        progress.setProgress(newProgress)
      }
    })

    web.loadUrl(uri)
    root
  }

  override def onSaveInstanceState(state: Bundle): Unit = {
    state.putString("url", uri)
  }
}

class ListActivity extends AppCompatActivity {

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_list)
    setSupportActionBar(findViewById(R.id.toolbar))
    getSupportActionBar.setLogo(R.mipmap.ic_launcher)
    getSupportActionBar.setDisplayHomeAsUpEnabled(true)

    val (url, name) = getIntent match {
      case i if i.hasExtra("url") => (i.getStringExtra("url"), i.getStringExtra("name"))
      case i if i.hasExtra(SearchManager.QUERY) =>
        val key = i.getStringExtra(SearchManager.QUERY)
        val suggestions = new SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
        suggestions.saveRecentQuery(key, null)
        ( s"""${HAcg.WORDPRESS}/?s=${Uri.encode(key)}&submit=%E6%90%9C%E7%B4%A2""", key)
      case _ => null
    }
    if (url == null) {
      finish()
      return
    }
    setTitle(name)
    val transaction = getSupportFragmentManager.beginTransaction()

    val fragment = getSupportFragmentManager.findFragmentById(R.id.container) match {
      case fragment: InfoFragment => fragment
      case _ => new ArticleFragment().arguments(new Bundle().string("url", url))
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

class SearchHistoryProvider extends SearchRecentSuggestionsProvider() {
  setupSuggestions(SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
}

object SearchHistoryProvider {
  val AUTHORITY = s"${getClass.getPackage.getName}.SuggestionProvider"
  val MODE: Int = SearchRecentSuggestionsProvider.DATABASE_MODE_QUERIES
}

class ArticleFragment extends Fragment with ViewEx.ViewEx[Boolean, SwipeRefreshLayout] {
  lazy val adapter = new ArticleAdapter()
  var url: String = null
  val error = new ViewEx.Error {
    override def retry(): Unit = query(defurl)
  }

  override def onCreate(saved: Bundle): Unit = {
    super.onCreate(saved)
    setRetainInstance(true)

    if (saved != null) {
      val data = saved.getParcelableArray("data")
      if (data != null && data.nonEmpty) {
        adapter.data ++= data.map(_.asInstanceOf[Article])
        adapter.notifyDataSetChanged()
        return
      }
      error.error = saved.getBoolean("error", false)
    }
    query(defurl)
  }

  def defurl = getArguments.getString("url") match {
    case uri if uri.startsWith("/") => s"${HAcg.WORDPRESS}$uri"
    case uri => uri
  }

  override def onSaveInstanceState(out: Bundle): Unit = {
    out.putParcelableArray("data", adapter.data.toArray)
    out.putBoolean("error", error.error)
  }

  override def refresh(): Unit = view.post(runnable { () => view.setRefreshing(value) })

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.fragment_list, container, false)
    error.image = root.findViewById(R.id.image1)
    view = root.findViewById(R.id.swipe)
    view.setOnRefreshListener(new OnRefreshListener {
      override def onRefresh(): Unit = {
        adapter.data.clear()
        adapter.notifyDataSetChanged()
        query(defurl)
      }
    })
    val recycler: RecyclerView = root.findViewById(R.id.recycler)
    val layout = new StaggeredGridLayoutManager(getResources.getInteger(R.integer.main_list_column), StaggeredGridLayoutManager.VERTICAL)
    recycler.setLayoutManager(layout)
    recycler.setHasFixedSize(true)
    recycler.setAdapter(adapter)
    recycler.addOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(recycler: RecyclerView, state: Int): Unit =
        (state, url, recycler.getLayoutManager) match {
          case (RecyclerView.SCROLL_STATE_IDLE, url: String, staggered: StaggeredGridLayoutManager) if url.isNonEmpty && staggered.findLastVisibleItemPositions(null).max >= adapter.data.size - 1 =>
            query(url)
          case _ =>
        }
    })

    root
  }

  def query(uri: String): Unit = {
    if (value) return
    value = true
    error.error = false
    type R = Option[(List[Article], String)]
    new ScalaTask[String, Void, R]() {
      override def background(params: String*): R = {
        params.head.httpGet.jsoup {
          dom => (dom.select("article").map(o => new Article(o)).toList,
            dom.select("#wp_page_numbers a").lastOption match {
              case Some(n) if ">" == n.text() => n.attr("abs:href")
              case _ => dom.select("#nav-below .nav-previous a").headOption match {
                case Some(p) => p.attr("abs:href")
                case _ => null
              }
            })
        }
      }

      override def post(result: R): Unit = {
        result match {
          case Some(r) =>
            url = r._2
            adapter.data ++= r._1
            adapter.notifyItemRangeInserted(adapter.data.size - r._1.size, r._1.size)
          case _ =>
            error.error = adapter.data.isEmpty
        }
        value = false
      }
    }.execute(uri)
  }

  val click = new OnClickListener {
    override def onClick(v: View): Unit = {
      v.getTag match {
        case h: ArticleHolder =>
          //          val article = v.getTag.asInstanceOf[Article]
          //          startActivity(new Intent(getActivity, classOf[InfoActivity]).putExtra("article", article.asInstanceOf[Parcelable]))
          ActivityCompat.startActivity(getActivity,
            new Intent(getActivity, classOf[InfoActivity]).putExtra("article", h.article.asInstanceOf[Parcelable]),
            ActivityOptionsCompat.makeSceneTransitionAnimation(getActivity, (h.image1, "image")).toBundle)
        case _ =>
      }
    }
  }

  class ArticleHolder(val view: View) extends RecyclerView.ViewHolder(view) {
    view.setOnClickListener(click)
    val context = view.getContext
    val text1: TextView = view.findViewById(R.id.text1)
    val text2: TextView = view.findViewById(R.id.text2)
    val text3: TextView = view.findViewById(R.id.text3)
    val image1: ImageView = view.findViewById(R.id.image1)
    var article: Article = _
    view.setTag(this)
    text3.setMovementMethod(LinkMovementMethod.getInstance())
  }

  class TagClickableSpan(tag: Tag) extends ClickableSpan {
    override def onClick(widget: View): Unit = {
      startActivity(new Intent(getActivity, classOf[ListActivity]).putExtra("url", tag.url).putExtra("name", tag.name))
      //      ActivityCompat.startActivity(getActivity,
      //        new Intent(getActivity, classOf[ListActivity]).putExtra("url", tag.url).putExtra("name", tag.name),
      //        ActivityOptionsCompat.makeSceneTransitionAnimation(getActivity, widget, "tag").toBundle)
    }

    override def updateDrawState(ds: TextPaint): Unit = {
      ds.setColor(0xFFFFFFFF)
      ds.setUnderlineText(false)
    }
  }

  class ArticleAdapter extends RecyclerView.Adapter[ArticleHolder] {
    val data = new ArrayBuffer[Article]()

    override def getItemCount: Int = data.size

    override def onBindViewHolder(holder: ArticleHolder, position: Int): Unit = {
      val item = data(position)
      holder.article = item
      holder.text1.setText(item.title)
      holder.text1.setTextColor(Common.randomColor())
      holder.text2.setText(item.content)
      val w = " "
      val tags = item.expend.map(o => s"$w${o.name}$w").mkString(" ")
      val span = new SpannableStringBuilder(tags)
      for {tag <- item.expend
           pw = tags.indexOf(s"$w${tag.name}$w")
           p = pw + w.length
           e = p + tag.name.length
           ew = e + w.length} {
        span.setSpan(new TagClickableSpan(tag), p, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(new BackgroundColorSpan(Common.randomColor(0xBF)), pw, ew, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      holder.text3.setText(span)
      holder.text3.setVisibility(if (item.tags.nonEmpty) View.VISIBLE else View.GONE)

      Picasso.`with`(holder.context).load(item.img).placeholder(R.drawable.loading).error(R.drawable.placeholder).into(holder.image1)
    }

    override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleHolder =
      new ArticleHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.article_item, parent, false))
  }

}
