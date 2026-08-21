package com.harken.android.network

import com.harken.android.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors src/Harken.Mobile/Services/AppSettings.cs's TryValidate — same rules, same
// three rejection cases, so the two clients behave identically against the backend.
class AppSettingsValidationTest {

    @Test
    fun blankUrlIsInvalid() {
        assertFalse(AppSettings.isValid(""))
        assertFalse(AppSettings.isValid("   "))
    }

    @Test
    fun nonAbsoluteUrlIsInvalid() {
        assertFalse(AppSettings.isValid("localhost:5057"))
        assertFalse(AppSettings.isValid("not a url"))
    }

    @Test
    fun nonHttpSchemeIsInvalid() {
        assertFalse(AppSettings.isValid("ftp://192.168.1.101:5057"))
    }

    @Test
    fun validHttpUrlIsAccepted() {
        assertTrue(AppSettings.isValid("http://192.168.1.101:5057"))
        assertTrue(AppSettings.isValid("http://localhost:5057"))
        assertTrue(AppSettings.isValid("https://harken.example.com"))
    }
}
