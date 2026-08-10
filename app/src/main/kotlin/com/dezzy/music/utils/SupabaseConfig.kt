/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.utils

/**
 * Shared Supabase connection used by the app's native backend features (Music Map presence,
 * direct messaging, friends, profiles). The anon key is public by design — Row Level Security
 * policies on the server protect the data.
 */
object SupabaseConfig {
    const val URL = "https://qbjkiqbypctpcamnappl.supabase.co"
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFiamtpcWJ5cGN0cGNhbW5hcHBsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk3MjIzNTIsImV4cCI6MjA4NTI5ODM1Mn0.gipz-gAehw-fkalXXT4xKJLiNMLwvkVGF2cuPLxIr2g"

    const val REST = "$URL/rest/v1"
}
