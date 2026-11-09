package app.shosetsu.android.backend.images

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/*
 * This file is part of shosetsu.
 *
 * shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 */

internal const val MAX_IMAGE_WORDS = 4
internal const val MAX_IMAGE_CHARS = 80

data class WordImage(
	val thumbUrl: String,
)

/**
 * Looks up a few photographs for a highlighted word on Wikimedia Commons.
 * The API is free and needs no key. Results are previews, credited in the bubble.
 */
class CommonsImageSearch(
	sharedClient: OkHttpClient,
) {
	private val client = OkHttpClient.Builder()
		.proxy(sharedClient.proxy)
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.build()

	fun search(selection: String): List<WordImage> {
		val query = imageQuery(selection) ?: return emptyList()
		val request = Request.Builder()
			.url(commonsImageSearchUrl(query))
			.header("User-Agent", USER_AGENT)
			.header("Api-User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) error("Image search failed (${response.code})")
			return parseCommonsImages(response.body?.string().orEmpty())
		}
	}

	private companion object {
		const val USER_AGENT = "Shosetsu (Android image lookup; https://github.com/mcranford13/Shosetsu)"
	}
}

internal fun imageQuery(selection: String): String? {
	val cleaned = selection.trim()
		.replace(Regex("\\s+"), " ")
		.trim { it.isWhitespace() || it in EDGE_PUNCTUATION }
	if (cleaned.isEmpty() || cleaned.length > MAX_IMAGE_CHARS) return null
	if (cleaned.split(' ').size > MAX_IMAGE_WORDS) return null
	return cleaned
}

internal fun commonsImageSearchUrl(query: String) =
	"https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
		.addQueryParameter("action", "query")
		.addQueryParameter("format", "json")
		.addQueryParameter("formatversion", "2")
		.addQueryParameter("generator", "search")
		.addQueryParameter("gsrsearch", "$query filetype:bitmap")
		.addQueryParameter("gsrnamespace", "6")
		.addQueryParameter("gsrlimit", "12")
		.addQueryParameter("prop", "imageinfo")
		.addQueryParameter("iiprop", "url|mime")
		.addQueryParameter("iiurlwidth", "480")
		.build()

internal fun parseCommonsImages(body: String, limit: Int = 6): List<WordImage> {
	if (body.isBlank()) return emptyList()
	val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
	val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray ?: return emptyList()
	val found = mutableListOf<Pair<Int, WordImage>>()
	pages.forEachIndexed { i, element ->
		val image = runCatching {
			val page = element.jsonObject
			val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
			val mime = info["mime"]?.jsonPrimitive?.content
			if (mime !in ALLOWED_TYPES) return@runCatching null
			val thumb = info["thumburl"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
				?: info["url"]?.jsonPrimitive?.content.orEmpty()
			if (!thumb.startsWith("https://")) return@runCatching null
			val index = page["index"]?.jsonPrimitive?.int ?: i
			index to WordImage(thumb)
		}.getOrNull()
		if (image != null) found += image
	}
	return found.sortedBy { it.first }.map { it.second }.distinctBy { it.thumbUrl }.take(limit)
}

private val JSON = Json { ignoreUnknownKeys = true }

private const val EDGE_PUNCTUATION = "\"'`.,;:!?()[]{}“”‘’"

private val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
