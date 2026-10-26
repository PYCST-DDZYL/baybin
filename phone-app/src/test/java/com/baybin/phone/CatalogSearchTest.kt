package com.baybin.phone

import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    private val items = listOf(Rules.Item("paper_cup", "Paper coffee cup", ""),
        Rules.Item("plastic_takeout", "Plastic take-out container", ""),
        Rules.Item("paper_takeout", "Paper take-out box", ""), Rules.Item("battery", "Household battery", ""))

    @Test fun ChineseAndEnglishQueriesFindTheSameCatalogItem() {
        for (query in listOf("纸杯", "PAPER CUP", "paper_cup", " coffee cup ")) {
            assertEquals(listOf("paper_cup"), CatalogSearch.find(items, query).map { it.id })
        }
        assertEquals(listOf("battery"), CatalogSearch.find(items, "电池").map { it.id })
    }

    @Test fun ambiguousTakeoutQueryPreservesMaterialChoicesInsteadOfGuessingOne() {
        assertEquals(setOf("paper_takeout", "plastic_takeout"), CatalogSearch.find(items, "外卖盒").map { it.id }.toSet())
        assertTrue(CatalogSearch.find(items, "不存在的物品").isEmpty())
        assertEquals(items, CatalogSearch.find(items, ""))
    }
}
