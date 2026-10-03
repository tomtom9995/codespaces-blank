package com.example.nova

import com.example.nova.central.EventsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsServiceTest {
    @Test
    fun `iCalendar wird gelesen – Zeitzone, ganztägig, gefaltete Zeilen, Sonderzeichen`() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:stiftungsfest\r\nDTSTART;TZID=Europe/Berlin:20261128T180000\r\n" +
            "DTEND;TZID=Europe/Berlin:20261129T020000\r\nSUMMARY:Stiftungsfest\\, 175 Jahre\r\nLOCATION:Kur\r\n haus\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:ferien\r\nDTSTART;VALUE=DATE:20261224\r\nSUMMARY:Weihnachtsferien\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:serie\r\nRECURRENCE-ID:20261105T180000Z\r\nDTSTART:20261105T180000Z\r\nSUMMARY:Stammtisch\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val events = EventsService.parseEvents(ics, "Semesterprogramm")
        assertEquals(3, events.size)
        val fest = events[0]
        assertEquals("Stiftungsfest, 175 Jahre", fest.title)
        assertEquals("2026-11-28T17:00:00Z", fest.start)
        assertEquals("Kurhaus", fest.location)
        assertEquals(false, fest.internal)
        assertTrue(events[1].allDay)
        assertEquals("2026-12-23T23:00:00Z", events[1].start)
        assertEquals("serie#20261105T180000Z", events[2].id)
        assertTrue(EventsService.parseEvents(ics, "Semesterprogramm intern").all { it.internal })
    }

    @Test
    fun `nur Kalender-Sammlungen werden gefunden`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
 <d:response><d:href>/remote.php/dav/calendars/bursch/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/bursch/semesterprogramm_shared_by_nc-admin/</d:href><d:propstat><d:prop>
   <d:displayname>Semesterprogramm</d:displayname><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype></d:prop></d:propstat></d:response>
</d:multistatus>"""
        assertEquals(listOf("/remote.php/dav/calendars/bursch/semesterprogramm_shared_by_nc-admin/" to "Semesterprogramm"), EventsService.parseCalendars(xml))
    }
}
