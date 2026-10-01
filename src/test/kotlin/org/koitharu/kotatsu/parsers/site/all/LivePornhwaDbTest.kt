package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.*

@EnabledIfEnvironmentVariable(named = "PORNHWA_DB_API_KEY", matches = ".+")
internal class LivePornhwaDbTest {

	private val parser by lazy {
		MangaLoaderContextMock.newParserInstance(MangaParserSource.PORNHWADB)
	}

	@Test
	fun `live browse returns mapped manga`() = runTest {
		val list = parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		println("BROWSE n=${list.size}")
		assertTrue(list.isNotEmpty())
		assertTrue(list.all { it.url.startsWith("/pornhwa/") })
		assertTrue(list.all { it.contentRating == ContentRating.ADULT })
		assertTrue(list.all { it.title.isNotBlank() })
		println("FIRST ${list.first().title} rating=${list.first().rating} state=${list.first().state}")
	}

	@Test
	fun `live pagination advances`() = runTest {
		val a = parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		val b = parser.getList(20, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		println("PAGE1=${a.size} PAGE2=${b.size}")
		assertTrue(a.isNotEmpty() && b.isNotEmpty())
		assertTrue(a.intersect(b.toSet()).isEmpty()) { "pages overlap" }
	}

	@Test
	fun `live search finds a known series`() = runTest {
		val list = parser.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = "100% Personal"))
		println("SEARCH n=${list.size} first=${list.firstOrNull()?.title}")
		assertTrue(list.any { it.title.contains("100% Personal") })
	}

	@Test
	fun `live tag and state filters apply`() = runTest {
		val tag = MangaTag(title = "Romance", key = "Romance", source = MangaParserSource.PORNHWADB)
		val list = parser.getList(
			0,
			SortOrder.POPULARITY,
			MangaListFilter(tags = setOf(tag), states = setOf(MangaState.FINISHED)),
		)
		println("FILTER n=${list.size}")
		assertTrue(list.isNotEmpty())
		assertTrue(list.all { it.state == MangaState.FINISHED })
	}

	@Test
	fun `live details include description and no chapters`() = runTest {
		val manga = parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY).first()
		val details = parser.getDetails(manga)
		println("DETAIL title=${details.title} authors=${details.authors} tags=${details.tags.size}")
		println("DESC>>>${details.description?.take(700)}")
		assertNotNull(details.description)
		assertTrue(details.description!!.isNotBlank())
		assertTrue(details.description!!.contains(PornhwaDbParser.READING_UNAVAILABLE))
		assertNull(details.chapters)
	}

	@Test
	fun `live filter options expose tags`() = runTest {
		val options = parser.getFilterOptions()
		println("TAGS n=${options.availableTags.size} sample=${options.availableTags.take(3).map { it.title }}")
		assertTrue(options.availableTags.size > 10)
	}

	@Test
	fun `live getPages reports that reading is unavailable`() = runTest {
		val chapter = MangaChapter(
			id = 1,
			title = "probe",
			number = 1f,
			volume = 0,
			url = "/pornhwa/100-personal",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.PORNHWADB,
		)
		// No nested runTest: this test already owns one.
		val error = runCatching { parser.getPages(chapter) }.exceptionOrNull()
		println("PAGES err=${error?.message}")
		assertNotNull(error)
		assertTrue(error!!.message!!.contains("does not host chapter images")) { error.message!! }
	}
}
