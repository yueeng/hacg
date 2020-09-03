package io.github.yueeng.hacg

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import io.github.yueeng.hacg.databinding.ActivityWebBinding
import io.github.yueeng.hacg.databinding.FragmentWebBinding

class WebActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityWebBinding.inflate(layoutInflater).also { binding ->
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }.root)

        supportFragmentManager.beginTransaction()
                .replace(R.id.container, supportFragmentManager.findFragmentById(R.id.container)
                        ?.let { it as? WebFragment }
                        ?: WebFragment().arguments(intent.extras))
                .commit()
    }

    override fun onBackPressed() {
        supportFragmentManager.findFragmentById(R.id.container)
                ?.let { it as? WebFragment }
                ?.takeIf { it.onBackPressed() }
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

class WebViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val busy = handle.getLiveData("busy", false)
    val uri = handle.getLiveData("url", args?.getString("url")!!)
}

class WebViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = WebViewModel(handle, defaultArgs) as T
}

class WebFragment : Fragment() {
    private val viewModel: WebViewModel by viewModels { WebViewModelFactory(this, bundleOf("url" to defuri)) }

    private val defuri: String
        get() = arguments?.takeIf { it.containsKey("url") }?.getString("url")
                ?: (if (isLogin) "${HAcg.philosophy}?foro=signin" else HAcg.philosophy)
    private val isLogin: Boolean get() = arguments?.getBoolean("login", false) ?: false

    fun onBackPressed(): Boolean {
        val binding = FragmentWebBinding.bind(requireView())
        if (binding.web.canGoBack()) {
            binding.web.goBack()
            return true
        }
        return false
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_web, menu)
        return super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.open -> {
            activity?.openUri(viewModel.uri.value!!)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setHasOptionsMenu(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        FragmentWebBinding.bind(requireView()).web.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentWebBinding.inflate(inflater, container, false)
        CookieManager.getInstance().acceptThirdPartyCookies(binding.web)
        viewModel.busy.observe(viewLifecycleOwner, { binding.swipe.isRefreshing = it })
        binding.swipe.setOnRefreshListener { binding.web.loadUrl(viewModel.uri.value!!) }

        val settings = binding.web.settings
        settings.javaScriptEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        val back = binding.button2
        val fore = binding.button3
        binding.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean =
                    activity?.openUri(url, false) == true

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                viewModel.busy.postValue(true)
                binding.progress.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                viewModel.busy.postValue(false)
                binding.progress.progress = 100
                viewModel.uri.postValue(url)

                back.isEnabled = view?.canGoBack() ?: false
                fore.isEnabled = view?.canGoForward() ?: false
                if (isLogin) view?.evaluateJavascript("""favorites_data["user_id"]""") { s ->
                    s?.trim('"')?.toIntOrNull()?.takeIf { it != 0 }?.let {
                        user = it
                        activity!!.finish()
                    }
                }
            }
        }
        binding.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
            }
        }

        val click = View.OnClickListener { v ->
            when (v?.id) {
                R.id.button1 -> binding.web.loadUrl(defuri)
                R.id.button2 -> if (binding.web.canGoBack()) binding.web.goBack()
                R.id.button3 -> if (binding.web.canGoForward()) binding.web.goForward()
                R.id.button4 -> binding.web.loadUrl(viewModel.uri.value!!)
            }
        }
        listOf(binding.button1, binding.button2, binding.button3, binding.button4)
                .forEach { it.setOnClickListener(click) }

        binding.web.loadUrl(viewModel.uri.value!!)
        return binding.root
    }
}
