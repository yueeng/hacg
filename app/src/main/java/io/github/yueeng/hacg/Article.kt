@file:Suppress("MayBeConstant", "unused", "MemberVisibilityCanBePrivate", "FunctionName")

package io.github.yueeng.hacg

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.annotations.SerializedName
import io.github.yueeng.hacg.databinding.AlertHostBinding
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.jsoup.nodes.Element
import java.io.File
import java.util.*
import kotlin.math.min


object HAcg {
    private val SYSTEM_HOST: String = "system.host"
    private val SYSTEM_HOSTS: String = "system.hosts"

    data class HacgConfig(
            @SerializedName("version") val version: Int,
            @SerializedName("bbs") val bbs: String,
            @SerializedName("category") val category: List<Category>,
            @SerializedName("host") val host: List<String>
    )

    data class Category(
            @SerializedName("name") val name: String,
            @SerializedName("url") val url: String
    )

    private val configFile: File get() = File(HAcgApplication.instance.filesDir, "config.json")

    @Synchronized
    private fun defaultConfig(): HacgConfig? = try {
        val str = if (configFile.exists())
            configFile.readText()
        else HAcgApplication.instance.assets.open("config.json").use { s ->
            s.reader().use { it.readText() }
        }
        gson.fromJson(str, HacgConfig::class.java)
    } catch (_: Exception) {
        null
    }

    private fun defaultHosts(cfg: HacgConfig? = null): List<String> = try {
        (cfg ?: defaultConfig())!!.host
    } catch (_: Exception) {
        listOf("www.hacg.me")
    }

    private fun defaultCategory(cfg: HacgConfig? = null): List<Pair<String, String>> = try {
        (cfg ?: defaultConfig())!!.category.map { it.url to it.name }
    } catch (_: Exception) {
        listOf()
    }

    private fun defaultBbs(cfg: HacgConfig? = null): String = try {
        (cfg ?: defaultConfig())!!.bbs
    } catch (_: Exception) {
        "/wp/bbs"
    }

    private val config = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance).also { c ->
        val avc = "app.version.code"
        if (c.getInt(avc, 0) < BuildConfig.VERSION_CODE) {
            c.edit().remove(SYSTEM_HOST)
                    .remove(SYSTEM_HOSTS)
                    .putInt(avc, BuildConfig.VERSION_CODE)
                    .apply()
            configFile.delete()
        }
    }

    private var saveHosts: List<String>
        get() = try {
            config.getString(SYSTEM_HOSTS, null).let { s ->
                gson.fromJson(s, Array<String>::class.java).toList()
            }
        } catch (_: Exception) {
            listOf()
        }
        set(hosts): Unit = config.edit().also { c ->
            c.remove(SYSTEM_HOSTS)
            if (hosts.any())
                c.remove(SYSTEM_HOSTS).putString(SYSTEM_HOSTS, gson.toJson(hosts.distinct()))
        }.apply()

    fun hosts(): List<String> = (saveHosts + defaultHosts()).distinct()

    var host: String
        get() = config.getString(SYSTEM_HOST, null)?.takeIf { it.isNotEmpty() } ?: (hosts().first())
        set(host): Unit = config.edit().also { c ->
            if (host.isEmpty()) c.remove(SYSTEM_HOST) else c.putString(SYSTEM_HOST, host)
        }.apply()

    val bbs: String
        get() = defaultBbs()

    val categories: List<Pair<String, String>>
        get() = defaultCategory()

    suspend fun update(context: Activity, tip: Boolean, updated: () -> Unit) {
        val html = "https://raw.githubusercontent.com/yueeng/hacg/master/app/src/main/assets/config.json".httpGetAwait()
        val config = try {
            gson.fromJson(html?.first, HacgConfig::class.java)
        } catch (_: Exception) {
            null
        }
        when {
            config == null -> Unit
            config.version <= defaultConfig()?.version ?: 0 -> if (tip) context.toast(R.string.settings_config_newest)
            else -> context.snack(context.getString(R.string.settings_config_updating), Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings_config_update) {
                        runCatching {
                            host = defaultHosts(config).first()
                            configFile.writeText(html!!.first)
                            updated()
                        }
                    }.show()
        }
    }

    val IsHttp: Regex = """^https?://.*$""".toRegex()
    val RELEASE = "https://github.com/yueeng/hacg/releases"

    val web get() = "https://$host"

    val domain: String
        get() = host.indexOf('/').takeIf { it >= 0 }?.let { host.substring(0, it) } ?: host

    val wordpress: String get() = "$web/wp"

    val philosophy: String
        get() = bbs.takeIf { IsHttp.matches(it) }?.let { bbs } ?: "$web$bbs"

    val wpdiscuz
        get() = "$wordpress/wp-content/plugins/wpdiscuz/utils/ajax/wpdiscuz-ajax.php"

    fun setHostEdit(context: Context, title: Int, list: () -> List<String>, cur: () -> String, set: (String) -> Unit, ok: (String) -> Unit, reset: () -> Unit) {
        val view = AlertHostBinding.inflate(LayoutInflater.from(context))
        MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view.root)
                .setNegativeButton(R.string.app_cancel, null)
                .setOnDismissListener { setHostList(context, title, list, cur, set, ok, reset) }
                .setNeutralButton(R.string.settings_host_reset) { _, _ -> reset() }
                .setPositiveButton(R.string.app_ok) { _, _ ->
                    val host = view.edit1.text.toString()
                    if (host.isNotEmpty()) ok(host)
                }
                .create().show()
    }

    fun setHostList(context: Context, title: Int, list: () -> List<String>, cur: () -> String, set: (String) -> Unit, ok: (String) -> Unit, reset: () -> Unit) {
        val hosts = list().toList()
        MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(hosts.toTypedArray(), hosts.indexOf(cur()).takeIf { it >= 0 }
                        ?: 0, null)
                .setNegativeButton(R.string.app_cancel, null)
                .setNeutralButton(R.string.settings_host_more) { _, _ -> setHostEdit(context, title, list, cur, set, ok, reset) }
                .setPositiveButton(R.string.app_ok) { d, _ -> set(hosts[(d as AlertDialog).listView.checkedItemPosition]) }
                .create().show()
    }

    fun setHost(context: Context, ok: (String) -> Unit = {}) {
        setHostList(context,
                R.string.settings_host,
                { hosts() },
                { host },
                {
                    host = it
                    ok(host)
                },
                { host -> saveHosts = (saveHosts + host) },
                { saveHosts = (listOf()) }
        )
    }
}

data class Version(val ver: List<Int>) {
    operator fun compareTo(other: Version): Int {
        val v1 = ver
        val v2 = other.ver
        for (i in 0 until min(v1.size, v2.size)) {
            val v = v1[i] - v2[i]
            when {
                v > 0 -> return 1
                v < 0 -> return -1
            }
        }
        return when {
            v1.size > v2.size && v1.drop(v2.size).any { it != 0 } -> 1
            v1.size < v2.size && v2.drop(v1.size).any { it != 0 } -> -1
            else -> 0
        }
    }

    override fun toString(): String = ver.joinToString(".")

    constructor(ver: String) : this(ver.split('.').map { it.toInt() })

    companion object {
        fun from(ver: String?) = try {
            ver?.let { Version(ver) }
        } catch (_: Exception) {
            null
        }
    }
}

@Parcelize
data class Tag(val name: String, val url: String) : Parcelable {
    constructor(e: Element) :
            this(e.text(), e.attr("abs:href"))
}

@Parcelize
data class Comment(val id: Int, val parent: Int, val content: String, val user: String, val face: String,
                   var moderation: Int, val time: String, val children: MutableList<Comment>, val depth: Int = 1) : Parcelable {
    companion object {
        val ID: Regex = """wpd-comm-(\d+)_(\d+)""".toRegex()
    }

    val uniqueId: String get() = "${id}_$parent"

    constructor(e: Element, depth: Int = 1) :
            this(ID.find(e.attr("id"))?.let { it.groups[1]?.value }?.toInt() ?: 0,
                    ID.find(e.attr("id"))?.let { it.groups[2]?.value }?.toInt() ?: 0,
                    e.select(">.wpd-comment-wrap .wpd-comment-text").text(),
                    e.select(">.wpd-comment-wrap .wpd-comment-author").text(),
                    e.select(">.wpd-comment-wrap .avatar").attr("abs:src"),
                    e.select(">.wpd-comment-wrap .wpd-vote-result").text().toIntOrNull() ?: 0,
                    e.select(">.wpd-comment-wrap .wpd-comment-date").text(),
                    e.select(">.wpd-comment-wrap~.wpd-reply").map { Comment(it, depth + 1) }.toMutableList(),
                    depth
            )
}

@Parcelize
data class JComment(
        @SerializedName("last_parent_id") val lastParentId: String,
        @SerializedName("is_show_load_more") val isShowLoadMore: Boolean,
        @SerializedName("comment_list") val commentList: String?,
        @SerializedName("loadLastCommentId") val loadLastCommentId: String
) : Parcelable

@Parcelize
data class JWpdiscuzComment(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JComment
) : Parcelable

@Parcelize
data class JCommentResult(
        @SerializedName("code") val code: String,
        @SerializedName("comment_author") val commentAuthor: String,
        @SerializedName("comment_author_email") val commentAuthorEmail: String,
        @SerializedName("comment_author_url") val commentAuthorUrl: String,
        @SerializedName("held_moderate") val held_moderate: Int,
        @SerializedName("is_in_same_container") val isInSameContainer: String,
        @SerializedName("is_main") val is_main: Int,
        @SerializedName("message") val message: String,
        @SerializedName("new_comment_id") val newCommentId: Int,
        @SerializedName("redirect") val redirect: Int,
        @SerializedName("uniqueid") val uniqueid: String,
        @SerializedName("wc_all_comments_count_new") val wcAllCommentsCountNew: String,
        @SerializedName("wc_all_comments_count_new_html") val wcAllCommentsCountNewHtml: String
) : Parcelable

@Parcelize
data class JWpdiscuzCommentResult(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JCommentResult
) : Parcelable

@Parcelize
data class JWpdiscuzVote(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: String
) : Parcelable

@Parcelize
data class JWpdiscuzVoteSucceed(
        @SerializedName("success") val success: Boolean,
        @SerializedName("data") val data: JWpdiscuzVoteSucceedData
) : Parcelable

@Parcelize
data class JWpdiscuzVoteSucceedData(
        @SerializedName("buttonsStyle") val buttonsStyle: String,
        @SerializedName("votes") val votes: String
) : Parcelable

@Parcelize
data class Article(val id: Int, val title: String,
                   val link: String?,
                   val image: String?,
                   val content: String?,
                   val time: Date?,
                   val comments: Int,
                   val author: Tag?,
                   val category: Tag?,
                   val tags: List<Tag>) : Parcelable {
    companion object {
        val ID: Regex = """post-(\d+)""".toRegex()
        fun parseID(str: String?) = str?.let { s -> ID.find(s)?.let { it.groups[1]?.value?.toInt() } }
        private val URL: Regex = """/wp/(\d+)\.html""".toRegex()
        fun getIdFromUrl(str: String?) = str?.let { s -> URL.find(s)?.let { it.groups[1]?.value?.toInt() } }
        private val LIST = listOf("/wp/tag/", "/wp/author/", "/wp/?s=")
        fun isList(url: String): Boolean = Uri.parse(url).path?.let { path ->
            HAcg.categories.any { path == it.first } || LIST.any { path.startsWith(it) }
        } ?: false
    }

    constructor(msg: String) : this(0, msg, null, null, null, null, 0, null, null, listOf())

    @IgnoredOnParcel
    private val defimg = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${this::class.java.`package`!!.name}/drawable/placeholder"

    @IgnoredOnParcel
    val img: String
        get() = if (image.isNullOrEmpty()) defimg else image

    @IgnoredOnParcel
    val expend: List<Tag> by lazy { (tags + category + author).mapNotNull { it } }

    constructor(e: Element) :
            this(parseID(e.attr("id")) ?: 0,
                    e.select("header .entry-title").text().trim(),
                    e.select("header .entry-title,.entry-meta a").attr("abs:href"),
                    e.select(".entry-content img").let { img ->
                        img.takeIf { it.hasClass("avatar") }?.let { "" } ?: img.attr("abs:src")
                    },
                    e.select(".entry-content p,.entry-summary p").firstOrNull()?.text()?.trim(),
                    e.select("time").attr("datetime").toDate(),
                    e.select("header .comments-link").text().trim().toIntOrNull() ?: 0,
                    e.select(".author a").take(1).map { Tag(it) }.firstOrNull(),
                    e.select("footer .cat-links a").take(1).map { Tag(it) }.firstOrNull(),
                    e.select("footer .tag-links a").map { Tag(it) }.toList())
}

data class JGitHubRelease(
        @SerializedName("assets") val assets: List<JGitHubReleaseAsset>,
        @SerializedName("assets_url") val assetsUrl: String,
        @SerializedName("author") val author: JGitHubReleaseAuthor,
        @SerializedName("body") val body: String,
        @SerializedName("created_at") val createdAt: String,
        @SerializedName("draft") val draft: Boolean,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("id") val id: Int,
        @SerializedName("name") val name: String,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("prerelease") val prerelease: Boolean,
        @SerializedName("published_at") val publishedAt: String,
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("tarball_url") val tarballUrl: String,
        @SerializedName("target_commitish") val targetCommitish: String,
        @SerializedName("upload_url") val uploadUrl: String,
        @SerializedName("url") val url: String,
        @SerializedName("zipball_url") val zipballUrl: String
)

data class JGitHubReleaseAsset(
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("content_type") val contentType: String,
        @SerializedName("created_at") val createdAt: String,
        @SerializedName("download_count") val downloadCount: Int,
        @SerializedName("id") val id: Int,
        @SerializedName("label") val label: Any,
        @SerializedName("name") val name: String,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("size") val size: Int,
        @SerializedName("state") val state: String,
        @SerializedName("updated_at") val updatedAt: String,
        @SerializedName("uploader") val uploader: JGitHubReleaseUploader,
        @SerializedName("url") val url: String
)

data class JGitHubReleaseAuthor(
        @SerializedName("avatar_url") val avatarUrl: String,
        @SerializedName("events_url") val eventsUrl: String,
        @SerializedName("followers_url") val followersUrl: String,
        @SerializedName("following_url") val followingUrl: String,
        @SerializedName("gists_url") val gistsUrl: String,
        @SerializedName("gravatar_id") val gravatarId: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("id") val id: Int,
        @SerializedName("login") val login: String,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("organizations_url") val organizationsUrl: String,
        @SerializedName("received_events_url") val receivedEventsUrl: String,
        @SerializedName("repos_url") val reposUrl: String,
        @SerializedName("site_admin") val siteAdmin: Boolean,
        @SerializedName("starred_url") val starredUrl: String,
        @SerializedName("subscriptions_url") val subscriptionsUrl: String,
        @SerializedName("type") val type: String,
        @SerializedName("url") val url: String
)

data class JGitHubReleaseUploader(
        @SerializedName("avatar_url") val avatarUrl: String,
        @SerializedName("events_url") val eventsUrl: String,
        @SerializedName("followers_url") val followersUrl: String,
        @SerializedName("following_url") val followingUrl: String,
        @SerializedName("gists_url") val gistsUrl: String,
        @SerializedName("gravatar_id") val gravatarId: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("id") val id: Int,
        @SerializedName("login") val login: String,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("organizations_url") val organizationsUrl: String,
        @SerializedName("received_events_url") val receivedEventsUrl: String,
        @SerializedName("repos_url") val reposUrl: String,
        @SerializedName("site_admin") val siteAdmin: Boolean,
        @SerializedName("starred_url") val starredUrl: String,
        @SerializedName("subscriptions_url") val subscriptionsUrl: String,
        @SerializedName("type") val type: String,
        @SerializedName("url") val url: String
)