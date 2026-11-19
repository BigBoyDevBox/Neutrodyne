// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chart the station dial is tuned to: [genre] null is the top chart. [hits] stay null while
 * the chart loads; [failure] says why it did not ([ProviderStatus.Disabled] when Apple, the only
 * chart source, is switched off). [generation] changes whenever the chart must be fetched again.
 */
internal data class TunedChart(
    val genre: ChartGenre? = null,
    val hits: List<DirectoryHit>? = null,
    val failure: ProviderStatus? = null,
    val generation: Int = 0,
)

internal data class DiscoverState(
    val input: String = "",
    val results: SearchResults? = null,
    /** Keeps the source query with the slips still on screen while a replacement is pending. */
    val retainedResults: SearchResults? = null,
    val providers: List<ProviderInfo>? = null,
    /** Preset stations: the top chart, then each genre a group's name maps to (03), once. */
    val presets: List<ChartGenre?> = listOf(null),
    val chart: TunedChart = TunedChart(),
)

@ViewModelKey(DiscoverViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DiscoverViewModel(
    private val repository: SearchRepository,
    groups: GroupRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoverState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var disclosedProviders: List<ProviderInfo>? = null
    private var pendingSearchTiming = SearchTiming.DEBOUNCED
    private var chartJob: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            repository.observeProviders().collect { providers ->
                val previous = mutableState.value.providers
                val changed = previous?.configuration() != providers.configuration()
                if (changed) {
                    disclosedProviders = null
                    pendingSearchTiming = if (previous == null) SearchTiming.DEBOUNCED else SearchTiming.IMMEDIATE
                }
                mutableState.update { it.copy(providers = providers) }
                if (changed) restartSearch(pendingSearchTiming)
            }
        }
        viewModelScope.launch {
            // Charts come from Apple: a changed directory configuration changes their answer.
            repository
                .observeProviders()
                .map { providers -> providers.configuration() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refetchChart() }
        }
        viewModelScope.launch {
            groups.observeGroups().map { rows -> rows.map { it.name } }.distinctUntilChanged().collect { names ->
                val presets =
                    (listOf<ChartGenre?>(null) + names.mapNotNull(repository::genreForGroupName)).distinct()
                mutableState.update { it.copy(presets = presets) }
            }
        }
    }

    /** Tunes the dial; the screen asks for the chart with [loadChart] once it shows it. */
    fun tune(genre: ChartGenre?) {
        if (genre == mutableState.value.chart.genre) return
        chartJob?.cancel()
        mutableState.update { it.copy(chart = TunedChart(genre, generation = it.chart.generation + 1)) }
    }

    /** Fetches the tuned chart again after a failure. */
    fun retryChart() = refetchChart()

    /** The screen calls this as the tuned chart enters composition; repeated calls share one request. */
    fun loadChart(genre: ChartGenre?) {
        val chart = mutableState.value.chart
        if (genre != chart.genre || chart.hits != null || chart.failure != null || chartJob?.isActive == true) return
        chartJob =
            viewModelScope.launch {
                val result = repository.charts(genre)
                ensureActive()
                mutableState.update { state ->
                    if (state.chart.genre != genre || state.chart.generation != chart.generation) {
                        state
                    } else {
                        when (result) {
                            is Outcome.Success -> state.copy(chart = state.chart.copy(hits = result.value))
                            is Outcome.Failure -> state.copy(chart = state.chart.copy(failure = result.error))
                        }
                    }
                }
            }
    }

    /** Provider and group changes can change the chart's answer: drop it and wait to be shown. */
    private fun refetchChart() {
        chartJob?.cancel()
        mutableState.update { it.copy(chart = TunedChart(it.chart.genre, generation = it.chart.generation + 1)) }
    }

    /** Uses Directory's disclosure acknowledgement, including its frame boundary. */
    fun onProvidersVisible(providers: List<ProviderInfo>) {
        val current = mutableState.value.providers ?: return
        if (providers.configuration() != current.configuration() ||
            disclosedProviders?.configuration() == current.configuration()
        ) {
            return
        }
        disclosedProviders = current
        restartSearch(pendingSearchTiming)
    }

    fun inputChanged(input: String) {
        mutableState.update { it.copy(input = input) }
        pendingSearchTiming = SearchTiming.DEBOUNCED
        restartSearch(pendingSearchTiming)
    }

    fun retry() {
        pendingSearchTiming = SearchTiming.IMMEDIATE
        restartSearch(pendingSearchTiming)
    }

    private fun restartSearch(timing: SearchTiming) {
        searchJob?.cancel()
        val run = ++generation
        val query = mutableState.value.input.trim()
        val providers = mutableState.value.providers
        if (providers == null || disclosedProviders?.configuration() != providers.configuration() ||
            query.length < MIN_QUERY_LENGTH || isLinkInput(query)
        ) {
            mutableState.update { it.copy(results = null, retainedResults = null) }
            return
        }
        mutableState.update { state ->
            state.copy(
                results = null,
                retainedResults = state.results?.takeIf { it.hits.isNotEmpty() } ?: state.retainedResults,
            )
        }
        searchJob =
            viewModelScope.launch {
                if (timing == SearchTiming.DEBOUNCED) delay(SEARCH_DEBOUNCE_MS)
                repository.search(query).collect { results ->
                    ensureActive()
                    if (run == generation) {
                        mutableState.update { state ->
                            state.copy(
                                results = results,
                                retainedResults =
                                    state.retainedResults.takeIf {
                                        !results.complete &&
                                            results.hits.isEmpty()
                                    },
                            )
                        }
                    }
                }
            }
    }
}

// Conservatively keep addresses, handles and pasted link-bearing prose out of directory queries.
// Add owns normalization and validation; this boundary only decides whether disclosure is enough.
// A slash can introduce a private path on a single-label host. Colons between words and
// punctuation in names such as "Web 2.0" and "The A.I. Show" still allow a title search.
internal fun isLinkInput(text: String): Boolean = text.split(WHITESPACE).any(::isLinkWord)

private fun isLinkWord(raw: String): Boolean {
    val word = raw.trim(*WORD_PUNCTUATION)
    if (word.isEmpty()) return false
    if (word.startsWith("UC") && word.length == YOUTUBE_CHANNEL_ID_LENGTH) return true
    if ('@' in word || '\\' in word || '=' in word || word.count { it == ':' } >= 2) return true
    if ('/' in word || word.startsWith('~')) return true
    // A scheme ("https:", "feed:", "itpc:") or a host and port: a letter, then a colon with
    // something after it. A colon that ends a word ("Wars:") or a clock time ("10:30") is text.
    if (SCHEME_OR_PORT.containsMatchIn(word)) return true
    val host = word.substringBefore('?').substringBefore(':')
    return host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || DOMAIN.matches(host)
}

// Pasted text often joins words with a no-break space (U+00A0, U+202F), which \s does not match;
// \p{Z} covers every Unicode space separator.
private val WHITESPACE = Regex("[\\s\\p{Z}]+")

// Marks that wrap or end a word in prose, typographic ones included, so a quoted or trailing
// address ("“example.com”", "…https://x") is still seen whole.
private val WORD_PUNCTUATION =
    ".,;!?\"'()[]<>\u2018\u2019\u201C\u201D\u201E\u00AB\u00BB\u2039\u203A\u2026\u2013\u2014\u2022".toCharArray()
private val SCHEME_OR_PORT = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.")
private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

// Internationalized host names can use the ASCII, ideographic, fullwidth or halfwidth dot.
private val DOMAIN =
    Regex("""^[\p{L}\p{N}\p{M}-]+([.。．｡][\p{L}\p{N}\p{M}-]+)*[.。．｡]\p{L}[\p{L}\p{N}\p{M}-]+$""")

internal const val MIN_QUERY_LENGTH = 3
internal const val SEARCH_DEBOUNCE_MS = 600L
private const val YOUTUBE_CHANNEL_ID_LENGTH = 24

private enum class SearchTiming { IMMEDIATE, DEBOUNCED }

internal fun List<ProviderInfo>.configuration() = map { Triple(it.id, it.effective, it.configurationVersion) }
