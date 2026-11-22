package com.baybin.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

internal object ServiceSettings {
    fun bind(activity: Activity, app: App): () -> Boolean {
        val box = activity.findViewById<LinearLayout>(R.id.service_settings)
        val cityId = app.city
        val city = app.rules.cities.getValue(cityId)
        val zh = Lang.zh(app.lang)
        fun label(text: String) = box.addView(TextView(activity).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 12, 0, 8)
        })
        label(if (zh) "服务范围：${city.name}" else "Service area: ${city.name}")
        label(if (zh) "住房类型" else "Housing type")
        val housing = Spinner(activity).apply {
            id = R.id.service_housing
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                ServiceScope.Housing.entries.map { it.label(app.lang) })
            setSelection(app.housing(cityId).ordinal)
        }
        box.addView(housing)
        label(if (zh) "清运服务" else "Collection service")
        val service = Spinner(activity).apply {
            id = R.id.service_collection
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                if (zh) listOf("其他服务 / 尚未确认", "该城市指定的居民清运服务")
                else listOf("Other / not confirmed", "This city's residential collection service"))
            setSelection(if (app.cityCollection(cityId)) 1 else 0)
        }
        box.addView(service)
        val scopeStatus = TextView(activity).apply {
            textSize = 14f
            setPadding(0, 12, 0, 8)
        }
        fun updateScopeStatus() {
            scopeStatus.text = if (app.serviceCovered(cityId)) {
                if (zh) "已保存：此城市的独立住宅居民清运规则可用。" else "Saved: single-family residential rules are available for this city."
            } else {
                if (zh) "已保存：当前服务不在照片分类规则覆盖范围内；仍可文字查询或查看官方指南。"
                else "Saved: photo sorting rules do not cover this service. Text lookup and the official guide remain available."
            }
        }
        updateScopeStatus()
        box.addView(scopeStatus)
        label(city.authority + "\n" + if (zh) "当前规则仅覆盖独立住宅。公寓、商业及其他服务不套用这张表。" else
            "Current tables cover single-family homes only. Apartments, businesses and other services are outside coverage.")
        box.addView(Button(activity).apply {
            text = if (zh) "查看该城市官方服务指南" else "Open the official city service guide"
            setOnClickListener {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(city.guideUrl))) }
                catch (_: ActivityNotFoundException) { Toast.makeText(activity, if (zh) "无法打开浏览器" else "No browser available", Toast.LENGTH_LONG).show() }
            }
        })
        box.addView(Button(activity).apply {
            text = if (zh) "保存服务范围" else "Save service area"
            setOnClickListener {
                app.saveService(cityId, ServiceScope.Housing.entries[housing.selectedItemPosition], service.selectedItemPosition == 1)
                updateScopeStatus()
                Toast.makeText(activity, if (app.serviceCovered(cityId)) {
                    if (zh) "服务范围已保存，此城市规则适用" else "Service area saved; this city's rules apply"
                } else {
                    if (zh) "服务范围已保存，当前服务不在照片规则覆盖内" else "Saved; photo rules do not cover this service"
                }, Toast.LENGTH_LONG).show()
            }
        })
        return {
            ServiceScope.Housing.entries[housing.selectedItemPosition] != app.housing(cityId) ||
                (service.selectedItemPosition == 1) != app.cityCollection(cityId)
        }
    }
}
