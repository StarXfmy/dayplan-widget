package com.xfmy.dayplan

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 添加小组件时弹出，存下"这台设备读哪个 widget.json"。每人一份，互不影响。 */
class ConfigureActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.configure)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val box = findViewById<EditText>(R.id.url)
        val saved = getSharedPreferences(DayPlanWidget.PREFS, MODE_PRIVATE)
            .getString(DayPlanWidget.KEY_URL + widgetId, "") ?: ""
        box.setText(if (saved.isNotEmpty()) saved else getString(R.string.default_url))

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = box.text.toString().trim()
            if (url.isEmpty() || !url.startsWith("http")) {
                box.error = "要填 http 或 https 开头的地址"
                return@setOnClickListener
            }
            getSharedPreferences(DayPlanWidget.PREFS, MODE_PRIVATE).edit()
                .putString(DayPlanWidget.KEY_URL + widgetId, url).apply()
            DayPlanWidget.refreshOne(applicationContext, AppWidgetManager.getInstance(this), widgetId)

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}
