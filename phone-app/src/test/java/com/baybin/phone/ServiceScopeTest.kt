package com.baybin.phone

import org.junit.Assert.*
import org.junit.Test

class ServiceScopeTest {
    private val city = Rules.City("san_jose", "San Jose", emptyMap(), emptyMap(), "https://example.com/guide")

    @Test fun unconfirmedHousingAndOtherCollectionServicesCannotReceiveDefiniteCityRules() {
        for (housing in ServiceScope.Housing.entries) {
            assertFalse(ServiceScope.covered(city, housing, false))
            assertEquals(housing == ServiceScope.Housing.SINGLE_FAMILY, ServiceScope.covered(city, housing, true))
        }
    }

    @Test fun unsupportedTableScopeNeverBorrowsTheSingleFamilyRules() {
        val unsupported = Rules.City("other", "Other", emptyMap(), emptyMap(), "https://example.com/guide", residence = "unknown")
        assertFalse(ServiceScope.covered(unsupported, ServiceScope.Housing.SINGLE_FAMILY, true))
        val answer = ServiceScope.blocked(unsupported)
        assertNull(answer.bin)
        assertNull(answer.reply)
        assertEquals(unsupported.guideUrl, answer.sourceUrl)
        assertNull(Clarification.next(answer))
    }
}
