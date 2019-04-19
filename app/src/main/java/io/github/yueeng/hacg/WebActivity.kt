package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class WebActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setLogo(R.mipmap.ic_launcher)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val transaction = supportFragmentManager.beginTransaction()

        val fragment = supportFragmentManager.findFragmentById(R.id.container)?.takeIf { it is WebFragment }
                ?: WebFragment().arguments(intent.extras)

        transaction.replace(R.id.container, fragment)

        transaction.commit()
    }

    override fun onBackPressed() {
        supportFragmentManager.findFragmentById(R.id.container)?.let { it as?WebFragment }?.takeIf { it.web.canGoBack() }?.let { it.web.goBack() }
                ?: super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            super.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class WebFragment : Fragment() {
    private val busy = ViewBinder<Boolean, SwipeRefreshLayout>(false) { view, value -> view.post { view.isRefreshing = value } }
    private var uri: String? = null

    private val defuri: String
        get() = arguments?.takeIf { it.containsKey("url") }?.getString("url") ?: HAcg.philosophy

    val web: WebView by lazy { view!!.findViewById<WebView>(R.id.web) }
    private val progress: ProgressBar by lazy { view!!.findViewById<ProgressBar>(R.id.progress) }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_web, menu)
        return super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
            when (item.itemId) {
                R.id.open -> {
                    openWeb(activity!!, uri!!)
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setHasOptionsMenu(true)
        retainInstance = true
        uri = if (state != null && state.containsKey("url")) state.getString("url") else defuri
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_web, container, false)
        val web: WebView = root.findViewById(R.id.web)

        busy + root.findViewById(R.id.swipe)
        busy.each { it.setOnRefreshListener { web.loadUrl(uri) } }

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        val back = root.findViewById<View>(R.id.button2)
        val fore = root.findViewById<View>(R.id.button3)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                busy * true
                progress.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                busy * false
                progress.progress = 100
                uri = url

                back.isEnabled = view?.canGoBack() ?: false
                fore.isEnabled = view?.canGoForward() ?: false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }

        val click = View.OnClickListener { v ->
            when (v?.id) {
                R.id.button1 -> web.loadUrl(defuri)
                R.id.button2 -> if (web.canGoBack()) web.goBack()
                R.id.button3 -> if (web.canGoForward()) web.goForward()
                R.id.button4 -> web.loadUrl(uri)
            }
        }
        listOf(R.id.button1, R.id.button2, R.id.button3, R.id.button4)
                .map { root.findViewById<View>(it) }.forEach { it.setOnClickListener(click) }

        web.loadUrl(uri)
        return root
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("url", uri)
    }
}
