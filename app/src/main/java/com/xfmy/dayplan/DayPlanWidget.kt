package com.xfmy.dayplan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.appwidget.AppWidgetProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只读消费一份 widget.json，把今天摊在桌面上。
 * 渲染规则与 widget.html 保持一致，尤其三件事：
 *   1. 过期判断看「今天这条还在不在 days 里」，不是比 days[0].date；
 *   2. 过期时不显示倒计时和天气，那是发布当时的进度；
 *   3. 天气 offline 时把 ago 文本带出来，不拿旧数据冒充实时。
 */
class DayPlanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> refreshOne(context, mgr, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mgr = AppWidgetManager.getInstance(context)
        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        if (ids != null) ids.forEach { refreshOne(context, mgr, it) }
    }

    companion object {
        const val PREFS = "dayplan"
        const val KEY_URL = "url_"

        private fun today(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun refreshOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL + id, "") ?: ""
            Thread {
                val views = build(context, id, url)
                try { mgr.updateAppWidget(id, views) } catch (e: Exception) { }
            }.start()
        }

        fun build(context: Context, id: Int, url: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setOnClickPendingIntent(R.id.root, openApp(context, id, appTarget(url)))

            if (url.isBlank()) {
                views.setTextViewText(R.id.title, "Day Plan")
                views.setTextViewText(R.id.sub, "还没设置快照地址，长按小组件重新配置")
                hideRows(views)
                return views
            }

            val cands = variants(url)
            val raw = fetchAny(cands)
            if (raw == null) {
                views.setTextViewText(R.id.title, "Day Plan")
                views.setTextViewText(R.id.sub, "取不到快照（试了 " + cands.size + " 个等价地址）")
                hideRows(views)
                return views
            }

            val snap = try { JSONObject(raw) } catch (e: Exception) { null }
            if (snap == null) {
                views.setTextViewText(R.id.title, "Day Plan")
                views.setTextViewText(R.id.sub, "快照不是合法 JSON")
                hideRows(views)
                return views
            }

            applyColors(views, snap)
            // 点击跳转以快照里记的 appUrl 为准：文件名换成 widget-9tu5ms.json 这一类之后，
            // 从地址里砍掉文件名推不出应用入口。
            val inApp = snap.optString("appUrl", "")
            if (inApp.startsWith("http")) views.setOnClickPendingIntent(R.id.root, openApp(context, id, inApp))
            val days = snap.optJSONArray("days")
            val day = pickToday(days)

            if (day == null) {
                // 快照里没有今天这条 = 发布之后没再更新过，别拿旧数据糊弄
                val first = days?.optJSONObject(0)?.optString("date", "") ?: ""
                views.setTextViewText(R.id.title, "Day Plan")
                views.setTextViewText(R.id.sub, if (first.isEmpty()) "快照里没有内容，去 Day Plan 重新发布"
                    else "快照停在 " + first + "，去 Day Plan 发布一次")
                hideRows(views)
                return views
            }

            val weekday = day.optString("weekday")
            val lunar = day.optString("lunar")
            val title = if (lunar.isEmpty()) weekday else weekday + " · " + lunar
            views.setTextViewText(R.id.title, title)
            views.setTextViewText(R.id.sub, day.optString("summary"))

            val courses = day.optJSONArray("courses")
            var shown = 0
            if (courses != null) {
                for (i in 0 until minOf(courses.length(), 4)) {
                    val c = courses.optJSONObject(i) ?: continue
                    val whenText = c.optString("when")
                    val name = c.optString("name")
                    val room = c.optString("room")
                    var line = if (whenText.isEmpty()) name else whenText + "  " + name
                    if (room.isNotEmpty()) line = line + " · " + room
                    val resId = rowId(i)
                    if (resId != 0) {
                        views.setTextViewText(resId, line)
                        views.setTextColor(resId, colorOf(snap, if (c.optBoolean("now")) "accent" else "ink"))
                        views.setViewVisibility(resId, View.VISIBLE)
                    }
                    shown++
                }
            }
            val more = day.optInt("moreCourses", 0)
            if (more > 0 && shown < 4) {
                val resId = rowId(shown)
                if (resId != 0) {
                    views.setTextViewText(resId, "还有 " + more + " 节")
                    views.setTextColor(resId, colorOf(snap, "sub"))
                    views.setViewVisibility(resId, View.VISIBLE)
                    shown++
                }
            }
            for (i in shown until 4) {
                val resId = rowId(i)
                if (resId != 0) views.setViewVisibility(resId, View.GONE)
            }

            val next = day.optJSONObject("next")
            if (next != null) {
                views.setTextViewText(R.id.next, next.optString("text"))
                val st = next.optString("state")
                views.setTextColor(R.id.next, colorOf(snap, if (st == "done") "sub" else "warn"))
                views.setViewVisibility(R.id.next, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.next, View.GONE)
            }

            val wx = snap.optJSONObject("wx")
            if (wx != null && day.optString("date") == today()) {
                var line = wx.optString("text") + " " + wx.optString("temp") + "° · " + wx.optString("city")
                if (wx.optBoolean("offline")) line = line + " · " + wx.optString("ago") + "缓存"
                views.setTextViewText(R.id.wx, line)
                views.setViewVisibility(R.id.wx, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.wx, View.GONE)
            }
            return views
        }

        private fun pickToday(days: org.json.JSONArray?): JSONObject? {
            if (days == null) return null
            val t = today()
            for (i in 0 until days.length()) {
                val d = days.optJSONObject(i)
                if (d != null && d.optString("date") == t) return d
            }
            return null
        }

        private fun rowId(i: Int): Int = when (i) {
            0 -> R.id.row1
            1 -> R.id.row2
            2 -> R.id.row3
            3 -> R.id.row4
            else -> 0
        }

        private fun hideRows(views: RemoteViews) {
            for (i in 0 until 4) {
                val r = rowId(i)
                if (r != 0) views.setViewVisibility(r, View.GONE)
            }
            views.setViewVisibility(R.id.next, View.GONE)
            views.setViewVisibility(R.id.wx, View.GONE)
        }

        private fun colorOf(snap: JSONObject, which: String): Int {
            val hex = snap.optJSONObject("colors")?.optString(which, "") ?: ""
            return try {
                android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#" + hex)
            } catch (e: Exception) {
                when (which) {
                    "warn" -> android.graphics.Color.parseColor("#b3402f")
                    "sub" -> android.graphics.Color.parseColor("#7a6a55")
                    "accent" -> android.graphics.Color.parseColor("#c8863c")
                    else -> android.graphics.Color.parseColor("#33291d")
                }
            }
        }

        private fun applyColors(views: RemoteViews, snap: JSONObject) {
            try {
                views.setInt(R.id.root, "setBackgroundColor", colorOf(snap, "card"))
            } catch (e: Exception) { }
        }

        private fun openApp(context: Context, id: Int, target: String): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            return PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 取不到快照时的兜底：从快照地址推出应用入口。
            raw.githubusercontent.com 那个域名只发原始文件、不发网页，得换成 Pages 域名。 */
        private fun appTarget(url: String): String {
            val raw = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/.*")
                .find(url)
            if (raw != null) {
                return "https://" + raw.groupValues[1].lowercase() + ".github.io/" +
                        raw.groupValues[2] + "/kernel/index.html"
            }
            val base = url.substringBeforeLast('/', "")
            return if (base.contains('.')) base + "/kernel/index.html"
            else "https://starxfmy.github.io/xfmy-local/kernel/index.html"
        }

        /** 同一份快照在各域名下的等价地址。raw、Pages、jsDelivr 在国内都不稳，
            所以只填一个地址、这里逐个试，哪个通用哪个——与 iOS 那份脚本同一套做法。 */
        private fun variants(url: String): List<String> {
            val out = ArrayList<String>()
            fun add(u: String) { if (u.isNotBlank() && !out.contains(u)) out.add(u) }
            val t = url.trim()
            add(t)

            val pages = Regex("^https?://([^.]+)\\.github\\.io/([^/]+)/(.+)$", RegexOption.IGNORE_CASE).find(t)
            if (pages != null) {
                val f = pages.groupValues[3].substringBefore('?').substringBefore('#')
                add("https://raw.githubusercontent.com/" + pages.groupValues[1] + "/" + pages.groupValues[2] + "/main/" + f)
                add("https://cdn.jsdelivr.net/gh/" + pages.groupValues[1] + "/" + pages.groupValues[2] + "@main/" + f)
            }

            val rawHit = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$",
                RegexOption.IGNORE_CASE).find(t)
            if (rawHit != null) {
                val g = rawHit.groupValues
                val f = g[4].substringBefore('?').substringBefore('#')
                add("https://" + g[1].lowercase() + ".github.io/" + g[2] + "/" + f)
                add("https://cdn.jsdelivr.net/gh/" + g[1] + "/" + g[2] + "@" + g[3] + "/" + f)
            }

            val cdn = Regex("^https?://(?:fastly\\.)?cdn\\.jsdelivr\\.net/gh/([^/]+)/([^/]+)@([^/]+)/(.+)$",
                RegexOption.IGNORE_CASE).find(t)
            if (cdn != null) {
                val g = cdn.groupValues
                val f = g[5].substringBefore('?').substringBefore('#')
                add("https://raw.githubusercontent.com/" + g[2] + "/" + g[3] + "/" + g[4] + "/" + f)
                add("https://" + g[2].lowercase() + ".github.io/" + g[3] + "/" + f)
            }
            return out
        }

        private fun fetchAny(urls: List<String>): String? {
            for (u in urls) { val r = fetch(u); if (r != null) return r }
            return null
        }

        private fun fetch(url: String): String? {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DayPlanWidget/1.0")
                    // 绕开 Pages 那层十分钟 CDN 缓存
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (conn.responseCode != 200) return null
                BufferedReader(conn.inputStream.reader()).use { it.readText() }
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
