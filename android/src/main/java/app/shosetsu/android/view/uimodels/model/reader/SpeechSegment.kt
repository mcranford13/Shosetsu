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
package app.shosetsu.android.view.uimodels.model.reader

/**
 * One piece of chapter text handed to the dialogue segmenter.
 *
 * @param elementId id of the HTML element this text was read from, without the "textElement" prefix
 */
data class SegmentSource(
	val elementId: String,
	val text: String,
)

/**
 * A stretch of chapter text spoken in one voice.
 *
 * @param id unique id for this utterance. Used to know when the chapter has finished.
 * @param elementId HTML element to highlight while this segment is spoken
 * @param characterKey stable key for the speaker. "narrator", "unknown", or a normalized name
 */
data class SpeechSegment(
	val id: String,
	val elementId: String,
	val text: String,
	val characterKey: String,
	val displayName: String,
	val dialogue: Boolean,
)

data class ReaderVoiceCast(
	val characterKey: String,
	val displayName: String,
	val voicePackId: String,
)

data class ReaderVoiceOption(
	val id: String,
	val label: String,
)
