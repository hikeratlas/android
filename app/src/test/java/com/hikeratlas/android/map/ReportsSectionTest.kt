package com.hikeratlas.android.map

import com.hikeratlas.android.routes.ReportPage
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportsSectionTest {
    @Test
    fun datesAndMeta() {
        assertEquals("Aug 2018", reportDate("2018-08"))
        assertEquals("Aug 2018", reportDate("2018-08-14"))
        assertEquals("2018", reportDate("2018"))
        assertEquals("", reportDate(null))
        assertEquals("blog.example · Aug 2018 · trip report", reportMeta(ReportPage("u", "t", "www.blog.example", type = "trip_report", date = "2018-08")))
        assertEquals("parks.example · trail guide", reportMeta(ReportPage("u", "t", "parks.example", type = "trail_guide")))
    }
}
