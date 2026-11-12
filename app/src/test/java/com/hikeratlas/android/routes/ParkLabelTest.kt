package com.hikeratlas.android.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkLabelTest {
    @Test
    fun kindsAboveShorterNames() {
        assertEquals(ParkLabel("Algonquin", "Provincial park", null), parkLabel("Algonquin Provincial Park"))
        assertEquals(ParkLabel("Banff", "National park", null), parkLabel("Banff National Park of Canada"))
        assertEquals(ParkLabel("Pacific Rim", "National park reserve", null), parkLabel("Pacific Rim National Park Reserve"))
        assertEquals(ParkLabel("Mount Hood", "National forest", null), parkLabel("Mount Hood National Forest"))
        assertEquals(ParkLabel("Raging River", "State forest", null), parkLabel("Raging River State Forest"))
        assertEquals(ParkLabel("Lynn Headwaters", "Regional park", null), parkLabel("Lynn Headwaters Regional Park"))
        assertEquals(ParkLabel("Kejimkujik", "National park and historic site", null), parkLabel("Kejimkujik National Park and National Historic Site"))
        assertEquals(ParkLabel("Parc national du Mont-Tremblant", "Parc national", null), parkLabel("Parc national du Mont-Tremblant"))
    }

    @Test
    fun theTownThatTellsItApartAndTrails() {
        assertEquals(ParkLabel("Dundas Valley", "Conservation area", "near Hamilton"), parkLabel("Dundas Valley Conservation Area (near Hamilton)"))
        assertEquals(ParkLabel("Bruce Trail", "Trail", null), parkLabel("Bruce Trail", "trail-123"))
        assertEquals(ParkLabel("Stanley Park", null, null), parkLabel("Stanley Park"))
        // A kind can't start the name.
        assertEquals(ParkLabel("Wilderness", null, null), parkLabel("Wilderness"))
    }
}
