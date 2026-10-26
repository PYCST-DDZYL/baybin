package com.baybin.phone

import java.util.Locale

internal object CatalogSearch {
    private val aliases = mapOf(
        "plastic_takeout" to "外卖盒 饭盒 塑料餐盒 takeout food container",
        "paper_takeout" to "外卖盒 饭盒 纸餐盒 takeout food container",
        "foam_food_container" to "外卖盒 饭盒 泡沫餐盒 styrofoam takeout",
        "plastic_clamshell" to "外卖盒 透明盒 水果盒 clamshell packaging",
        "paper_cup" to "咖啡杯 奶茶杯 纸杯 coffee cup",
        "plastic_cup" to "奶茶杯 塑料杯 plastic cup",
        "plastic_bag" to "购物袋 塑料袋 plastic bag",
        "cardboard_box" to "纸箱 纸板 快递盒 cardboard shipping box",
        "pizza_box_greasy" to "油污纸盒 油渍纸盒 披萨盒 greasy pizza box",
        "food_in_container" to "剩饭 剩菜 带食物餐盒 leftovers food in container",
        "battery" to "电池 纽扣电池 锂电池 battery batteries",
        "bulb_fluorescent" to "灯泡 荧光灯 节能灯 fluorescent cfl bulb",
        "phone_electronics" to "电子垃圾 手机 旧手机 electronic waste phone",
    )
    private fun normalized(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun find(items: List<Rules.Item>, query: String): List<Rules.Item> {
        if (query.isBlank()) return items
        val terms = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (terms.isEmpty()) return emptyList()
        val needle = normalized(query)
        return items.mapNotNull { item ->
            val names = listOf(item.id, item.name, Lang.itemName(item.id, item.name, "zh")).map(::normalized)
            val searchable = names + normalized(aliases[item.id].orEmpty())
            if (!terms.all { term -> searchable.any { term in it } }) return@mapNotNull null
            val rank = when {
                normalized(item.id) == needle -> 0
                needle in names -> 1
                searchable.any { needle in it } -> 2
                else -> 3
            }
            item to rank
        }.sortedBy { it.second }.map { it.first }
    }
}
