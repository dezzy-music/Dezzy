/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Búsquedas recientes de «Solo videos», separadas por salto de línea (la más nueva primero). */
private val VideoRecentSearchesKey = stringPreferencesKey("video_feed_recent_searches")
private const val MaxRecentSearches = 8

/**
 * Búsqueda de videos de «Solo videos» (boceto B, como YouTube): resultados sólo de video
 * con paginación al llegar al final.
 */
@Stable
internal class VideoSearchResults(private val scope: CoroutineScope) {
    val items = mutableStateListOf<SongItem>()
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var query by mutableStateOf<String?>(null)
        private set

    private var continuation: String? = null
    private var job: Job? = null

    fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        if (text == query && (items.isNotEmpty() || loading)) return
        job?.cancel()
        query = text
        items.clear()
        continuation = null
        failed = false
        loadingMore = false
        loading = true
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                YouTube.search(text, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
            }
            if (query != text) return@launch
            loading = false
            if (result == null) {
                failed = true
                return@launch
            }
            items.addAll(result.items.filterIsInstance<SongItem>().distinctBy { it.id })
            continuation = result.continuation
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loading || loadingMore) return
        val requestQuery = query
        loadingMore = true
        job = scope.launch {
            val page = withContext(Dispatchers.IO) { YouTube.searchContinuation(token).getOrNull() }
            if (query != requestQuery) return@launch
            loadingMore = false
            if (page == null) {
                continuation = null
                return@launch
            }
            val known = items.mapTo(HashSet()) { it.id }
            items.addAll(page.items.filterIsInstance<SongItem>().filter { known.add(it.id) })
            continuation = page.continuation.takeIf { it != token }
        }
    }

    fun retry() {
        val q = query ?: return
        query = null
        search(q)
    }
}

/** Lista de búsquedas recientes guardada en DataStore. */
@Composable
internal fun rememberVideoRecentSearches(): Pair<List<String>, (String?) -> Unit> {
    var raw by rememberPreference(VideoRecentSearchesKey, "")
    val list = remember(raw) { raw.split('\n').filter { it.isNotBlank() } }
    // null = borrar todo; texto = añadir arriba (sin repetir).
    val update: (String?) -> Unit = { q ->
        raw = if (q == null) {
            ""
        } else {
            (listOf(q.trim()) + list.filterNot { it.equals(q.trim(), ignoreCase = true) })
                .take(MaxRecentSearches)
                .joinToString("\n")
        }
    }
    return list to update
}

/** Campo de búsqueda que sustituye a la barra superior mientras se busca. */
@Composable
internal fun VideoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = modifier
            .height(42.dp)
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Buscar videos", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) {
                        keyboard?.hide()
                        onSearch(query)
                    }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = "Borrar texto",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Lo que se ve mientras se escribe: búsquedas recientes y sugerencias de YouTube.
 * Tocar una fila busca; la flecha ↖ sólo la copia al campo para seguir escribiendo.
 */
@Composable
internal fun VideoSearchSuggestions(
    query: String,
    recent: List<String>,
    onClearRecent: () -> Unit,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = remember { mutableStateListOf<String>() }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions.clear()
            return@LaunchedEffect
        }
        delay(220)
        val result = withContext(Dispatchers.IO) {
            YouTube.searchSuggestions(query.trim()).getOrNull()?.queries.orEmpty()
        }
        suggestions.clear()
        suggestions.addAll(result.take(8))
    }
    val recentShown = if (query.isBlank()) recent
    else recent.filter { it.contains(query.trim(), ignoreCase = true) }.take(3)

    LazyColumn(modifier = modifier) {
        if (recentShown.isNotEmpty()) {
            item(key = "recent_header") {
                SectionLabel(
                    text = "Búsquedas recientes",
                    action = if (query.isBlank()) "Borrar" else null,
                    onAction = onClearRecent,
                )
            }
            items(recentShown, key = { "r_$it" }) { text ->
                SuggestionRow(R.drawable.history, text, query, onSearch, onFill)
            }
        }
        val fresh = suggestions.filterNot { s -> recentShown.any { it.equals(s, ignoreCase = true) } }
        if (fresh.isNotEmpty()) {
            item(key = "sugg_header") { SectionLabel("Sugerencias") }
            items(fresh, key = { "s_$it" }) { text ->
                SuggestionRow(R.drawable.search, text, query, onSearch, onFill)
            }
        }
        if (recentShown.isEmpty() && fresh.isEmpty() && query.isBlank()) {
            item(key = "hint") {
                Text(
                    text = "Busca cualquier video: videoclips, directos, covers…",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    iconRes: Int,
    text: String,
    query: String,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val typed = query.trim()
    val label = buildAnnotatedString {
        if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(0, typed.length)) }
            append(text.substring(typed.length))
        } else {
            append(text)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSearch(text) }
            .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onFill(text) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_top_left),
                contentDescription = "Completar con «$text»",
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Filas de resultados vacíos / error / cargando, compartidas con la lista de resultados. */
internal fun LazyListScope.videoSearchStatus(
    results: VideoSearchResults,
) {
    when {
        results.loading -> item(key = "search_loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        results.items.isEmpty() -> item(key = "search_empty") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (results.failed) "No se pudo buscar. Revisa la conexión." else "Sin videos para «${results.query.orEmpty()}»",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(enabled = results.failed) { results.retry() },
                )
            }
        }
    }
}
