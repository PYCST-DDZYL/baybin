package com.baybin.phone

import com.baybin.protocol.Proto

internal object ServiceScope {
    enum class Housing(val zh: String, val en: String) {
        UNKNOWN("请选择住房类型", "Choose housing type"),
        SINGLE_FAMILY("独立住宅 / single-family", "Single-family home"),
        APARTMENT("公寓 / 多户住宅", "Apartment / multifamily"),
        BUSINESS("商业 / 办公场所", "Business / workplace");
        fun label(lang: String) = if (Lang.zh(lang)) zh else en
    }
    const val MESSAGE = "Confirm housing and city collection service in Settings. These rules cover single-family residential service only."
    fun covered(city: Rules.City, housing: Housing, cityService: Boolean): Boolean =
        cityService && housing == Housing.SINGLE_FAMILY && city.residence == "single-family"

    fun blocked(city: Rules.City) = Pipeline.Answer(Proto.KIND_UNSURE, Proto.TEXT_UNSURE, MESSAGE,
        cityId = city.id, sourceUrl = city.guideUrl)
}
