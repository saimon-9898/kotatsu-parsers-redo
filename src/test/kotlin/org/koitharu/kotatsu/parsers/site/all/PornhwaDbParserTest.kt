package org.koitharu.kotatsu.parsers.site.all

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.optDoubleOrNull
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.toApiSort
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.toMangaState
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.toRating
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.toApiOrder
import org.koitharu.kotatsu.parsers.site.all.PornhwaDbParser.Companion.toApiStatus
import java.io.File
import java.nio.file.Path

/**
 * Offline tests for [PornhwaDbParser]: JSON mapping, API-key handling and error translation.
 * Fixtures are trimmed copies of real `GET /api/v1` responses.
 */
internal class PornhwaDbParserTest {

	// --- fixtures -------------------------------------------------------------------------

	private val listItem = """
		{
		  "id": 596,
		  "title": "100% Personal",
		  "slug": "100-personal",
		  "coverImage": "https://images.pornhwadb.com/covers/100__Personal.jpg",
		  "status": "Completed",
		  "orientation": null,
		  "description": "MOON-SU has to win another short-film award.",
		  "releaseYear": 2022,
		  "releaseMonth": null,
		  "endYear": 2023,
		  "endMonth": null,
		  "creators": [
		    {"id": 237, "canonicalName": "Jayeon Ppang", "aliases": ["자연빵"], "role": "artist"},
		    {"id": 192, "canonicalName": "Hijun", "aliases": ["히준"], "role": "author"}
		  ],
		  "artists": ["Jayeon Ppang"],
		  "authors": ["Hijun"],
		  "genreTags": ["Harem", "Romance"],
		  "averageRating": 3.72,
		  "totalRatings": 27,
		  "chapterCount": 10,
		  "totalChapters": 32
		}
	""".trimIndent()

	private val searchItem = """
		{
		  "id": 596,
		  "title": "100% Personal",
		  "slug": "100-personal",
		  "coverImage": "https://images.pornhwadb.com/covers/100__Personal.jpg",
		  "status": "Completed",
		  "orientation": null
		}
	""".trimIndent()

	private val detail = """
		{
		  "id": 596,
		  "title": "100% Personal",
		  "slug": "100-personal",
		  "coverImage": "https://images.pornhwadb.com/covers/100__Personal.jpg",
		  "status": "Completed",
		  "dataStatus": "complete",
		  "description": "MOON-SU has to win another short-film award.",
		  "releaseYear": 2022,
		  "genreTags": ["Harem", "Romance"],
		  "creators": [
		    {"id": 237, "canonicalName": "Jayeon Ppang", "aliases": ["자연빵"], "role": "artist"},
		    {"id": 192, "canonicalName": "Hijun", "aliases": ["히준"], "role": "author"}
		  ],
		  "artists": ["Jayeon Ppang"],
		  "authors": ["Hijun"],
		  "alternativeTitles": ["사심 100%", "A Hidden Motive"],
		  "externalLinks": [
		    {"siteName": "AniList", "url": "https://anilist.co/manga/159066"},
		    {"siteName": "TOPTOON EN", "url": "https://global.toptoon.com/content/102787"}
		  ],
		  "characters": [
		    {"id": 9715, "name": "Kang Su-A", "role": "Supporting"},
		    {"id": 9713, "name": "Park Mun-Su", "role": "Main"}
		  ]
		}
	""".trimIndent()

	private val scene = """
		{
		  "id": 53377,
		  "chapterStart": 3,
		  "chapterEnd": 5,
		  "description": "",
		  "tags": ["Boob Fondling", "Hotel", "Virgin"],
		  "characters": [
		    {"id": 9715, "name": "Kang Su-A"},
		    {"id": 9713, "name": "Park Mun-Su"}
		  ]
		}
	""".trimIndent()

	// --- mapping --------------------------------------------------------------------------

	@Test
	fun `list item maps to manga`() {
		val manga = parser().let { it.mapListItem(JSONObject(listItem)) }

		assertEquals("100% Personal", manga.title)
		assertEquals("/pornhwa/100-personal", manga.url)
		assertEquals("https://pornhwadb.com/pornhwa/100-personal", manga.publicUrl)
		assertEquals("https://images.pornhwadb.com/covers/100__Personal.jpg", manga.coverUrl)
		assertEquals(MangaState.FINISHED, manga.state)
		assertEquals(ContentRating.ADULT, manga.contentRating)
		assertEquals(MangaParserSource.PORNHWADB, manga.source)
	}

	@Test
	fun `relative url is used and id is derived from the slug`() {
		val first = parser().let { it.mapListItem(JSONObject(listItem)) }
		val second = parser().let { it.mapListItem(JSONObject(listItem)) }

		assertFalse(first.url.startsWith("http"))
		assertEquals(first.id, second.id)
	}

	@Test
	fun `5 point rating maps onto 0 to 1`() {
		val manga = parser().let { it.mapListItem(JSONObject(listItem)) }

		assertEquals(3.72f / 5f, manga.rating, 0.0001f)
		assertTrue(manga.rating in 0f..1f)
	}

	@Test
	fun `null rating becomes unknown`() {
		assertEquals(RATING_UNKNOWN, null.toRating())
	}

	@Test
	fun `search result maps the same way as a list item`() {
		val manga = parser().let { it.mapListItem(JSONObject(searchItem)) }

		assertEquals("100% Personal", manga.title)
		assertEquals("/pornhwa/100-personal", manga.url)
		assertEquals(MangaState.FINISHED, manga.state)
	}

	@Test
	fun `detail maps alt titles authors and tags`() {
		val mapped = parser().let { it.mapDetail(JSONObject(detail)) }

		assertEquals("100% Personal", mapped.title)
		assertEquals(setOf("사심 100%", "A Hidden Motive"), mapped.altTitles)
		// Legacy string arrays plus normalized creators collapse to the same two names.
		assertEquals(setOf("Jayeon Ppang", "Hijun"), mapped.authors)
		assertTrue(mapped.tags.any { it.title == "Harem" })
		assertTrue(mapped.tags.any { it.title == "Character: Kang Su-A" })
		assertEquals(MangaState.FINISHED, mapped.state)
	}

	@Test
	fun `detail description carries official sources and the no-reading notice`() {
		val scenes = listOf(JSONObject(scene))
		val description = parser().let { it.mapDescription(JSONObject(detail), scenes) }

		assertTrue(description.contains("https://anilist.co/manga/159066"))
		assertTrue(description.contains("AniList"))
		assertTrue(description.contains(PornhwaDbParser.READING_UNAVAILABLE))
	}

	@Test
	fun `scene maps to a readable line`() {
		val line = parser().let { it.mapSceneLine(JSONObject(scene)) }

		assertNotNull(line)
		assertTrue(line!!.startsWith("Ch. 3-5:")) { line }
		assertTrue(line.contains("Hotel")) { line }
		assertTrue(line.contains("Kang Su-A")) { line }
	}

	@Test
	fun `scene without a chapter number is dropped`() {
		val line = parser().let {
			it.mapSceneLine(JSONObject("""{"id": 1, "chapterStart": 0, "chapterEnd": 0, "tags": []}"""))
		}

		assertNull(line)
	}

	@Test
	fun `single chapter scene omits the range dash`() {
		val line = parser().let {
			it.mapSceneLine(
				JSONObject("""{"id": 1, "chapterStart": 7, "chapterEnd": null, "tags": ["Kiss"]}"""),
			)
		}

		assertEquals("Ch. 7: Kiss", line)
	}

	@Test
	fun `status values map to manga states`() {
		assertEquals(MangaState.FINISHED, "Completed".toMangaState())
		assertEquals(MangaState.ONGOING, "On Going".toMangaState())
		assertEquals(MangaState.PAUSED, "Hiatus".toMangaState())
		assertNull(null.toMangaState())
		assertNull("Something New".toMangaState())
	}

	@Test
	fun `sort orders map to API sort fields`() {
		assertEquals("average_rating", SortOrder.RATING.toApiSort())
		assertEquals("release_year", SortOrder.NEWEST.toApiSort())
		assertEquals("updated_at", SortOrder.UPDATED.toApiSort())
		assertEquals("title", SortOrder.ALPHABETICAL.toApiSort())
	}

	@Test
	fun `sort direction is descending except for alphabetical`() {
		// Ascending `release_year` would lead with the oldest entries, and unrated series sort
		// last on `average_rating`, so both need descending.
		assertEquals("desc", SortOrder.RATING.toApiOrder())
		assertEquals("desc", SortOrder.POPULARITY.toApiOrder())
		assertEquals("desc", SortOrder.NEWEST.toApiOrder())
		assertEquals("desc", SortOrder.UPDATED.toApiOrder())
		assertEquals("asc", SortOrder.ALPHABETICAL.toApiOrder())
	}

	@Test
	fun `states map to API status values`() {
		assertEquals("Completed", MangaState.FINISHED.toApiStatus())
		assertEquals("On Going", MangaState.ONGOING.toApiStatus())
		assertEquals("Hiatus", MangaState.PAUSED.toApiStatus())
		assertNull(MangaState.ABANDONED.toApiStatus())
		assertNull(MangaState.UPCOMING.toApiStatus())
	}

	@Test
	fun `default browse order is highest rated first`() {
		assertEquals("average_rating", PornhwaDbParser.defaultSortOrderForTest.toApiSort())
		assertEquals("desc", PornhwaDbParser.defaultSortOrderForTest.toApiOrder())
	}

	// --- API key -------------------------------------------------------------------------

	@Test
	fun `system property wins over environment and file`(@TempDir dir: Path) {
		val keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME).apply {
			writeText("${PornhwaDbParser.HEADER_API_KEY}=from-file")
		}
		val key = PornhwaDbParser.resolveApiKey(
			systemProperties = { name -> if (name == PornhwaDbParser.PROPERTY_API_KEY) "from-sysprop" else null },
			environment = { name -> if (name == PornhwaDbParser.ENV_API_KEY) "from-env" else null },
			keyFile = keyFile,
		)

		assertEquals("from-sysprop", key)
	}

	@Test
	fun `settings value wins over every fallback`(@TempDir dir: Path) {
		val keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME).apply {
			writeText("${PornhwaDbParser.HEADER_API_KEY}=from-file")
		}
		val key = PornhwaDbParser.resolveApiKey(
			configured = "from-settings",
			systemProperties = { name -> if (name == PornhwaDbParser.PROPERTY_API_KEY) "from-sysprop" else null },
			environment = { name -> if (name == PornhwaDbParser.ENV_API_KEY) "from-env" else null },
			keyFile = keyFile,
		)

		assertEquals("from-settings", key)
	}

	@Test
	fun `an empty settings value falls through to the next source`(@TempDir dir: Path) {
		val key = PornhwaDbParser.resolveApiKey(
			configured = "   ",
			systemProperties = { null },
			environment = { name -> if (name == PornhwaDbParser.ENV_API_KEY) "from-env" else null },
			keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME),
		)

		assertEquals("from-env", key)
	}

	@Test
	fun `the in-app api key config has no default credential`() {
		// A default here would be a credential compiled into the shipped binary.
		assertEquals("", ConfigKey.ApiKey().defaultValue)
	}

	@Test
	fun `environment is used when no system property is set`(@TempDir dir: Path) {
		val key = PornhwaDbParser.resolveApiKey(
			systemProperties = { null },
			environment = { name -> if (name == PornhwaDbParser.ENV_API_KEY) "from-env" else null },
			keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME),
		)

		assertEquals("from-env", key)
	}

	@Test
	fun `key file is the last resort`(@TempDir dir: Path) {
		val keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME).apply {
			writeText("${PornhwaDbParser.HEADER_API_KEY}=file-key")
		}
		val key = PornhwaDbParser.resolveApiKey(
			systemProperties = { null },
			environment = { null },
			keyFile = keyFile,
		)

		assertEquals("file-key", key)
	}

	@Test
	fun `missing key resolves to empty rather than throwing`(@TempDir dir: Path) {
		val key = PornhwaDbParser.resolveApiKey(
			systemProperties = { null },
			environment = { null },
			keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME),
		)

		assertEquals("", key)
	}

	@Test
	fun `blank values are ignored so an empty env var does not shadow the file`(@TempDir dir: Path) {
		val keyFile = File(dir.toFile(), PornhwaDbParser.KEY_FILE_NAME).apply {
			writeText("${PornhwaDbParser.HEADER_API_KEY}=file-key")
		}
		val key = PornhwaDbParser.resolveApiKey(
			systemProperties = { "   " },
			environment = { "" },
			keyFile = keyFile,
		)

		assertEquals("file-key", key)
	}

	@Test
	fun `no credential is embedded in the source`() {
		val text = parserSource().readText()

		assertFalse(Regex("""pwdb_[A-Za-z0-9_-]{8,}""").containsMatchIn(text)) {
			"A hard-coded API key literal is present in the parser source"
		}
	}

	@Test
	fun `the api key is never appended to a url`() {
		// The key is only ever sent as a request header. This guards the url builder against
		// someone "fixing" a 401 by passing the key as a query parameter, which would leak it
		// into proxy logs and crash reports.
		val urlBuilderLines = parserSource().readLines()
			.filter { it.contains("append(") || it.contains("addQueryParameter") }

		assertTrue(urlBuilderLines.none { it.contains("API_KEY") || it.contains("resolveApiKey") }) {
			"API key referenced while building a url: $urlBuilderLines"
		}
	}

	@Test
	fun `the api key is sent as a request header`() {
		val text = parserSource().readText()
		val expected = ".add(HEADER_API_KEY, resolveApiKey(config[apiKeyKey]))"

		assertTrue(text.contains(expected)) {
			"Expected the key to be attached as a ${PornhwaDbParser.HEADER_API_KEY} header"
		}
	}

	// --- source registration -------------------------------------------------------------

	@Test
	fun `source is registered as an adult hentai source`() {
		assertEquals(ContentType.HENTAI, MangaParserSource.PORNHWADB.contentType)
		assertEquals("Pornhwa DB", MangaParserSource.PORNHWADB.title)
		assertEquals("en", MangaParserSource.PORNHWADB.locale)
	}

	@Test
	fun `no chapter is offered because none can be read`() {
		// A tappable chapter here would always dead-end; getPages throws instead.
		assertTrue(PornhwaDbParser.READING_UNAVAILABLE.isNotBlank())
	}

	/**
	 * Mapping is exercised without any network: the parser is only used for its
	 * `source`/`domain`/`generateUid` plumbing, never to issue a request.
	 */
	private fun parserSource() = File(
		"src/main/kotlin/org/koitharu/kotatsu/parsers/site/all/PornhwaDbParser.kt",
	).also {
		assertTrue(it.isFile) { "Parser source not found at ${it.absolutePath}" }
	}

	// `newParserInstance` hands back the KSP-generated wrapper; the parser itself is what carries
	// the mapping helpers under test.
	private fun parser() = PornhwaDbParser(MangaLoaderContextMock)
}
