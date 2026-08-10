/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * Persisted-query (sha256) hashes for Spotify's internal GraphQL API
 * (api-partner.spotify.com/pathfinder). The public REST Web API
 * (api.spotify.com v1) is hard-throttled (HTTP 429) for web-player /
 * sp_dc tokens, so all library/playlist reads go through pathfinder — the
 * same API the real open.spotify.com web player uses.
 *
 * Approach mirrors Metrolist/Meld: ship current hashes hardcoded, keep the
 * PREVIOUS hash as a fallback for when Spotify rotates one, and expose
 * updateHashes() so a remote registry can refresh them without an app update.
 */

package com.dezzy.music.spotify

import java.util.concurrent.ConcurrentHashMap

object SpotifyHashProvider {

    private data class Entry(val hash: String, val previousHash: String?)

    private val hashes = ConcurrentHashMap<String, Entry>()

    init {
        // Verified hashes (registry snapshot 2026-07-11). `previous` covers the
        // last rotation so an in-flight change self-heals via the fallback.
        put("profileAttributes",
            "b197b5adb4b761690f76ad9d9fb278c14c14e7331f357c04a56e7001af7106e0",
            "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced")
        put("libraryV3",
            "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
            "2de10199b2441d6e4ae875f27d2db361020c399fb10b03951120223fbed10b08")
        put("fetchPlaylist",
            "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4",
            "32b05e92e438438408674f95d0fdad8082865dc32acd55bd97f5113b8579092b")
        put("fetchLibraryTracks",
            "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            null)
        // Extracted live from web-player.a9552620.js (2026-07-11). Used to resolve an
        // arbitrary song → Spotify track id so Canvas works for songs NOT preloaded.
        put("searchSuggestions",
            "556f5a15b2fdd3a7113ffd377ad9805e38a3a27b8bb1ca7d6d76bad54aa8ee12",
            null)
    }

    private fun put(op: String, hash: String, previous: String?) {
        hashes[op] = Entry(hash, previous)
    }

    fun getHash(operationName: String): String =
        hashes[operationName]?.hash
            ?: error("No hash registered for Spotify GQL operation: $operationName")

    fun getPreviousHash(operationName: String): String? =
        hashes[operationName]?.previousHash

    /** Bulk-refresh from a remote registry (op -> [current, previous]). */
    fun updateHashes(remote: Map<String, Pair<String, String?>>) {
        remote.forEach { (op, pair) ->
            if (hashes.containsKey(op)) hashes[op] = Entry(pair.first, pair.second)
        }
    }
}
