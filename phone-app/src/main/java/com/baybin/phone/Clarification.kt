package com.baybin.phone

import com.baybin.protocol.Proto

internal object Clarification {
    data class Choice(val value: String, val zh: String, val en: String) {
        fun label(lang: String) = if (Lang.zh(lang)) zh else en
    }
    data class Question(val field: String, val zh: String, val en: String, val choices: List<Choice>) {
        fun title(lang: String) = if (Lang.zh(lang)) zh else en
    }
    private val unknown = Choice("unknown", "看不清 / 不确定", "Can't tell")

    fun next(answer: Pipeline.Answer): Question? {
        if (answer.kind != Proto.KIND_UNSURE || answer.help == null) return null
        val reply = answer.reply ?: return null
        val seen = reply.observation ?: return null
        val question = when (answer.help) {
            Recognition.Help.OPEN -> if (seen.food == "unknown") Question("food", "容器里还有食物吗？", "Is there still food in the container?",
                listOf(Choice("present", "有食物", "Food remains"), Choice("none", "没有食物（饮料请先倒空）", "No food (empty drinks first)"), unknown)) else null
            Recognition.Help.MARK -> if (seen.material == "unknown") Question("material", "请查看底部编号或可堆肥标志", "Read the number or compostable marking",
                (1..7).map { Choice(it.toString(), "清楚标着 #$it", "Clearly marked #$it") } +
                    Choice("compostable", "标着 COMPOSTABLE / PLA", "Marked COMPOSTABLE / PLA") + unknown) else null
            Recognition.Help.EMPTY -> if (seen.empty == "unknown") Question("empty", "你确认容器已完全用空了吗？", "Can you confirm the container is completely empty?",
                listOf(Choice("yes", "已确认完全用空", "Confirmed completely empty"), Choice("no", "还没用空", "Not empty"), unknown)) else null
            Recognition.Help.CLEAN -> if (seen.paper == "unknown") Question("paper", "打开查看：纸盒有没有食物或油渍？", "Open it: are there food or grease stains?",
                listOf(Choice("clean", "内外都干净、无食物油渍", "Clean inside and outside"), Choice("soiled", "有食物或油渍", "Food or grease stains"), unknown)) else null
            Recognition.Help.LINING -> if (seen.paper == "unknown") Question("paper", "打开查看：纸容器内侧有涂层吗？", "Is the open paper container lined?",
                listOf(Choice("uncoated", "确认无塑料或铝箔涂层", "Confirmed no plastic/foil lining"), Choice("coated", "有涂层", "Lined"), unknown)) else null
            else -> null
        }
        return question?.takeUnless { it.field in reply.userFields }
    }
}
