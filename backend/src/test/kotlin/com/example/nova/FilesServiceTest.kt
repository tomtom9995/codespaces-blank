package com.example.nova

import com.example.nova.central.FilesService
import com.example.nova.central.TokenCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesServiceTest {
    @Test
    fun `Pfade werden normalisiert und Ausbruchsversuche abgelehnt`() {
        assertEquals(emptyList(), FilesService.normalize("/"))
        assertEquals(listOf("Corps", "Protokolle", "2026.pdf"), FilesService.normalize("//Corps/Protokolle//2026.pdf"))
        for (bad in listOf("../etc/passwd", "/a/../../b", "a/./b", "a\\b", "a/\u0000b")) {
            assertFailsWith<ApiException>(bad) { FilesService.normalize(bad) }
        }
    }

    @Test
    fun `WebDAV-Antwort von Nextcloud wird gelesen`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/senior/</d:href><d:propstat><d:prop>
   <d:resourcetype><d:collection/></d:resourcetype><oc:size>1234</oc:size></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/senior/Kneipe%20Fotos/</d:href><d:propstat><d:prop>
   <d:getlastmodified>Fri, 02 Oct 2026 18:00:00 GMT</d:getlastmodified>
   <d:resourcetype><d:collection/></d:resourcetype><oc:size>1000</oc:size></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/senior/Satzung.pdf</d:href><d:propstat><d:prop>
   <d:getlastmodified>Thu, 01 Oct 2026 08:30:00 GMT</d:getlastmodified><d:getcontentlength>234</d:getcontentlength>
   <d:getcontenttype>application/pdf</d:getcontenttype><d:resourcetype/></d:prop></d:propstat></d:response>
</d:multistatus>"""
        val entries = FilesService.parseMultiStatus(xml, "/remote.php/dav/files/senior/")
        assertEquals(3, entries.size)
        val folder = entries.first { it.name == "Kneipe Fotos" }
        assertTrue(folder.isFolder)
        assertEquals("/Kneipe Fotos", folder.path)
        assertEquals("2026-10-02T18:00:00Z", folder.modified)
        assertNull(folder.contentType)
        val pdf = entries.first { it.name == "Satzung.pdf" }
        assertEquals(234L, pdf.size)
        assertEquals("application/pdf", pdf.contentType)
    }

    @Test
    fun `WebDAV-Antworten mit DOCTYPE werden abgelehnt (XXE)`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&e;</d:multistatus>"""
        assertFailsWith<Exception> { FilesService.parseMultiStatus(xml, "/") }
    }

    @Test
    fun `Tokens werden verschlüsselt gespeichert`() {
        val crypto = TokenCrypto("test-secret")
        val enc = crypto.encrypt("eyJ.access.token")
        assertNotEquals("eyJ.access.token", enc)
        assertNotEquals(enc, crypto.encrypt("eyJ.access.token"))
        assertEquals("eyJ.access.token", crypto.decrypt(enc))
        assertFailsWith<Exception> { TokenCrypto("anderes-secret").decrypt(enc) }
    }
}
