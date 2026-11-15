package com.carescape.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeLogTest {
    @Test fun roundTripAndBadRows() {
        val rows = listOf(ChargeLog("10/25", 4, 3, 40, 80, 152), ChargeLog("1/2", 1, 1, 12, 80, null))
        assertEquals(rows, rows.map { ChargeLog.decode(it.encode()) })
        assertNull(ChargeLog.decode(""))
        assertNull(ChargeLog.decode("10/25|x|3|40|80|"))
        assertEquals("10/25 · 4화 · ★★★ · 40→80% · +152km", rows[0].line)
        assertEquals("1/2 · 1화 · ★ · 12→80%", rows[1].line)
    }

    @Test fun adventureCount() {
        assertEquals("우리 가족의 첫 번째 충전 모험", adventure(1))
        assertEquals("우리 가족의 7번째 충전 모험", adventure(7))
    }
}
