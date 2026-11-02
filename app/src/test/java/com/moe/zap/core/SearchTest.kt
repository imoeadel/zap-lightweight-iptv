package com.moe.zap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
    @Test fun lowercasesAndStripsAccents() = assertEquals("beyonce live", Search.normalize("  BEYONCÉ — Live!! "))

    @Test fun splitsPanelPrefixesIntoWords() = assertEquals("ar bein sports 1 hd", Search.normalize("AR | beIN Sports 1 (HD)"))

    @Test fun foldsArabicSpellingVariants() {
        assertEquals(Search.normalize("الجزيره"), Search.normalize("الجزيرة"))
        assertEquals(Search.normalize("احمد"), Search.normalize("أحمد"))
        assertEquals(Search.normalize("مصطفي"), Search.normalize("مصطفى"))
        assertEquals(Search.normalize("محمد"), Search.normalize("مُحَمَّد"))
        assertEquals(Search.normalize("قناه"), Search.normalize("قنـــاة"))
    }

    @Test fun foldsArabicDigits() = assertEquals("mbc 2", Search.normalize("MBC ٢"))

    @Test fun emptyInputs() {
        assertEquals("", Search.normalize(""))
        assertEquals("", Search.normalize(" |-| "))
        assertNull(Search.ftsQuery(" |-| "))
    }

    @Test fun everyWordBecomesAQuotedPrefix() = assertEquals("\"bein*\" \"spo*\"", Search.ftsQuery("beIN Spo"))

    @Test fun ftsOperatorsAreTreatedAsText() = assertEquals("\"or*\" \"not*\"", Search.ftsQuery("OR NOT"))

    @Test fun quotesCannotBreakOutOfTheQuery() = assertEquals("\"a*\" \"b*\"", Search.ftsQuery("a\"b"))
}
