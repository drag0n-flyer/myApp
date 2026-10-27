package org.example.app.store

import kotlin.test.Test
import kotlin.test.assertEquals

class TextFoldTest {
    @Test fun foldsWidthCaseAndAccents() = assertEquals("cafe", TextFold.fold("Ｃafé"))
    @Test fun foldsCombiningSequence() = assertEquals("cafe", TextFold.fold("CAFÉ"))
    @Test fun keepsCjkAndHangulUnchanged() {
        assertEquals("東京タワー", TextFold.fold("東京タワー"))
        assertEquals("한국어", TextFold.fold("한국어"))
    }
    @Test fun keepsIndicVowelSigns() = assertEquals("नमस्ते", TextFold.fold("नमस्ते"))
    @Test fun stripsArabicMarksKeepsLetters() = assertEquals("مرحبا", TextFold.fold("مَرْحَبًا"))
    @Test fun halfWidthKatakanaBecomesFullWidth() = assertEquals("タワー", TextFold.fold("ﾀﾜｰ"))
}
