package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.ui.FieldGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldGeometryTest {
    @Test fun `yard line is measured from the home goal line`() {
        assertEquals(0.25f, FieldGeometry.x(25), "NYG 25 at home")
        assertEquals(0.58f, FieldGeometry.x(58), "WSH 42 is 58 from home")
    }

    @Test fun `home attacks right and away attacks left`() {
        assertEquals(1f, FieldGeometry.attackingToward("home"), "home toward away goal")
        assertEquals(0f, FieldGeometry.attackingToward("away"), "away toward home goal")
    }

    @Test fun `first down line for the away team moves toward the home goal`() {
        assertEquals(0.53f, FieldGeometry.firstDownX(58, 5, "away")!!, 0.0001f, "2nd and 5 at 58")
    }

    @Test fun `first down line for the home team moves toward the away goal`() {
        assertEquals(0.35f, FieldGeometry.firstDownX(25, 10, "home")!!, 0.0001f, "1st and 10 at 25")
    }

    @Test fun `goal to go and unknowns have no first down line`() {
        assertNull(FieldGeometry.firstDownX(95, 5, "home"), "goal to go home")
        assertNull(FieldGeometry.firstDownX(4, 4, "away"), "goal to go away")
        assertNull(FieldGeometry.firstDownX(50, null, "home"), "no distance")
        assertNull(FieldGeometry.firstDownX(50, 0, "home"), "between plays")
        assertNull(FieldGeometry.firstDownX(50, 10, null), "unknown possession")
    }

    @Test fun `drive span is ordered and absent without a start`() {
        assertEquals(0.2f to 0.45f, FieldGeometry.driveSpan(45, 20), "ball behind the start")
        assertEquals(0.2f to 0.45f, FieldGeometry.driveSpan(20, 45), "ball ahead of the start")
        assertNull(FieldGeometry.driveSpan(null, 45), "no start")
    }
}
