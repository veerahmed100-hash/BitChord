package com.music.bitchord.ui.replay

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.DayStats
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.rememberArtworkColors
import com.music.bitchord.ui.theme.AccentRed

/** Whether the Replay page is being shown in dark mode (vs light). */
@Composable
private fun isDarkMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The Replay page: four headline cards, four charts and a way to play or share
 * the lot.
 *
 * ## Two ways in to the same numbers
 *
 * The headline cards also live on the Library page, where each one opens this
 * page at its matching chart. They remain here as Replay's own visual summary;
 * the ranked lists below answer the follow-up questions those cards provoke.
 *
 * ## Why the page is washed in the top song's colours
 *
 * The mesh backdrop is the player's, and that is the point: this page is about
 * one particular year of listening, and running it in the colours of the record
 * that defined that year ties the two together without a line of copy saying so.
 * It also means two people's Replays do not look alike, which a fixed brand
 * gradient could never manage.
 */
@Composable
fun ReplayScreen(
    state: ReplayState,
    /** The signed-in account name embossed on the Replay cards. */
    holder: String,
    onPeriodChange: (ReplayPeriod) -> Unit,
    onOpenStory: (ReplayStoryPage) -> Unit,
    onPlaySong: (Song) -> Unit,
    /**
     * Opens the artist's page. The browse id is null for most rows — an artist
     * is counted by name, and the id only rides along when the track that
     * credited them carried one — so the caller has to be able to find the page
     * from the name alone. Same for the album below.
     */
    onOpenArtist: (String?, String) -> Unit,
    onOpenAlbum: (String?, String, String?, String?) -> Unit,
    onShare: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    landingPage: ReplayStoryPage = ReplayStoryPage.INTRO,
) {
    val context = LocalContext.current
    val summary = state.summary
    ReplaySources.init(context.applicationContext)
    val sourceScope = rememberCoroutineScope()
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var showSyncSheet by rememberSaveable { mutableStateOf(false) }

    // Songs that only exist in Last.fm (never played here) carry a made-up id.
    // Tapping one looks the song up on YouTube Music and plays that.
    val playSong: (Song) -> Unit = { song ->
        if (song.videoId.startsWith("lf:")) {
            sourceScope.launch {
                val hit = YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS)
                    .getOrNull()
                    ?.mapNotNull {
                        when (it) {
                            is SearchResult.Track -> it.song
                            is SearchResult.TopTrack -> it.song
                            else -> null
                        }
                    }
                    ?.firstOrNull()
                if (hit != null) onPlaySong(hit)
            }
        } else {
            onPlaySong(song)
        }
    }

    // Catch up quietly when the page opens, then rebuild the page whenever an
    // import or sync has changed the listening on disk.
    val latestState by rememberUpdatedState(state)
    val changeTick by ReplaySources.changeTick.collectAsState()
    val firstTick = remember { ReplaySources.changeTick.value }
    LaunchedEffect(Unit) { ReplaySources.autoRun() }
    LaunchedEffect(changeTick) {
        if (changeTick == firstTick) return@LaunchedEffect
        // The numbers are held by the caller and only reread when the period
        // changes, so step to another period and straight back.
        val back = latestState.period
        onPeriodChange(if (back == ReplayPeriod.ALL_TIME) ReplayPeriod.THIS_YEAR else ReplayPeriod.ALL_TIME)
        try {
            delay(300)
        } finally {
            onPeriodChange(back)
        }
    }

    val leadArtwork = summary?.songs?.firstOrNull()?.song?.thumbnailUrl
    val palette = rememberArtworkColors(leadArtwork)
    val topSongs = stringResource(R.string.top_songs)
    val topArtists = stringResource(R.string.top_artists)
    val topAlbums = stringResource(R.string.top_albums)
    val topGenres = stringResource(R.string.top_genres)

    // Library cards open this same page, but category cards should land on the
    // corresponding chart rather than making the user find it again. Wait for
    // a loaded summary because the loading/empty layouts do not contain these
    // keyed sections yet.
    //
    // Once only per opening, though. Back from an artist or album opened off
    // this page composes it again, and landing a second time threw away
    // wherever it had been scrolled to. Saveable, so it outlives that trip —
    // the page's state is kept while it is on the back stack.
    var landed by rememberSaveable(landingPage, state.period) { mutableStateOf(false) }
    LaunchedEffect(landingPage, state.period, summary == null) {
        if (landed) return@LaunchedEffect
        val target = when {
            landingPage == ReplayStoryPage.INTRO || landingPage == ReplayStoryPage.MINUTES -> 0
            summary == null || summary.isEmpty -> 0
            else -> replayChartIndex(summary, landingPage)
        }
        listState.scrollToItem(target)
        if (summary != null) landed = true
    }

    Box(modifier.fillMaxSize()) {
        MeshGradientBackground(palette = palette, trackKey = leadArtwork, animated = false)
        // The mesh is built to sit behind a player, where the only thing over it
        // is a handful of large controls. A page of ranked lists needs a good
        // deal more separation than that, so most of it is put back under ink.
        val dark = isDarkMode()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.88f),
                        ) else listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.65f),
                            Color.White.copy(alpha = 0.82f),
                        ),
                    ),
                ),
        )

        val textColor = if (dark) Color.White else Color.Black
        val textVariant = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("heading") {
                Heading(
                    state = state,
                    onPeriodChange = onPeriodChange,
                    dark = dark,
                    onImport = { showImportSheet = true },
                    onSync = { showSyncSheet = true },
                )
            }

            when {
                state.loading && summary == null -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(64.dp), Alignment.Center) {
                        CircularProgressIndicator(color = textColor.copy(alpha = 0.6f))
                    }
                }
                summary == null || summary.isEmpty -> item("empty") { EmptyReplay(state.period, dark) }
                else -> {
                    item("cards") {
                        ReplayCardRow(
                            cards = summary.cards(context),
                            holder = holder,
                            memberSince = state.memberSince,
                            onCardClick = onOpenStory,
                        )
                    }
                    item("open") {
                        ReplayActionRow(BitChordIcons.Play, stringResource(R.string.play_your_replay), dark) {
                            onOpenStory(ReplayStoryPage.INTRO)
                        }
                    }

                    chart(
                        key = "songs",
                        title = topSongs,
                        rows = summary.songRows(CHART_LENGTH),
                        dark = dark,
                        onClick = { index ->
                            summary.songs.getOrNull(index)?.let { playSong(it.song) }
                        },
                    )
                    chart(
                        key = "artists",
                        title = topArtists,
                        rows = summary.artistRows(CHART_LENGTH),
                        dark = dark,
                        circular = true,
                        onClick = { index ->
                            val artist = summary.artists.getOrNull(index) ?: return@chart
                            onOpenArtist(artist.browseId, artist.title)
                        },
                    )
                    chart(
                        key = "albums",
                        title = topAlbums,
                        rows = summary.albumRows(CHART_LENGTH),
                        dark = dark,
                        onClick = { index ->
                            val album = summary.albums.getOrNull(index) ?: return@chart
                            onOpenAlbum(album.browseId, album.title, album.subtitle, album.artworkUrl)
                        },
                    )
                    if (summary.genres.isNotEmpty()) {
                        chart(
                            key = "genres",
                            title = topGenres,
                            rows = summary.genreRows(CHART_LENGTH),
                            dark = dark,
                            onClick = {},
                        )
                    } else if (ArtistFacts.genresAvailable) {
                        item("genres-pending") {
                            Note(
                                text = stringResource(R.string.genres_pending),
                                dark = dark,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp),
                            )
                        }
                    }

                    item("habits") { Habits(summary, dark) }
                    item("share") {
                        ReplayActionRow(
                            Icons.Rounded.IosShare,
                            stringResource(R.string.share_my_replay),
                            dark,
                            onShare,
                        )
                    }
                }
            }
        }

        if (showImportSheet) ReplayImportSheet(onDismiss = { showImportSheet = false })
        if (showSyncSheet) ReplaySyncSheet(onDismiss = { showSyncSheet = false })
    }
}

// ── Heading ─────────────────────────────────────────────────────────────────

@Composable
private fun Heading(
    state: ReplayState,
    onPeriodChange: (ReplayPeriod) -> Unit,
    dark: Boolean,
    onImport: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = stringResource(R.string.replay),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = state.summary?.localizedLabel(context) ?: state.period.localizedChip(context),
            style = MaterialTheme.typography.titleMedium,
            color = textColor.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(14.dp))
        PeriodPicker(state.period, onPeriodChange, dark)
        Spacer(Modifier.height(10.dp))
        SourcesBar(dark, onImport, onSync)
        Spacer(Modifier.height(18.dp))
    }
}

/**
 * The four stretches a Replay can cover.
 *
 * Weeks and months rather than a date range, because that is the granularity the
 * listening is actually kept at — see [com.music.bitchord.data.stats.ListeningStats].
 * A "last 30 days" chip would have to be answered from monthly totals, which
 * would make it a lie for the first thirty days of every month.
 */
@Composable
private fun PeriodPicker(selected: ReplayPeriod, onSelect: (ReplayPeriod) -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    // Four equal shares of the full width, each held to one line. Sized to
    // their labels instead, the chips ran past the edge on a narrow phone and
    // the last was squeezed into whatever was left, one letter to a line.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(textColor.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReplayPeriod.entries.forEach { period ->
            val active = period == selected
            val background by animateColorAsState(
                if (active) textColor.copy(alpha = 0.92f) else Color.Transparent,
                tween(160),
                label = "periodChip",
            )
            Text(
                text = period.localizedTab(context),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = if (active) if (dark) Color.Black else Color.White else textColor.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { onSelect(period) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

// ── The cards ───────────────────────────────────────────────────────────────

/**
 * The wallet of cards, wherever it is drawn.
 *
 * It lives on the Library page rather than here — see [ReplayScreen]'s note on
 * where each half of Replay belongs — but it is defined alongside the page it
 * summarises, because the two have to keep saying the same thing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReplayCardRow(
    cards: List<ReplayHeroCard>,
    holder: String,
    memberSince: String?,
    onCardClick: (ReplayStoryPage) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
) {
    val rowState = rememberLazyListState()
    LazyRow(
        modifier = modifier,
        state = rowState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = rowState),
    ) {
        items(cards, key = { it.label }) { card ->
            ReplayCreditCard(
                label = card.label,
                value = card.value,
                detail = card.detail,
                artworkUrl = card.artworkUrl,
                holder = holder,
                memberSince = memberSince,
                onClick = { onCardClick(card.page) },
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

/**
 * Lazy-list index of a chart title after the heading, card row and Replay action.
 * Each preceding chart contributes its title, visible rows and trailing gap.
 */
private fun replayChartIndex(summary: ReplaySummary, page: ReplayStoryPage): Int {
    val songs = summary.songRows(CHART_LENGTH)
    val artists = summary.artistRows(CHART_LENGTH)
    fun span(rows: List<ReplayRow>): Int = if (rows.isEmpty()) 0 else rows.size + 2

    return when (page) {
        ReplayStoryPage.SONGS -> 3
        ReplayStoryPage.ARTISTS -> 3 + span(songs)
        ReplayStoryPage.ALBUMS -> 3 + span(songs) + span(artists)
        else -> 0
    }
}

/**
 * The two things this page can do, drawn the same way.
 *
 * They sit at either end of it and are the same kind of act — open the Replay,
 * send the Replay — so they have no business looking unalike. The share button
 * used to be a filled red slab, which on a page with no destructive control on
 * it was the loudest thing there and read like one.
 */
@Composable
private fun ReplayActionRow(icon: ImageVector, label: String, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(textColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Charts ──────────────────────────────────────────────────────────────────

private fun LazyListScope.chart(
    key: String,
    title: String,
    rows: List<ReplayRow>,
    dark: Boolean,
    onClick: (Int) -> Unit,
    circular: Boolean = false,
) {
    if (rows.isEmpty()) return
    item("$key-title") { SectionTitle(title, dark) }
    items(rows, key = { "$key-${it.key}" }) { row ->
        ReplayChartRow(row, circular, dark) { onClick(row.rank - 1) }
    }
    item("$key-gap") { Spacer(Modifier.height(20.dp)) }
}

@Composable
private fun SectionTitle(text: String, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W800,
        color = textColor,
        modifier = Modifier.padding(
            start = PAGE_GUTTER + 4.dp,
            end = PAGE_GUTTER + 4.dp,
            top = 8.dp,
            bottom = 10.dp,
        ),
    )
}

@Composable
private fun ReplayChartRow(row: ReplayRow, circular: Boolean, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    val textVariant = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
    val shape = if (circular) CircleShape else RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(row.rank, AccentRed)
        if (row.artworkUrl != null) {
            AsyncImage(
                model = row.artworkUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(shape),
            )
        } else {
            InitialTile(row.title, 48.dp, shape)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W600,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(row.subtitle, formatListening(LocalContext.current, row.ms))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = textVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.plays > 0) {
            Text(
                text = row.plays.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.45f),
            )
        }
    }
}

// ── Tail ────────────────────────────────────────────────────────────────────

@Composable
private fun Habits(summary: ReplaySummary, dark: Boolean) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        SectionTitleInline(stringResource(R.string.listening_shape), dark)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.songs), summary.distinctSongs.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.artists), summary.distinctArtists.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.albums), summary.distinctAlbums.toString(), dark, Modifier.weight(1f))
        }
        summary.peakHour?.let {
            Spacer(Modifier.height(10.dp))
            Note(stringResource(R.string.listen_most_around, formatHour(context, it)), dark)
        }
        summary.busiestDay?.let {
            Spacer(Modifier.height(6.dp))
            Note(
                stringResource(
                    R.string.biggest_day_note,
                    formatDay(context, it),
                    formatListening(context, summary.busiestDayMs),
                ),
                dark,
            )
        }
    }
}

@Composable
private fun SectionTitleInline(text: String, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W800,
        color = textColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(textColor.copy(alpha = 0.09f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.55f),
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Note(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

@Composable
private fun EmptyReplay(period: ReplayPeriod, dark: Boolean) {
    val emptyColor = if (dark) Color.White else Color.Black
    val emptyVariant = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = BitChordIcons.Clock,
            contentDescription = null,
            tint = emptyVariant,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.not_enough_listening),
            style = MaterialTheme.typography.titleLarge,
            color = emptyColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (period) {
                ReplayPeriod.THIS_WEEK -> stringResource(R.string.not_enough_this_week)
                ReplayPeriod.THIS_MONTH -> stringResource(R.string.not_enough_this_month)
                else -> stringResource(R.string.replay_empty_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = emptyColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How far down each chart the page goes.
 *
 * Five. Ten was the first guess and it made the page a scroll of forty rows
 * whose second half nobody has an opinion about — a top five is a result, and a
 * top ten is a list.
 */
private const val CHART_LENGTH = 5

// ═══════════════════════════════════════════════════════════════════════════
//  Sources: Last.fm import and GitHub sync
//
//  Everything below lives in this one file on purpose. It never edits the
//  numbers Replay already keeps; it adds to them through ListeningStats'
//  public export/import, and remembers exactly what it added (the "overlay")
//  so that it can take it out again before every recompute. That is what
//  stops anything being counted twice, however often it runs.
// ═══════════════════════════════════════════════════════════════════════════

private const val RS_TAG = "ReplaySources"

/** Two entries for the same song closer together than this are one play. */
private const val RS_DEDUPE_WINDOW_MS = 120_000L

/** ListeningStats keeps this many calendar months; nothing older is worth adding. */
private const val RS_KEEP_MONTHS = 36L

private const val RS_DEFAULT_LENGTH_MS = 210_000L

private val RS_BRACKETS = Regex("""[(\[{][^)\]}]*[)\]}]""")
private val RS_FEATURING = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
private val RS_NOT_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val RS_ARTIST_SPLIT = Regex("""\s*(,|&|;|/|\s+x\s+|\s+and\s+)\s*""", RegexOption.IGNORE_CASE)

/** Lower-cases and strips "(Remastered)", "feat. X", punctuation and spaces, for comparing only. */
private fun rsNormalize(text: String): String {
    var s = text.lowercase(Locale.ROOT)
    s = RS_BRACKETS.replace(s, " ")
    s = RS_FEATURING.replace(s, "")
    val squashed = RS_NOT_ALNUM.replace(s, "")
    return squashed.ifEmpty { text.trim().lowercase(Locale.ROOT) }
}

private fun rsNormArtist(artist: String): String =
    rsNormalize(artist.split(RS_ARTIST_SPLIT).firstOrNull().orEmpty().ifBlank { artist })

/** Identity of a song for matching across sources: title + lead artist, normalised. */
private fun rsMatchKey(title: String, artist: String): String =
    rsNormalize(title) + "|" + rsNormArtist(artist)

/** The same, from a stored "title|artist" key. */
private fun rsMatchKeyOfTrackKey(trackKey: String): String {
    val cut = trackKey.lastIndexOf('|')
    if (cut < 0) return rsNormalize(trackKey) + "|"
    return rsNormalize(trackKey.substring(0, cut)) + "|" + rsNormArtist(trackKey.substring(cut + 1))
}

private fun rsTrackKey(title: String, artist: String): String =
    "${title.trim()}|${artist.trim()}".lowercase(Locale.ROOT)

private fun rsAlbumKey(id: String?, name: String, sub: String): String =
    id ?: (name.lowercase(Locale.ROOT) + Char(31).toString() + sub.lowercase(Locale.ROOT))

private fun rsSha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun rsSha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Known plays, used to spot near-identical new ones. */
private class RsPlayIndex {
    private val byKey = HashMap<String, MutableList<Long>>()

    fun add(trackKey: String, playedAtMillis: Long) {
        byKey.getOrPut(rsMatchKeyOfTrackKey(trackKey)) { ArrayList() }.add(playedAtMillis)
    }

    fun addIfNew(trackKey: String, playedAtMillis: Long): Boolean {
        val times = byKey.getOrPut(rsMatchKeyOfTrackKey(trackKey)) { ArrayList() }
        if (times.any { abs(it - playedAtMillis) < RS_DEDUPE_WINDOW_MS }) return false
        times.add(playedAtMillis)
        return true
    }
}

// ── File shapes ─────────────────────────────────────────────────────────────

/** One play. Same short keys the LastWave replay patch writes, so both apps read each other's files. */
@Serializable
private data class RsEv(
    val k: String,
    val t: String,
    val a: String,
    val al: String? = null,
    val u: String? = null,
    val g: String = "",
    val p: Long,
    val l: Long = 0L,
)

/**
 * What one phone puts on GitHub. `events` is the same as the LastWave patch's
 * file; `native` is this phone's own BitChord listening (month buckets) and is
 * ignored by readers that don't know it.
 */
@Serializable
private data class RsRemoteFile(
    val v: Int = 1,
    val device: String = "",
    val app: String = "",
    val events: List<RsEv> = emptyList(),
    val native: List<StoredBucket> = emptyList(),
)

@Serializable
private data class RsLedger(val events: List<RsEv> = emptyList())

/** [overlay] is everything added on top of native listening; [own] is native listening alone. */
@Serializable
private data class RsApplied(
    val overlay: List<StoredBucket> = emptyList(),
    val own: List<StoredBucket> = emptyList(),
)

@Serializable
private data class RsDur(val d: Long = 0L, val al: String? = null, val u: String? = null, val at: Long = 0L)

@Serializable
private data class RsDurFile(val items: Map<String, RsDur> = emptyMap())

private class RsException(message: String, val code: Int = 0) : Exception(message)

private class RsResult(val changed: Boolean, val extras: Int)

private enum class RsImportScope(val label: String, val hint: String) {
    NEW("New plays only", "Everything since your last import"),
    MONTH("Last 30 days", "Quick, good for a first try"),
    YEAR("This year", "From 1 January"),
    ALL("All history", "Every scrobble you have (last 3 years are kept). Can take a while."),
}

private sealed interface RsImportStatus {
    data object Idle : RsImportStatus
    data class Running(val step: String, val done: Int, val total: Int) : RsImportStatus
    data class Done(val atMillis: Long, val read: Int, val added: Int, val extras: Int) : RsImportStatus
    data class Failed(val message: String) : RsImportStatus
}

private sealed interface RsSyncStatus {
    data object Off : RsSyncStatus
    data object Ready : RsSyncStatus
    data object Syncing : RsSyncStatus
    data class Done(val atMillis: Long, val extras: Int) : RsSyncStatus
    data class Failed(val message: String) : RsSyncStatus
}

private class RsSc(val title: String, val artist: String, val album: String?, val art: String?, val uts: Long)

// ── Month model ─────────────────────────────────────────────────────────────

/** A month of listening in a form that can be added and subtracted. */
private class RsMonth(val key: String) {
    val tracks = LinkedHashMap<String, TrackEntry>()
    val artists = LinkedHashMap<String, NameEntry>()
    val albums = LinkedHashMap<String, NameEntry>()
    val hours = LongArray(24)
    val days = HashMap<Int, Long>()
    val trackDays = HashMap<String, DayStats>()
    val artistDays = HashMap<String, DayStats>()
    val albumDays = HashMap<String, DayStats>()
}

private fun rsAddDay(map: MutableMap<String, DayStats>, key: String, s: DayStats, sign: Int) {
    val x = map.getOrPut(key) { DayStats() }
    x.ms += sign * s.ms
    x.plays += sign * s.plays
}

private fun RsMonth.addTrack(e: TrackEntry, sign: Int) {
    val t = tracks.getOrPut(e.id) { TrackEntry(id = e.id, title = e.title, artist = e.artist) }
    t.ms += sign * e.ms
    t.plays += sign * e.plays
    if (sign > 0) {
        t.last = maxOf(t.last, e.last)
        if (t.album == null) t.album = e.album
        if (t.albumId == null) t.albumId = e.albumId
        if (t.artistId == null) t.artistId = e.artistId
        if (t.art == null) t.art = e.art
    }
    e.days.forEach { (d, s) -> rsAddDay(t.days, d, s, sign) }
}

private fun RsMonth.addArtist(e: NameEntry, sign: Int) {
    val lead = ListeningStats.primaryArtist(e.name) ?: e.name
    val t = artists.getOrPut(lead.lowercase(Locale.ROOT)) { NameEntry(name = lead, sub = e.sub) }
    t.ms += sign * e.ms
    t.plays += sign * e.plays
    if (sign > 0) {
        if (t.art == null) t.art = e.art
        if (t.id == null) t.id = e.id
    }
    e.days.forEach { (d, s) -> rsAddDay(t.days, d, s, sign) }
}

private fun RsMonth.addAlbum(e: NameEntry, sign: Int) {
    val t = albums.getOrPut(rsAlbumKey(e.id, e.name, e.sub.orEmpty())) { NameEntry(name = e.name, sub = e.sub) }
    t.ms += sign * e.ms
    t.plays += sign * e.plays
    if (sign > 0) {
        if (t.art == null) t.art = e.art
        if (t.id == null) t.id = e.id
    }
    e.days.forEach { (d, s) -> rsAddDay(t.days, d, s, sign) }
}

private fun RsMonth.addStored(b: StoredBucket, sign: Int) {
    b.tracks.forEach { addTrack(it, sign) }
    b.artists.forEach { addArtist(it, sign) }
    b.albums.forEach { addAlbum(it, sign) }
    for (i in 0 until 24) hours[i] += sign * b.hours.getOrElse(i) { 0L }
    b.days.forEach { (d, ms) -> days[d] = (days[d] ?: 0L) + sign * ms }
    b.trackDays?.forEach { (k, s) -> rsAddDay(trackDays, k, s, sign) }
    b.artistDays?.forEach { (k, s) -> rsAddDay(artistDays, k, s, sign) }
    b.albumDays?.forEach { (k, s) -> rsAddDay(albumDays, k, s, sign) }
}

private fun RsMonth.toStored(): StoredBucket = StoredBucket(
    month = key,
    tracks = tracks.values.toList(),
    artists = artists.map { (k, v) -> v.copy(key = k) },
    albums = albums.map { (k, v) -> v.copy(key = k) },
    hours = hours.toList(),
    days = days.toMap(),
    trackDays = trackDays.toMap(),
    artistDays = artistDays.toMap(),
    albumDays = albumDays.toMap(),
)

private fun RsMonth.addMonth(o: RsMonth, sign: Int) = addStored(o.toStored(), sign)

/** After a subtraction: nothing negative, and nothing left that has no listening in it. */
private fun RsMonth.clamp() {
    fun MutableMap<String, DayStats>.tidy() {
        values.forEach { it.ms = maxOf(0L, it.ms); it.plays = maxOf(0, it.plays) }
        values.removeAll { it.ms <= 0L && it.plays <= 0 }
    }
    tracks.values.forEach { it.ms = maxOf(0L, it.ms); it.plays = maxOf(0, it.plays); it.days.tidy() }
    tracks.values.removeAll { it.ms <= 0L && it.plays <= 0 }
    artists.values.forEach { it.ms = maxOf(0L, it.ms); it.plays = maxOf(0, it.plays); it.days.tidy() }
    artists.values.removeAll { it.ms <= 0L && it.plays <= 0 }
    albums.values.forEach { it.ms = maxOf(0L, it.ms); it.plays = maxOf(0, it.plays); it.days.tidy() }
    albums.values.removeAll { it.ms <= 0L && it.plays <= 0 }
    for (i in 0 until 24) if (hours[i] < 0L) hours[i] = 0L
    days.values.removeAll { it <= 0L }
    trackDays.tidy()
    artistDays.tidy()
    albumDays.tidy()
}

/** Adds one play from an event, the way ListeningStats.record would have on the day it happened. */
private fun RsMonth.addEvent(
    e: RsEv,
    trackId: String,
    info: TrackEntry?,
    albumHit: NameEntry?,
    at: ZonedDateTime,
) {
    val ms = e.l.coerceAtLeast(0L)
    val dayKey = "%04d-%02d-%02d".format(Locale.ROOT, at.year, at.monthValue, at.dayOfMonth)
    val title = info?.title?.ifBlank { null } ?: e.t
    val credit = info?.artist?.ifBlank { null } ?: e.a
    val art = info?.art ?: e.u

    val track = tracks.getOrPut(trackId) {
        TrackEntry(
            id = trackId,
            title = title,
            artist = credit,
            album = info?.album ?: e.al,
            albumId = info?.albumId,
            artistId = info?.artistId,
            art = art,
        )
    }
    track.ms += ms
    track.plays++
    track.last = maxOf(track.last, e.p)
    rsAddDay(trackDays, dayKey, DayStats(ms, 1), 1)
    rsAddDay(track.days, dayKey, DayStats(ms, 1), 1)

    val lead = ListeningStats.primaryArtist(credit) ?: credit
    if (lead.isNotBlank()) {
        val artist = artists.getOrPut(lead.lowercase(Locale.ROOT)) { NameEntry(name = lead, art = art) }
        artist.ms += ms
        artist.plays++
        if (artist.id == null) artist.id = info?.artistId
        rsAddDay(artistDays, dayKey, DayStats(ms, 1), 1)
        rsAddDay(artist.days, dayKey, DayStats(ms, 1), 1)
    }

    val albumName = (albumHit?.name ?: info?.album ?: e.al)?.trim()?.takeIf { it.isNotEmpty() }
    if (albumName != null) {
        val albumId = info?.albumId ?: albumHit?.id
        val sub = albumHit?.sub ?: credit
        val album = albums.getOrPut(rsAlbumKey(albumId, albumName, sub)) {
            NameEntry(name = albumName, sub = sub, art = art, id = albumId)
        }
        album.ms += ms
        album.plays++
        rsAddDay(albumDays, dayKey, DayStats(ms, 1), 1)
        rsAddDay(album.days, dayKey, DayStats(ms, 1), 1)
    }

    hours[at.hour] += ms
    days[at.dayOfMonth] = (days[at.dayOfMonth] ?: 0L) + ms
}

private fun rsFingerprint(buckets: List<StoredBucket>): Long {
    var sum = 0L
    buckets.forEach { b ->
        b.tracks.forEach { sum += it.ms + it.plays * 1000L + 1L }
        b.albums.forEach { sum += it.ms / 2 + it.plays }
    }
    return sum
}

private fun rsMonthTotals(buckets: List<StoredBucket>): Map<String, Long> =
    buckets.associate { b -> b.month to b.tracks.sumOf { it.ms + it.plays * 1000L } }

/**
 * True when the listening on disk has less in it than what this code last
 * wrote there, which only happens when something else replaced it (a backup
 * restore). Native listening only ever grows.
 */
private fun rsLooksRestored(cur: List<StoredBucket>, expected: List<StoredBucket>): Boolean {
    val cutoff = YearMonth.now().minusMonths(RS_KEEP_MONTHS - 1)
    val now = rsMonthTotals(cur)
    val before = rsMonthTotals(expected)
    return before.any { (month, total) ->
        val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return@any false
        !ym.isBefore(cutoff) && total > 5_000L && (now[month] ?: 0L) < total * 0.98 - 5_000.0
    }
}

// ── The engine ──────────────────────────────────────────────────────────────

private object ReplaySources {

    private const val K_REPO = "repo"
    private const val K_TOKEN = "token"
    private const val K_DEVICE = "device_id"
    private const val K_HASH = "own_file_hash"
    private const val K_SYNC_OK = "sync_last_ok"
    private const val K_SYNC_TRY = "sync_last_try"
    private const val K_SHA = "sha_"
    private const val K_LAST_TS = "import_last_scrobble_ts"
    private const val K_IMPORT_TRY = "import_last_try"
    private const val FOLDER = "replay"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    private const val PAGE_SIZE = 200
    private const val OVERLAP_SEC = 300L
    private const val ITUNES_GAP_MS = 1_200L
    private const val RETRY_UNKNOWN_MS = 3L * 24L * 3600L * 1000L
    private const val NO_ART_HASH = "2a96cbd8b46e442fc41c2b86b821562f"
    private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val ready: Boolean get() = this::dir.isInitialized

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mergeMutex = Mutex()
    private val syncMutex = Mutex()
    private val importing = AtomicBoolean(false)

    @Volatile
    private var importJob: Job? = null

    @Volatile
    private var lastITunesAt = 0L

    private val http: OkHttpClient by lazy { Http.client.newBuilder().cache(null).build() }
    private val fast: OkHttpClient by lazy {
        Http.client.newBuilder().cache(null).callTimeout(15, TimeUnit.SECONDS).build()
    }

    val syncConfig = MutableStateFlow<Pair<String, String>?>(null)
    val syncStatus = MutableStateFlow<RsSyncStatus>(RsSyncStatus.Off)
    val importStatus = MutableStateFlow<RsImportStatus>(RsImportStatus.Idle)

    /** Bumped whenever the listening on disk was changed, so the page can rebuild. */
    val changeTick = MutableStateFlow(0)

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            prefs = context.getSharedPreferences("replay_sources", Context.MODE_PRIVATE)
            val folder = File(context.filesDir, "replay_sources")
            folder.mkdirs()
            val repo = prefs.getString(K_REPO, null)
            val token = prefs.getString(K_TOKEN, null)
            if (!repo.isNullOrBlank() && !token.isNullOrBlank()) {
                syncConfig.value = repo to token
                syncStatus.value = RsSyncStatus.Ready
            }
            dir = folder
        }
    }

    // ── Settings the sheets read ────────────────────────────────────────────

    fun lastfmUser(): String = AppSettings.lastfmUsername.value.trim()

    fun hasImportedBefore(): Boolean = ready && prefs.getLong(K_LAST_TS, 0L) > 0L

    fun lastSyncMillis(): Long = if (ready) prefs.getLong(K_SYNC_OK, 0L) else 0L

    fun saveSync(rawRepo: String, rawToken: String): String? {
        if (!ready) return "Not ready yet"
        val repo = rawRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("github.com/")
            .removeSuffix("/")
            .removeSuffix(".git")
        val token = rawToken.trim()
        if (!REPO_PATTERN.matches(repo)) return "Repository must look like owner/name"
        if (token.length < 20) return "That token looks too short"
        val editor = prefs.edit().putString(K_REPO, repo).putString(K_TOKEN, token).remove(K_HASH)
        prefs.all.keys.filter { it.startsWith(K_SHA) }.forEach { editor.remove(it) }
        editor.apply()
        File(dir, "remote").listFiles()?.forEach { it.delete() }
        syncConfig.value = repo to token
        syncStatus.value = RsSyncStatus.Ready
        return null
    }

    fun disableSync() {
        if (!ready) return
        val editor = prefs.edit().remove(K_REPO).remove(K_TOKEN).remove(K_HASH)
            .remove(K_SYNC_OK).remove(K_SYNC_TRY)
        prefs.all.keys.filter { it.startsWith(K_SHA) }.forEach { editor.remove(it) }
        editor.apply()
        File(dir, "remote").listFiles()?.forEach { it.delete() }
        syncConfig.value = null
        syncStatus.value = RsSyncStatus.Off
        // The other phones' listening is taken back out of this one.
        appScope.launch { mergeMutex.withLock { recomputeLocked() } }
    }

    /** Runs when the Replay page opens: quiet catch-up, only for things already set up. */
    fun autoRun() {
        if (!ready) return
        val now = System.currentTimeMillis()
        if (hasImportedBefore() && lastfmUser().isNotBlank() &&
            now - prefs.getLong(K_IMPORT_TRY, 0L) > MIN_INTERVAL_MS
        ) {
            startImport(RsImportScope.NEW)
        }
        if (syncConfig.value != null && now - prefs.getLong(K_SYNC_TRY, 0L) > MIN_INTERVAL_MS) {
            appScope.launch { syncNow(force = false) }
        }
    }

    // ── Last.fm import ──────────────────────────────────────────────────────

    fun startImport(scope: RsImportScope) {
        if (!ready) return
        if (!importing.compareAndSet(false, true)) return
        importStatus.value = RsImportStatus.Running("Starting", 0, 0)
        importJob = appScope.launch {
            try {
                doImport(scope)
            } catch (e: CancellationException) {
                importStatus.value = RsImportStatus.Idle
                throw e
            } catch (e: RsException) {
                importStatus.value = RsImportStatus.Failed(e.message ?: "Import failed")
            } catch (e: IOException) {
                importStatus.value = RsImportStatus.Failed("No connection to Last.fm")
            } catch (e: Exception) {
                Log.w(RS_TAG, "Import failed", e)
                importStatus.value = RsImportStatus.Failed(e.message ?: "Import failed")
            } finally {
                importing.set(false)
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    private suspend fun doImport(scope: RsImportScope) {
        val user = lastfmUser()
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        if (user.isBlank()) throw RsException("Sign in to Last.fm first (Settings, Account & scrobbling).")
        if (apiKey.isBlank()) throw RsException("This build has no Last.fm API key.")
        prefs.edit().putLong(K_IMPORT_TRY, System.currentTimeMillis()).apply()

        val zone = ZoneId.systemDefault()
        val startOfYear = LocalDate.now(zone).withDayOfYear(1).atStartOfDay(zone).toEpochSecond()
        val fromSec = when (scope) {
            RsImportScope.NEW -> {
                val last = prefs.getLong(K_LAST_TS, 0L)
                if (last > 0L) (last - OVERLAP_SEC).coerceAtLeast(0L) else startOfYear
            }
            RsImportScope.MONTH -> System.currentTimeMillis() / 1000L - 30L * 24L * 3600L
            RsImportScope.YEAR -> startOfYear
            RsImportScope.ALL -> 0L
        }

        // 1. Read the scrobbles.
        val all = ArrayList<RsSc>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            currentCoroutineContext().ensureActive()
            val result = fetchPage(user, apiKey, page, fromSec)
            totalPages = result.second.coerceAtLeast(1)
            all += result.first
            importStatus.value = RsImportStatus.Running("Reading Last.fm", page, totalPages)
            page++
            if (page <= totalPages) delay(250)
        }
        val newest = all.maxOfOrNull { it.uts } ?: 0L

        // 2. Drop what is already known.
        val known = mergeMutex.withLock {
            RsPlayIndex().also { idx -> readLedger().forEach { idx.add(it.k, it.p) } }
        }
        val cutoff = YearMonth.now().minusMonths(RS_KEEP_MONTHS - 1)
        val fresh = all.filter { s ->
            val ym = YearMonth.from(Instant.ofEpochSecond(s.uts).atZone(zone))
            !ym.isBefore(cutoff) && known.addIfNew(rsTrackKey(s.title, s.artist), s.uts * 1000L)
        }

        // 3. Song lengths, one lookup per song.
        val reps = LinkedHashMap<String, RsSc>()
        fresh.forEach { reps.getOrPut(rsMatchKey(it.title, it.artist)) { it } }
        val cache = HashMap(readDurations())
        val nativeAvg = nativeAverages()
        var done = 0
        for ((mk, rep) in reps) {
            currentCoroutineContext().ensureActive()
            importStatus.value = RsImportStatus.Running("Looking up song lengths", done, reps.size)
            if (resolveDuration(apiKey, mk, rep, cache, nativeAvg)) {
                if (done % 25 == 0) writeDurations(cache)
            }
            done++
        }
        writeDurations(cache)

        // 4. Add to the ledger and rebuild.
        importStatus.value = RsImportStatus.Running("Adding to Replay", reps.size, reps.size)
        val events = fresh.map { s ->
            val meta = cache[rsMatchKey(s.title, s.artist)]
            RsEv(
                k = rsTrackKey(s.title, s.artist),
                t = s.title.trim(),
                a = s.artist.trim(),
                al = s.album ?: meta?.al?.takeIf { it.isNotBlank() },
                u = s.art ?: meta?.u,
                p = s.uts * 1000L,
                l = (meta?.d ?: 0L).takeIf { it > 0L } ?: nativeAvg[rsMatchKey(s.title, s.artist)] ?: RS_DEFAULT_LENGTH_MS,
            )
        }
        val outcome = mergeMutex.withLock {
            val ledger = ArrayList(readLedger())
            val idx = RsPlayIndex()
            ledger.forEach { idx.add(it.k, it.p) }
            var added = 0
            events.forEach { if (idx.addIfNew(it.k, it.p)) { ledger += it; added++ } }
            if (added > 0) writeLedger(ledger)
            added to recomputeLocked()
        }
        if (newest > prefs.getLong(K_LAST_TS, 0L)) prefs.edit().putLong(K_LAST_TS, newest).apply()
        val now = System.currentTimeMillis()
        importStatus.value = RsImportStatus.Done(now, all.size, outcome.first, outcome.second.extras)
        if (outcome.second.changed) changeTick.value++
        if (outcome.first > 0 && syncConfig.value != null) syncNow(force = false)
    }

    /** Average listened length per play for tracks played in the app, by match key. */
    private suspend fun nativeAverages(): Map<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            ListeningStats.exportAll().forEach { b ->
                b.tracks.forEach { t ->
                    if (t.plays >= 2 && !t.id.startsWith("lf:")) {
                        val avg = t.ms / t.plays
                        if (avg in 45_000L..900_000L) out.putIfAbsent(rsMatchKey(t.title, t.artist), avg)
                    }
                }
            }
        }
        return out
    }

    /** Returns true when a network lookup was made. */
    private suspend fun resolveDuration(
        apiKey: String,
        mk: String,
        s: RsSc,
        cache: MutableMap<String, RsDur>,
        nativeAvg: Map<String, Long>,
    ): Boolean {
        val now = System.currentTimeMillis()
        cache[mk]?.let { if (it.d > 0L || now - it.at < RETRY_UNKNOWN_MS) return false }
        nativeAvg[mk]?.let { cache[mk] = RsDur(d = it, at = now); return false }
        val wait = ITUNES_GAP_MS - (now - lastITunesAt)
        if (wait > 0L) delay(wait)
        lastITunesAt = System.currentTimeMillis()
        val song = runCatching { iTunesLookup(s.title, s.artist) }.getOrNull()
        var duration = song?.d ?: 0L
        if (duration <= 0L) {
            duration = runCatching { lastFmDuration(apiKey, s.title, s.artist) }.getOrDefault(0L)
        }
        cache[mk] = RsDur(d = duration.coerceAtLeast(0L), al = song?.al, u = song?.u, at = System.currentTimeMillis())
        return true
    }

    private fun iTunesLookup(title: String, artist: String): RsDur? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$title $artist")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "8")
            .build()
        val body = fast.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string().orEmpty()
        }
        val results = json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return null
        val wantTitle = rsNormalize(title)
        val wantArtist = rsNormArtist(artist)
        for (el in results) {
            val o = el as? JsonObject ?: continue
            if (rsNormalize(o.str("trackName")) != wantTitle) continue
            if (rsNormArtist(o.str("artistName")) != wantArtist) continue
            val d = (o["trackTimeMillis"] as? JsonPrimitive)?.longOrNull ?: 0L
            return RsDur(
                d = d,
                al = o.str("collectionName").ifBlank { null },
                u = o.str("artworkUrl100").ifBlank { null }?.replace("100x100bb", "600x600bb"),
            )
        }
        return null
    }

    private fun lastFmDuration(apiKey: String, title: String, artist: String): Long {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "track.getInfo")
            .addQueryParameter("track", title)
            .addQueryParameter("artist", artist)
            .addQueryParameter("autocorrect", "1")
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .build()
        val body = fast.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return 0L
            it.body?.string().orEmpty()
        }
        val track = json.parseToJsonElement(body).jsonObject["track"] as? JsonObject ?: return 0L
        return track.str("duration").toLongOrNull() ?: 0L
    }

    /** One page of recent tracks: (scrobbles, total pages). */
    private suspend fun fetchPage(user: String, apiKey: String, page: Int, fromSec: Long): Pair<List<RsSc>, Int> {
        val builder = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "user.getrecenttracks")
            .addQueryParameter("user", user)
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
        if (fromSec > 0L) builder.addQueryParameter("from", fromSec.toString())
        val request = Request.Builder().url(builder.build()).build()

        var lastError = "Last.fm didn't answer. Try again in a minute."
        for (attempt in 0 until 4) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val code = (root?.get("error") as? JsonPrimitive)?.intOrNull
                    if (root != null && code != null) {
                        when (code) {
                            6 -> throw RsException("Last.fm doesn't know the user \"$user\".")
                            10, 26 -> throw RsException("Last.fm rejected the API key.")
                            17 -> throw RsException(
                                "Your Last.fm listening history is private. On last.fm open Settings, Privacy, " +
                                    "and untick \"Hide recent listening information\".",
                            )
                            8, 11, 16, 29 -> lastError = "Last.fm is busy right now. Try again in a minute."
                            else -> throw RsException(root.str("message").ifBlank { "Last.fm error $code" })
                        }
                    } else if (response.isSuccessful && root != null) {
                        return parsePage(root)
                    } else {
                        lastError = "Last.fm error ${response.code}"
                    }
                    Unit
                }
            } catch (e: RsException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = "No connection to Last.fm"
            }
            delay(2_000L * (attempt + 1))
        }
        throw RsException(lastError)
    }

    private fun parsePage(root: JsonObject): Pair<List<RsSc>, Int> {
        val recent = root["recenttracks"] as? JsonObject
            ?: throw RsException("Last.fm sent an answer this app doesn't understand.")
        val attr = recent["@attr"] as? JsonObject
        val totalPages = attr?.str("totalPages")?.toIntOrNull() ?: 1
        val list = recent["track"].asObjects().mapNotNull { t ->
            // The song playing right now has no date yet; a later import picks it up.
            val uts = (t["date"] as? JsonObject)?.str("uts")?.toLongOrNull() ?: return@mapNotNull null
            val title = t.str("name")
            val artistEl = t["artist"]
            val artist = (artistEl as? JsonObject)?.str("#text")
                ?: (artistEl as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val album = (t["album"] as? JsonObject)?.str("#text")?.takeIf { it.isNotBlank() }
            val art = t["image"].asObjects().map { it.str("#text") }
                .lastOrNull { it.isNotBlank() && !it.contains(NO_ART_HASH) }
            RsSc(title, artist, album, art, uts)
        }
        return list to totalPages
    }

    // ── GitHub sync ─────────────────────────────────────────────────────────

    suspend fun syncNow(force: Boolean = true) {
        val cfg = syncConfig.value ?: return
        if (!ready) return
        if (!syncMutex.tryLock()) return
        try {
            syncStatus.value = RsSyncStatus.Syncing
            prefs.edit().putLong(K_SYNC_TRY, System.currentTimeMillis()).apply()
            val result = withContext(Dispatchers.IO) { runSync(cfg, force) }
            val now = System.currentTimeMillis()
            prefs.edit().putLong(K_SYNC_OK, now).apply()
            syncStatus.value = RsSyncStatus.Done(now, result.extras)
            if (result.changed) changeTick.value++
        } catch (e: CancellationException) {
            throw e
        } catch (e: RsException) {
            syncStatus.value = RsSyncStatus.Failed(e.message ?: "Sync failed")
        } catch (e: IOException) {
            syncStatus.value = RsSyncStatus.Failed("No connection to GitHub")
        } catch (e: Exception) {
            Log.w(RS_TAG, "Sync failed", e)
            syncStatus.value = RsSyncStatus.Failed(e.message ?: "Sync failed")
        } finally {
            syncMutex.unlock()
        }
    }

    private class Entry(val name: String, val sha: String, val type: String)

    private suspend fun runSync(cfg: Pair<String, String>, force: Boolean): RsResult {
        val repo = cfg.first
        val token = cfg.second
        val info = json.parseToJsonElement(gh(token, "GET", "/repos/$repo")).jsonObject
        if ((info["private"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw RsException("That repository is public. Make it private first, so your listening history stays yours.")
        }

        val listing = listFolder(repo, token)
        val myName = "replay-${deviceId()}.json"
        val remoteDir = File(dir, "remote").apply { mkdirs() }
        val listed = HashSet<String>()
        for (entry in listing) {
            val name = entry.name
            if (entry.type != "file" || !name.startsWith("replay-") || !name.endsWith(".json") || name == myName) continue
            listed += name
            val cache = File(remoteDir, name)
            if (!force && cache.exists() && prefs.getString(K_SHA + name, null) == entry.sha) continue
            val body = gh(token, "GET", "/repos/$repo/contents/$FOLDER/$name", raw = true)
            if (runCatching { json.decodeFromString(RsRemoteFile.serializer(), body) }.isFailure) continue
            writeAtomic(cache, body)
            prefs.edit().putString(K_SHA + name, entry.sha).apply()
        }
        remoteDir.listFiles()?.forEach { if (it.name !in listed) it.delete() }

        // Merge what the other phones sent, then publish this phone's own.
        val result = mergeMutex.withLock { recomputeLocked() }

        val file = mergeMutex.withLock {
            RsRemoteFile(
                v = 1,
                device = deviceId(),
                app = "bitchord",
                events = readLedger(),
                native = readApplied()?.own.orEmpty(),
            )
        }
        val body = json.encodeToString(RsRemoteFile.serializer(), file)
        val hash = rsSha256(body)
        val mine = listing.firstOrNull { it.name == myName }
        if (mine == null || prefs.getString(K_HASH, null) != hash) {
            val content = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            fun upload(sha: String?) {
                val payload = buildJsonObject {
                    put("message", "Replay sync from ${deviceId()}")
                    put("content", content)
                    if (sha != null) put("sha", sha)
                }
                gh(token, "PUT", "/repos/$repo/contents/$FOLDER/$myName", payload = payload)
            }
            try {
                upload(mine?.sha)
            } catch (e: RsException) {
                if (e.code != 409 && e.code != 422) throw e
                // The file moved under us; look up where it is now and once more.
                upload(listFolder(repo, token).firstOrNull { it.name == myName }?.sha)
            }
            prefs.edit().putString(K_HASH, hash).apply()
        }
        return result
    }

    private fun listFolder(repo: String, token: String): List<Entry> {
        val body = try {
            gh(token, "GET", "/repos/$repo/contents/$FOLDER")
        } catch (e: RsException) {
            if (e.code == 404) return emptyList() else throw e
        }
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Entry(o.str("name"), o.str("sha"), o.str("type").ifBlank { "file" })
        }
    }

    private fun gh(token: String, method: String, path: String, raw: Boolean = false, payload: JsonObject? = null): String {
        val builder = Request.Builder()
            .url("https://api.github.com$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BitChord-Replay-Sync")
            .header("Cache-Control", "no-cache")
        if (method == "PUT" && payload != null) {
            builder.put(payload.toString().toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }
        return http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = response.code
                throw RsException(
                    when (code) {
                        401 -> "GitHub rejected the token. Check it hasn't expired."
                        403, 429 -> "GitHub refused the request. The token needs Contents: Read and write, or you hit a rate limit."
                        404 -> "Repository not found, or the token has no access to it."
                        409, 422 -> "Another phone just updated the file. Try Sync now again."
                        else -> "GitHub error $code"
                    },
                    code,
                )
            }
            text
        }
    }

    private fun deviceId(): String {
        prefs.getString(K_DEVICE, null)?.let { return it }
        val id = "bc" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(K_DEVICE, id).apply()
        return id
    }

    // ── The merge ───────────────────────────────────────────────────────────

    /**
     * Rebuilds Replay as: this phone's own listening, plus the other phones'
     * listening, plus any play on record at event level (Last.fm, LastWave) that
     * isn't already inside the app's own count.
     *
     * Must hold [mergeMutex].
     */
    private suspend fun recomputeLocked(): RsResult {
        if (!ready) return RsResult(false, 0)
        val cutoff = YearMonth.now().minusMonths(RS_KEEP_MONTHS - 1)
        var attempt = 0
        while (true) {
            val cur = ListeningStats.exportAll()
            val fingerprint = rsFingerprint(cur)
            val saved = readApplied()
            val restored = saved != null && rsLooksRestored(cur, saved.own + saved.overlay)
            val appliedOverlay: List<StoredBucket> = if (saved == null || restored) emptyList() else saved.overlay
            if (saved == null && cur.isNotEmpty()) backupOnce(cur)

            // This phone's own listening: what is on disk minus what was added last time.
            val own = LinkedHashMap<String, RsMonth>()
            cur.forEach { own.getOrPut(it.month) { RsMonth(it.month) }.addStored(it, 1) }
            appliedOverlay.forEach { b -> own[b.month]?.addStored(b, -1) }
            own.values.forEach { it.clamp() }

            // The other phones' own listening.
            val remoteFiles = readRemoteFiles()
            val remote = LinkedHashMap<String, RsMonth>()
            remoteFiles.forEach { f ->
                f.native.forEach { b ->
                    val ym = runCatching { YearMonth.parse(b.month) }.getOrNull() ?: return@forEach
                    if (!ym.isBefore(cutoff)) remote.getOrPut(b.month) { RsMonth(b.month) }.addStored(b, 1)
                }
            }

            // Plays on record at event level, merged across sources.
            val ledger = ArrayList(readLedger())
            val index = RsPlayIndex()
            ledger.forEach { index.add(it.k, it.p) }
            var grew = false
            remoteFiles.forEach { f ->
                f.events.forEach { e -> if (index.addIfNew(e.k, e.p)) { ledger += e; grew = true } }
            }
            if (grew) writeLedger(ledger)

            val native = LinkedHashMap<String, RsMonth>()
            own.values.forEach { native.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            remote.values.forEach { native.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            val extras = buildExtras(ledger, native, cutoff)

            val overlay = LinkedHashMap<String, RsMonth>()
            remote.values.forEach { overlay.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            var extraCount = 0
            extras.first.values.forEach { overlay.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            extraCount = extras.second

            val overlayStored = overlay.values.map { it.toStored() }.filter { it.tracks.isNotEmpty() }.sortedBy { it.month }
            val ownStored = own.values.map { it.toStored() }.filter { it.tracks.isNotEmpty() }.sortedBy { it.month }
            val unchanged = !restored && rsFingerprint(overlayStored) == rsFingerprint(appliedOverlay) &&
                overlayStored.size == appliedOverlay.size

            if (unchanged) {
                writeApplied(RsApplied(overlayStored, ownStored))
                return RsResult(false, extraCount)
            }

            val merged = LinkedHashMap<String, RsMonth>()
            own.values.forEach { merged.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            overlay.values.forEach { merged.getOrPut(it.key) { RsMonth(it.key) }.addMonth(it, 1) }
            val mergedStored = merged.values.map { it.toStored() }.filter { it.tracks.isNotEmpty() }.sortedBy { it.month }

            // If playback wrote something while this was being worked out, work it out again.
            if (rsFingerprint(ListeningStats.exportAll()) != fingerprint && attempt++ < 2) continue

            ListeningStats.importAll(mergedStored)
            writeApplied(RsApplied(overlayStored, ownStored))
            return RsResult(true, extraCount)
        }
    }

    /**
     * Plays known at event level that the native count doesn't already cover.
     *
     * Per song per day, the app's own plays and the event plays are the same
     * listens seen twice (the app scrobbles what it plays), so only the
     * surplus is added: max(0, events - native). Native listening kept by an
     * older build without per-day detail absorbs surplus in its month instead.
     */
    private fun buildExtras(
        ledger: List<RsEv>,
        native: Map<String, RsMonth>,
        cutoff: YearMonth,
    ): Pair<Map<String, RsMonth>, Int> {
        val nativeDay = HashMap<String, Int>()
        val nativeUnknown = HashMap<String, Int>()
        val best = HashMap<String, TrackEntry>()
        val albumHits = HashMap<String, NameEntry>()
        native.values.forEach { m ->
            m.tracks.values.forEach { t ->
                val mk = rsMatchKey(t.title, t.artist)
                var dayPlays = 0
                t.days.forEach { (d, s) ->
                    if (s.plays > 0) {
                        nativeDay.merge("$mk|$d", s.plays) { a, b -> a + b }
                        dayPlays += s.plays
                    }
                }
                if (t.plays > dayPlays) nativeUnknown.merge("$mk|${m.key}", t.plays - dayPlays) { a, b -> a + b }
                val current = best[mk]
                if (current == null || t.plays > current.plays) best[mk] = t
            }
            m.albums.values.forEach { a ->
                val lead = ListeningStats.primaryArtist(a.sub.orEmpty()) ?: a.sub.orEmpty()
                val key = rsNormalize(a.name) + "|" + rsNormalize(lead)
                val current = albumHits[key]
                if (current == null || (current.id == null && a.id != null)) albumHits[key] = a
            }
        }

        val zone = ZoneId.systemDefault()
        class Group(val mk: String, val month: String, val events: MutableList<RsEv> = ArrayList())
        val groups = LinkedHashMap<String, Group>()
        for (e in ledger) {
            val at = Instant.ofEpochMilli(e.p).atZone(zone)
            val ym = YearMonth.from(at)
            if (ym.isBefore(cutoff)) continue
            val mk = rsMatchKeyOfTrackKey(e.k)
            val day = "%04d-%02d-%02d".format(Locale.ROOT, at.year, at.monthValue, at.dayOfMonth)
            groups.getOrPut("$mk|$day") { Group(mk, ym.toString()) }.events += e
        }

        // Surplus per song per day, then whatever native listening without a day absorbs.
        val surplusByMonth = LinkedHashMap<String, MutableList<RsEv>>()
        groups.forEach { (key, g) ->
            val day = key.substringAfterLast('|')
            val covered = nativeDay["${g.mk}|$day"] ?: 0
            val extra = g.events.size - covered
            if (extra > 0) {
                g.events.sortBy { it.p }
                surplusByMonth.getOrPut("${g.mk}|${g.month}") { ArrayList() } += g.events.takeLast(extra)
            }
        }

        val result = LinkedHashMap<String, RsMonth>()
        var count = 0
        surplusByMonth.forEach { (key, list) ->
            list.sortBy { it.p }
            val absorbed = nativeUnknown[key] ?: 0
            val mk = key.substringBeforeLast('|')
            list.drop(absorbed).forEach { e ->
                val at = Instant.ofEpochMilli(e.p).atZone(zone)
                val month = result.getOrPut(YearMonth.from(at).toString()) { RsMonth(YearMonth.from(at).toString()) }
                val info = best[mk]
                val trackId = info?.id ?: ("lf:" + rsSha1(mk).take(16))
                val lead = ListeningStats.primaryArtist(info?.artist ?: e.a) ?: (info?.artist ?: e.a)
                val albumName = info?.album ?: e.al
                val hit = albumName?.let { albumHits[rsNormalize(it) + "|" + rsNormalize(lead)] }
                month.addEvent(e, trackId, info, hit, at)
                count++
            }
        }
        return result to count
    }

    // ── Disk ────────────────────────────────────────────────────────────────

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            if (file.delete() && tmp.renameTo(file)) Unit else tmp.delete()
        }
    }

    private fun readLedger(): List<RsEv> = runCatching {
        json.decodeFromString(RsLedger.serializer(), File(dir, "ledger.json").readText()).events
    }.getOrDefault(emptyList())

    private fun writeLedger(events: List<RsEv>) =
        writeAtomic(File(dir, "ledger.json"), json.encodeToString(RsLedger.serializer(), RsLedger(events)))

    private fun readApplied(): RsApplied? = runCatching {
        json.decodeFromString(RsApplied.serializer(), File(dir, "applied.json").readText())
    }.getOrNull()

    private fun writeApplied(state: RsApplied) =
        writeAtomic(File(dir, "applied.json"), json.encodeToString(RsApplied.serializer(), state))

    private fun readRemoteFiles(): List<RsRemoteFile> =
        File(dir, "remote").listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull {
            runCatching { json.decodeFromString(RsRemoteFile.serializer(), it.readText()) }.getOrNull()
        }.orEmpty()

    private fun readDurations(): Map<String, RsDur> = runCatching {
        json.decodeFromString(RsDurFile.serializer(), File(dir, "durations.json").readText()).items
    }.getOrDefault(emptyMap())

    private fun writeDurations(items: Map<String, RsDur>) =
        writeAtomic(File(dir, "durations.json"), json.encodeToString(RsDurFile.serializer(), RsDurFile(items)))

    /** The listening exactly as it was before this code first touched it. Kept once. */
    private fun backupOnce(cur: List<StoredBucket>) {
        val file = File(dir, "pre-merge-backup.json")
        if (file.exists()) return
        runCatching {
            writeAtomic(file, json.encodeToString(ListSerializer(StoredBucket.serializer()), cur))
        }
    }
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement?.asObjects(): List<JsonObject> = when (this) {
    is JsonArray -> this.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

private fun rsAgo(atMillis: Long): String {
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000L
    return when {
        atMillis <= 0L -> "never"
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

// ── Sources UI ──────────────────────────────────────────────────────────────

/** The two entry points, under the period picker. */
@Composable
private fun SourcesBar(dark: Boolean, onImport: () -> Unit, onSync: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    val imp by ReplaySources.importStatus.collectAsState()
    val sync by ReplaySources.syncStatus.collectAsState()
    val importLabel = if (imp is RsImportStatus.Running) "Last.fm: importing…" else "Last.fm import"
    val syncLabel = when (sync) {
        is RsSyncStatus.Syncing -> "GitHub: syncing…"
        is RsSyncStatus.Failed -> "GitHub sync (!)"
        else -> "GitHub sync"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(importLabel to onImport, syncLabel to onSync).forEach { (label, action) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = textColor.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(textColor.copy(alpha = 0.10f))
                    .clickable(onClick = action)
                    .padding(horizontal = 6.dp, vertical = 9.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayImportSheet(onDismiss: () -> Unit) {
    val status by ReplaySources.importStatus.collectAsState()
    val user = ReplaySources.lastfmUser()
    var scope by remember {
        mutableStateOf(if (ReplaySources.hasImportedBefore()) RsImportScope.NEW else RsImportScope.MONTH)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Import from Last.fm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.W800)
            Text(
                if (user.isBlank()) {
                    "You are not signed in to Last.fm. Sign in under Settings, Account & scrobbling, then come back."
                } else {
                    "Adds your Last.fm scrobbles for $user to Replay. Plays this app already counted are not added again, " +
                        "so nothing is double counted."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            RsImportScope.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (option == scope) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        )
                        .clickable { scope = option }
                        .padding(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
                        Text(
                            option.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            when (val s = status) {
                is RsImportStatus.Running -> {
                    Text(
                        if (s.total > 0) "${s.step}… ${s.done} of ${s.total}" else "${s.step}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.total > 0) {
                        LinearProgressIndicator(
                            progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { ReplaySources.cancelImport() }) { Text("Stop") }
                }
                is RsImportStatus.Done -> Text(
                    "Done ${rsAgo(s.atMillis)}. Read ${s.read} scrobbles, ${s.added} were new; " +
                        "${s.extras} plays are now on top of what the app counted itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is RsImportStatus.Failed -> Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                RsImportStatus.Idle -> Unit
            }
            Button(
                onClick = { ReplaySources.startImport(scope) },
                enabled = user.isNotBlank() && status !is RsImportStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import") }
            Text(
                "Song lengths come from the iTunes Search API (no key). After a first import, new scrobbles " +
                    "are fetched quietly whenever Replay opens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaySyncSheet(onDismiss: () -> Unit) {
    val config by ReplaySources.syncConfig.collectAsState()
    val status by ReplaySources.syncStatus.collectAsState()
    val coroutines = rememberCoroutineScope()
    var repo by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sync Replay with GitHub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.W800)
            val cfg = config
            if (cfg == null) {
                Text(
                    "Use a private GitHub repository and a fine-grained token with Contents: Read and write on it. " +
                        "Every phone that uses the same repository ends up with the same Replay. " +
                        "Files written by the LastWave Replay patch in the same repository are read too.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Repository (owner/name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        val problem = ReplaySources.saveSync(repo, token)
                        error = problem
                        if (problem == null) {
                            token = ""
                            coroutines.launch { ReplaySources.syncNow(force = true) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and sync") }
            } else {
                Text("Repository: ${cfg.first}", style = MaterialTheme.typography.bodyMedium)
                when (val s = status) {
                    is RsSyncStatus.Syncing -> {
                        Text("Syncing…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is RsSyncStatus.Done -> Text(
                        "Synced ${rsAgo(s.atMillis)}. ${s.extras} plays from other sources are on top of this phone's own count.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is RsSyncStatus.Failed -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        "Last synced ${rsAgo(ReplaySources.lastSyncMillis())}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { coroutines.launch { ReplaySources.syncNow(force = false) } },
                    enabled = status !is RsSyncStatus.Syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sync now") }
                TextButton(
                    onClick = { coroutines.launch { ReplaySources.syncNow(force = true) } },
                    enabled = status !is RsSyncStatus.Syncing,
                ) { Text("Re-download everything") }
                TextButton(onClick = { ReplaySources.disableSync() }) { Text("Turn sync off") }
                Text(
                    "This phone writes only its own file, replay/replay-<id>.json. Syncing runs when Replay opens, at most every 15 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
