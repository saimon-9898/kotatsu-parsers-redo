package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrDefault
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Properties

/**
 * Pornhwa DB metadata source.
 *
 * The public API (https://pornhwadb.com/docs) is a catalogue service, not a reader. Its `Chapter`
 * resource is a *scene record* - a chapter range plus tags and characters - and no schema in the
 * OpenAPI document exposes page image URLs. The platform says so directly in
 * https://pornhwadb.com/llms.txt: "it does not host full works for reading".
 *
 * So this parser maps catalogue data only: search, details and the scene list. [getPages] always
 * throws, and scene records are rendered into the details description rather than exposed as
 * tappable chapters, because a chapter entry here could only ever dead-end in the reader.
 *
 * ## API key
 *
 * The key is never hard-coded and never logged. It is resolved at request time by
 * [resolveApiKey] from, in order: the value entered in Kotatsu's per-source settings, the JVM
 * system property `pornhwadb.api.key`, the environment variable `PORNHWA_DB_API_KEY`, or a
 * `pornhwa-db.properties` file in the working directory (git-ignored, see `.gitignore`). With no
 * key configured every request fails with 401, which [api] reports as an explicit "rejected the
 * API key" error rather than a generic failure.
 */
@MangaSourceParser("PORNHWADB", "Pornhwa DB", "en", ContentType.HENTAI)
internal class PornhwaDbParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.PORNHWADB, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("pornhwadb.com")

	override val userAgentKey = ConfigKey.UserAgent(USER_AGENT)

	/**
	 * The in-app credential field, shown under this source's settings.
	 */
	private val apiKeyKey = ConfigKey.ApiKey()

	private val availableTags = suspendLazy { fetchTags() }

	init {
		setFirstPage(1)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(apiKeyKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add(HEADER_API_KEY, resolveApiKey(config[apiKeyKey]))
		.add("Accept", "application/json")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val defaultSortOrder: SortOrder = defaultSortOrderForTest

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			// The API takes one comma-separated `tags` value, and `tagMode=any` matches any of
			// them, so multi-select is honoured.
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		// A failure here means the key is missing or rejected; let the list screen show it rather
		// than offering an empty tag set.
		availableTags = availableTags.getOrDefault(emptySet()),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		// No locale list: the catalogue has no language filter, and declaring one makes the app
		// offer an original-language filter that the API cannot honour. Titles are romanised, but
		// that is a presentation choice, not a filterable language.
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		return if (query.isNullOrEmpty()) {
			api(
				path = "/pornhwa",
				params = buildList {
					add("page" to page)
					add("limit" to PAGE_SIZE)
					add("sort" to order.toApiSort())
					add("order" to order.toApiOrder())
					// Genre tags are the only tag kind the API can filter on.
					if (filter.tags.isNotEmpty()) {
						add("tags" to filter.tags.joinToString(",") { it.key })
						add("tagMode" to "any")
					}
					// The API takes a single `status`; ignore an ambiguous multi-select.
					filter.states.oneOrThrowIfMany()?.toApiStatus()?.let { add("status" to it) }
				},
			).getJSONArray("data").mapJSON { it.toManga() }
		} else {
			api(
				path = "/search",
				params = listOf(
					"q" to query,
					"type" to "pornhwa",
					// /search caps paging at 100, /pornhwa at 1000.
					"page" to page.coerceAtMost(MAX_SEARCH_PAGE),
					"limit" to PAGE_SIZE,
				),
			).getJSONObject("data")
				// `type=pornhwa` keeps characters out of the payload, but be defensive: if the
				// default ever changed, characters would otherwise map into the manga list.
				.optJSONArray("pornhwa")
				?.mapJSON { it.toManga() }
				.orEmpty()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix(PATH_PREFIX)
		val data = api(
			path = "/pornhwa/$slug",
			params = listOf("includes[]" to "characters"),
		).getJSONObject("data")
		return manga.copy(
			title = data.getStringOrNull("title") ?: manga.title,
			altTitles = data.optJSONArray("alternativeTitles")?.toStringSet().orEmpty(),
			coverUrl = data.getStringOrNull("coverImage") ?: manga.coverUrl,
			description = buildDescription(data, fetchScenes(slug)),
			rating = data.optDoubleOrNull("averageRating").toRating(),
			state = data.getStringOrNull("status").toMangaState(),
			authors = data.fetchAuthors(),
			tags = data.fetchTags(),
			// Scene records are not readable units, so they are not offered as chapters.
			chapters = null,
		)
	}

	/**
	 * The API exposes no page images, so there is nothing to return.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		throw ContentUnavailableException(READING_UNAVAILABLE)
	}

	/**
	 * Run an API call and translate failures into the parser's own exception types.
	 */
	private suspend fun api(path: String, params: List<Pair<String, Any>> = emptyList()): JSONObject {
		val url = buildString {
			append("https://")
			append(domain)
			append(API_PREFIX)
			append(path)
			if (params.isNotEmpty()) {
				append('?')
				append(params.joinToString("&") { (key, value) -> "$key=${value.toString().urlEncoded()}" })
			}
		}
		return try {
			webClient.httpGet(url).parseJson()
		} catch (error: HttpStatusException) {
			throw error.toParserException(url)
		}
	}

	/**
	 * `OkHttpWebClient` raises the bare jsoup exception, which carries only status and url, so
	 * the documented `Retry-After` value is not reachable here. Fall back to the advertised
	 * one-minute window instead of guessing a shorter one.
	 *
	 * ponytail: fixed 60s backoff from the documented 100 req/min window; read `Retry-After` if
	 * the client ever starts exposing response headers.
	 */
	private fun HttpStatusException.toParserException(url: String): Exception = when (statusCode) {
		HTTP_TOO_MANY_REQUESTS -> TooManyRequestExceptions(url, TimeUnit.SECONDS.toMillis(60))
		HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ParseException(
			shortMessage = "Pornhwa DB rejected the API key (HTTP $statusCode)",
			url = url,
		)

		else -> this
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		// `/tags/available?type=genre` answers with a flat array of names, not objects.
		val array = api("/tags/available", listOf("type" to "genre")).getJSONArray("data")
		return buildSet(array.length()) {
			for (i in 0 until array.length()) {
				val name = array.optString(i)
				if (name.isNotEmpty()) {
					add(MangaTag(title = name.toTitleCase(Locale.ENGLISH), key = name, source = source))
				}
			}
		}
	}

	/**
	 * Scene records page at 50 by default. `all=true` is avoided on purpose: for a long series it
	 * returns thousands of records in one response, which the rate limit makes expensive to
	 * recover from.
	 */
	private suspend fun fetchScenes(slug: String): List<JSONObject> {
		val scenes = ArrayList<JSONObject>()
		var page = 1
		while (page <= MAX_PAGE) {
			val response = api(
				path = "/pornhwa/$slug/chapters",
				params = listOf("page" to page, "limit" to SCENES_PAGE_SIZE),
			)
			val data = response.getJSONArray("data")
			scenes += data.mapJSON { it }
			if (data.length() == 0 ||
				!response.getJSONObject("pagination").optBoolean("hasMore", false)
			) {
				break
			}
			page++
		}
		return scenes
	}

	/**
	 * Visible for tests: maps one `data[]` entry of a list or search response.
	 */
	internal fun mapListItem(item: JSONObject): Manga = item.toManga()

	/**
	 * Visible for tests: maps the `data` object of a detail response, minus the scene list, which
	 * [mapDescription] folds into the description.
	 */
	internal fun mapDetail(data: JSONObject): Manga = Manga(
		id = generateUid("$PATH_PREFIX${data.getStringOrNull("slug")}"),
		title = data.getStringOrNull("title").orEmpty(),
		altTitles = data.optJSONArray("alternativeTitles")?.toStringSet().orEmpty(),
		url = "$PATH_PREFIX${data.getStringOrNull("slug")}",
		publicUrl = "https://$domain$PATH_PREFIX${data.getStringOrNull("slug")}",
		rating = data.optDoubleOrNull("averageRating").toRating(),
		contentRating = ContentRating.ADULT,
		coverUrl = data.getStringOrNull("coverImage"),
		tags = data.fetchTags(),
		state = data.getStringOrNull("status").toMangaState(),
		authors = data.fetchAuthors(),
		source = source,
	)

	/**
	 * Visible for tests: the rendered details description.
	 */
	internal fun mapDescription(data: JSONObject, scenes: List<JSONObject>): String =
		buildDescription(data, scenes)

	/**
	 * Visible for tests: one scene record as a display line, or null when it carries no chapter
	 * number.
	 */
	internal fun mapSceneLine(scene: JSONObject): String? = scene.toSceneLine()

	private fun JSONObject.toManga(): Manga {
		val slug = getStringOrNull("slug")
		return Manga(
			id = generateUid("$PATH_PREFIX$slug"),
			title = getStringOrNull("title") ?: slug.orEmpty(),
			altTitles = emptySet(),
			url = "$PATH_PREFIX$slug",
			publicUrl = "https://$domain$PATH_PREFIX$slug",
			rating = optDoubleOrNull("averageRating").toRating(),
			contentRating = ContentRating.ADULT,
			coverUrl = getStringOrNull("coverImage"),
			tags = emptySet(),
			state = getStringOrNull("status").toMangaState(),
			authors = emptySet(),
			source = source,
		)
	}

	private fun JSONObject.fetchAuthors(): Set<String> = buildSet {
		// `creators` is the normalized current field; `authors`/`artists` are legacy string arrays
		// still present on older records, so both are read and de-duplicated.
		optJSONArray("creators")?.mapJSON { it.getStringOrNull("canonicalName") }
			?.forEach { name -> name?.let(::add) }
		optJSONArray("authors")?.toStringSet()?.let(::addAll)
		optJSONArray("artists")?.toStringSet()?.let(::addAll)
	}

	private fun JSONObject.fetchTags(): Set<MangaTag> = buildSet {
		optJSONArray("genreTags")?.toStringSet()?.forEach { name ->
			add(MangaTag(title = name.toTitleCase(Locale.ENGLISH), key = name, source = source))
		}
		optJSONArray("characters")?.mapJSON { it.getStringOrNull("name") }
			?.forEach { name ->
				name?.let {
					add(MangaTag(title = "Character: $it", key = "character:$it", source = source))
				}
			}
	}

	private fun buildDescription(data: JSONObject, scenes: List<JSONObject>): String = buildString {
		data.getStringOrNull("description")?.let {
			append(it)
			append("\n\n")
		}
		val year = data.getIntOrDefault("releaseYear", 0)
		if (year > 0) {
			append("Release: $year\n\n")
		}
		val links = data.optJSONArray("externalLinks")
		if (links != null && links.length() > 0) {
			append("Official sources:\n")
			links.mapJSON { link ->
				val url = link.getStringOrNull("url") ?: return@mapJSON null
				link.getStringOrNull("siteName")?.let { "- $it: $url" } ?: "- $url"
			}.forEach { line -> line?.let { append(it).append('\n') } }
			append('\n')
		}
		if (scenes.isNotEmpty()) {
			append(SCENES_HEADING)
			scenes.mapNotNull { it.toSceneLine() }.forEach {
				append(it)
				append('\n')
			}
		}
		append(READING_UNAVAILABLE)
	}

	/**
	 * A scene reads as `Ch. 3-5: Hotel, Virgin (Kang Su-A, Park Mun-Su)`.
	 */
	internal fun JSONObject.toSceneLine(): String? {
		val start = optInt("chapterStart", 0)
		val end = optInt("chapterEnd", 0)
		if (start <= 0 && end <= 0) {
			return null
		}
		val range = when {
			end > 0 && end != start -> "$start-$end"
			start > 0 -> start.toString()
			else -> end.toString()
		}
		val tags = optJSONArray("tags")?.toStringSet().orEmpty()
		val characters = optJSONArray("characters")
			?.mapJSON { it.getStringOrNull("name") }
			.orEmpty()
			.filterNotNull()
		return buildString {
			append("Ch. ").append(range).append(':')
			if (tags.isNotEmpty()) {
				append(' ').append(tags.joinToString(", "))
			}
			if (characters.isNotEmpty()) {
				append(" (").append(characters.joinToString(", ")).append(')')
			}
		}
	}

	companion object {

		internal const val PAGE_SIZE = 20
		internal const val SCENES_PAGE_SIZE = 50
		internal const val MAX_PAGE = 1000
		internal const val MAX_SEARCH_PAGE = 100

		/** Ratings are on a 0.5-5.0 scale, the model wants 0..1. */
		internal const val MAX_RATING = 5.0

		internal const val PATH_PREFIX = "/pornhwa/"
		internal const val API_PREFIX = "/api/v1"
		internal const val HEADER_API_KEY = "X-API-Key"

		internal const val PROPERTY_API_KEY = "pornhwadb.api.key"
		internal const val ENV_API_KEY = "PORNHWA_DB_API_KEY"
		internal const val KEY_FILE_NAME = "pornhwa-db.properties"

		internal const val HTTP_UNAUTHORIZED = 401
		internal const val HTTP_FORBIDDEN = 403
		internal const val HTTP_TOO_MANY_REQUESTS = 429

		internal const val USER_AGENT =
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/138.0.7204.46 Mobile Safari/537.36"

		internal const val READING_UNAVAILABLE =
			"Pornhwa DB is a metadata catalogue and does not host chapter images, so chapters " +
				"cannot be read here. Use an official source listed above."

		internal const val SCENES_HEADING = "Scenes (chapter ranges known to the database):\n"

		/**
		 * Visible for tests, so the browse default is asserted without instantiating a parser.
		 */
		internal val defaultSortOrderForTest: SortOrder = SortOrder.POPULARITY

		/**
		 * `averageRating` is documented as nullable, so `optDouble` returning 0 for a JSON null
		 * has to be told apart from a real zero.
		 */
		internal fun JSONObject.optDoubleOrNull(key: String): Double? =
			if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

		/**
		 * `Completed` / `On Going` / `Hiatus`.
		 */
		internal fun String?.toMangaState(): MangaState? = when (this?.lowercase(Locale.ROOT)) {
			"completed" -> MangaState.FINISHED
			"on going", "ongoing" -> MangaState.ONGOING
			"hiatus" -> MangaState.PAUSED
			else -> null
		}

		/**
		 * Map the API's 0.5-5.0 rating onto the model's 0..1, or [RATING_UNKNOWN] when absent.
		 */
		internal fun Double?.toRating(): Float {
			val value = this ?: return RATING_UNKNOWN
			if (value.isNaN() || value <= 0.0) {
				return RATING_UNKNOWN
			}
			return (value / MAX_RATING).coerceIn(0.0, 1.0).toFloat()
		}

		/**
		 * The API sorts on a fixed set of fields; a missing mapping keeps the API default.
		 */
		internal fun SortOrder.toApiSort(): String = when (this) {
			SortOrder.RATING, SortOrder.POPULARITY -> "average_rating"
			SortOrder.NEWEST -> "release_year"
			SortOrder.UPDATED -> "updated_at"
			SortOrder.ALPHABETICAL -> "title"
			else -> "title"
		}

		/**
		 * Highest-rated first is the useful default; `release_year` ascending would otherwise
		 * lead with the oldest or least-documented entries, and unrated series sort last.
		 */
		internal fun SortOrder.toApiOrder(): String = when (this) {
			SortOrder.ALPHABETICAL -> "asc"
			else -> "desc"
		}

		/**
		 * `Hiatus` maps to [MangaState.PAUSED]. Unmapped states are skipped rather than guessed.
		 */
		internal fun MangaState.toApiStatus(): String? = when (this) {
			MangaState.FINISHED -> "Completed"
			MangaState.ONGOING -> "On Going"
			MangaState.PAUSED -> "Hiatus"
			else -> null
		}

		/**
		 * Resolve the key without ever logging or embedding it. Split out from the request path
		 * so tests can exercise the precedence rules directly.
		 *
		 * The in-app settings value comes first because that is how a real user supplies the key;
		 * the remaining sources exist so the desktop test harness and CI can run the same parser
		 * without a device. A blank value at any level falls through rather than sending an empty
		 * header, so a half-filled settings field still authenticates via the fallback.
		 */
		internal fun resolveApiKey(
			configured: String? = null,
			systemProperties: (String) -> String? = System::getProperty,
			environment: (String) -> String? = System::getenv,
			keyFile: File = File(KEY_FILE_NAME),
		): String = configured.isKey()
			?: systemProperties(PROPERTY_API_KEY).isKey()
			?: environment(ENV_API_KEY).isKey()
			?: keyFile.readKey()
			?: ""

		private fun String?.isKey(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

		/**
		 * `X-API-Key=...` in a git-ignored properties file, for setups without env vars.
		 */
		private fun File.readKey(): String? {
			if (!isFile) {
				return null
			}
			return runCatching {
				Properties().apply { inputStream().use(::load) }.getProperty(HEADER_API_KEY).isKey()
			}.getOrNull()
		}
	}
}
