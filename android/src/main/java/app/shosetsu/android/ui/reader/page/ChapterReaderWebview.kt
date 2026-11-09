/*
 * This file is part of Shosetsu.
 *
 * Shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package app.shosetsu.android.ui.reader.page

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import app.shosetsu.android.R

class ChapterReaderWebview(
	context: Context,
	attrs: AttributeSet? = null,
) : WebView(context, attrs) {

	var searchInBrowser: ((String) -> Unit)? = null
	var onDefineWord: ((String, Rect) -> Unit)? = null
	var onSearchImages: ((String, Rect) -> Unit)? = null

	private val selectionRect = Rect()

	override fun startActionMode(callback: ActionMode.Callback?): ActionMode {
		return super.startActionMode(wrapCallback(callback))
	}

	override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
		return super.startActionMode(wrapCallback(callback), type)
	}

	private fun wrapCallback(callback: ActionMode.Callback?): ActionMode.Callback {
		val actionMode = object : ActionMode.Callback {

			override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
				callback?.onCreateActionMode(mode, menu)
				ensureActions(menu)
				return true
			}

			override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
				val handled = callback?.onPrepareActionMode(mode, menu) ?: false
				return ensureActions(menu) || handled
			}

			override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
				val itemId = item?.itemId
				if (itemId == ITEM_SEARCH || itemId == ITEM_DEFINE || itemId == ITEM_IMAGES) {
					val needsAnchor = itemId == ITEM_DEFINE || itemId == ITEM_IMAGES
					val anchor = if (needsAnchor) selectionInWindow() else null
					getSelectedText { selectedText ->
						mode?.finish()
						val text = selectedText?.trim().orEmpty()
						if (text.isEmpty()) return@getSelectedText
						when (itemId) {
							ITEM_DEFINE -> onDefineWord?.invoke(text, anchor ?: selectionInWindow())
							ITEM_IMAGES -> onSearchImages?.invoke(text, anchor ?: selectionInWindow())
							else -> searchInBrowser?.invoke(text)
						}
					}
					return true
				}
				return callback?.onActionItemClicked(mode, item) ?: false
			}

			override fun onDestroyActionMode(mode: ActionMode?) {
				callback?.onDestroyActionMode(mode)
			}
		}

		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && callback is ActionMode.Callback2) {
			object : ActionMode.Callback2() {
				override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean =
					actionMode.onCreateActionMode(mode, menu)

				override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
					actionMode.onPrepareActionMode(mode, menu)

				override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean =
					actionMode.onActionItemClicked(mode, item)

				override fun onDestroyActionMode(mode: ActionMode?): Unit = actionMode.onDestroyActionMode(mode)
				override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
					callback.onGetContentRect(mode, view, outRect)
					if (outRect != null && !outRect.isEmpty) selectionRect.set(outRect)
				}
			}
		} else {
			actionMode
		}
	}

	private fun ensureActions(menu: Menu?): Boolean {
		if (menu == null) return false
		var added = false
		if (menu.findItem(ITEM_DEFINE) == null) {
			menu.add(0, ITEM_DEFINE, 0, R.string.define)
			added = true
		}
		if (menu.findItem(ITEM_IMAGES) == null) {
			menu.add(0, ITEM_IMAGES, 1, R.string.images)
			added = true
		}
		if (menu.findItem(ITEM_SEARCH) == null) {
			menu.add(0, ITEM_SEARCH, 2, R.string.search)
			added = true
		}
		return added
	}

	private fun selectionInWindow(): Rect {
		val rect = Rect(selectionRect)
		if (rect.isEmpty) rect.set(0, 0, width, 0)
		val location = IntArray(2)
		getLocationInWindow(location)
		rect.offset(location[0], location[1])
		return rect
	}

	private fun getSelectedText(callback: (String?) -> Unit) {
		evaluateJavascript(
			"(function(){return window.getSelection().toString();})()"
		) { value ->
			callback(decodeJavascriptString(value))
		}
	}

	private fun decodeJavascriptString(value: String?): String? {
		if (value.isNullOrBlank() || value == "null") return null
		return try {
			org.json.JSONArray("[$value]").getString(0)
		} catch (e: Exception) {
			value.removeSurrounding("\"")
		}
	}

	private companion object {
		const val ITEM_SEARCH = 1001
		const val ITEM_DEFINE = 1002
		const val ITEM_IMAGES = 1003
	}
}
