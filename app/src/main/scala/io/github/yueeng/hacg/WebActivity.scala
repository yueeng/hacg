package io.github.yueeng.hacg

import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener
import android.support.v7.app.AppCompatActivity
import android.view._
import android.webkit.{WebChromeClient, WebView, WebViewClient}
import android.widget.ProgressBar
import io.github.yueeng.hacg.Common._
import io.github.yueeng.hacg.ViewBinder.ViewBinder

class WebActivity extends AppCompatActivity {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_web)

    setSupportActionBar(findViewById(R.id.toolbar))
    getSupportActionBar.setLogo(R.mipmap.ic_launcher)
    getSupportActionBar.setDisplayHomeAsUpEnabled(true)

    val transaction = getSupportFragmentManager.beginTransaction()

    val fragment = getSupportFragmentManager.findFragmentById(R.id.container) match {
      case fragment: WebFragment => fragment
      case _ => new WebFragment().arguments(getIntent.getExtras)
    }

    transaction.replace(R.id.container, fragment)

    transaction.commit()
  }

  override def onBackPressed(): Unit = {
    getSupportFragmentManager.findFragmentById(R.id.container) match {
      case f: WebFragment if f.web.canGoBack => f.web.goBack()
      case _ => super.onBackPressed()
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home => super.onBackPressed(); true
      case _ => super.onOptionsItemSelected(item)
    }
  }
}

class WebFragment extends Fragment {
  val busy = new ViewBinder[Boolean, SwipeRefreshLayout](false)((view, value) => view.post(() => view.setRefreshing(value)))
  var uri: String = _

  def defuri: String = getArguments match {
    case b: Bundle if b.containsKey("url") => b.getString("url")
    case _ => HAcg.philosophy
  }

  lazy val web: WebView = getView.findViewById(R.id.web)
  lazy val progress: ProgressBar = getView.findViewById(R.id.progress)

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater): Unit = {
    inflater.inflate(R.menu.menu_web, menu)
    super.onCreateOptionsMenu(menu, inflater)
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case R.id.open => Common.openWeb(getActivity, uri); true
      case _ => super.onOptionsItemSelected(item)
    }
  }

  override def onCreate(state: Bundle): Unit = {
    super.onCreate(state)
    setHasOptionsMenu(true)
    setRetainInstance(true)
    uri = if (state != null && state.containsKey("url")) state.getString("url") else defuri
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val root = inflater.inflate(R.layout.fragment_web, container, false)
    val web: WebView = root.findViewById(R.id.web)

    busy += root.findViewById(R.id.swipe)
    busy.views.head.setOnRefreshListener(new OnRefreshListener {
      override def onRefresh(): Unit = web.loadUrl(uri)
    })

    val settings = web.getSettings
    settings.setJavaScriptEnabled(true)
    settings.setUseWideViewPort(true)
    settings.setLoadWithOverviewMode(true)
    val back = root.findViewById[View](R.id.button2)
    val fore = root.findViewById[View](R.id.button3)
    web.setWebViewClient(new WebViewClient() {
      override def shouldOverrideUrlLoading(view: WebView, url: String): Boolean = {
        view.loadUrl(url)
        true
      }

      override def onPageStarted(view: WebView, url: String, favicon: Bitmap): Unit = {
        busy <= true
        progress.setProgress(0)
      }

      override def onPageFinished(view: WebView, url: String): Unit = {
        busy <= false
        progress.setProgress(100)
        uri = url

        back.setEnabled(view.canGoBack)
        fore.setEnabled(view.canGoForward)
      }
    })
    web.setWebChromeClient(new WebChromeClient() {
      override def onProgressChanged(view: WebView, newProgress: Int): Unit = {
        progress.setProgress(newProgress)
      }
    })

    val click = viewClick {
      _.getId match {
        case R.id.button1 => web.loadUrl(defuri)
        case R.id.button2 => if (web.canGoBack) web.goBack()
        case R.id.button3 => if (web.canGoForward) web.goForward()
        case R.id.button4 => web.loadUrl(uri)
      }
    }
    List(R.id.button1, R.id.button2, R.id.button3, R.id.button4)
      .map(root.findViewById[View]).foreach(_.setOnClickListener(click))

    web.loadUrl(uri)
    root
  }

  override def onSaveInstanceState(state: Bundle): Unit = {
    state.putString("url", uri)
  }
}
