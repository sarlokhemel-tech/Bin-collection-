package com.hemel.dailynotebook.data

import org.json.JSONArray
import org.json.JSONObject

const val TYPE_TEXT = "text"
const val TYPE_IMAGE = "image"
const val TYPE_TABLE = "table"

data class PageElement(
    var type: String,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    var fontSize: Float = 16f,
    var text: String = "",
    // Compact JSON list of colored/highlighted ranges within `text`, e.g.
    // [{"s":0,"e":4,"fg":"#DC2626"},{"s":5,"e":9,"bg":"#FDE68A"}]. Empty when the
    // box has no custom coloring.
    var colorSpansJson: String = "",
    var image: String = "",
    var rows: Int = 0,
    var cols: Int = 0,
    var cells: MutableList<MutableList<String>> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        o.put("x", x); o.put("y", y); o.put("w", w); o.put("h", h)
        o.put("fontSize", fontSize)
        o.put("text", text)
        o.put("colorSpans", colorSpansJson)
        o.put("image", image)
        o.put("rows", rows); o.put("cols", cols)
        val cellsArr = JSONArray()
        for (row in cells) {
            val rowArr = JSONArray()
            for (c in row) rowArr.put(c)
            cellsArr.put(rowArr)
        }
        o.put("cells", cellsArr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): PageElement {
            val cellsList = mutableListOf<MutableList<String>>()
            val cellsArr = o.optJSONArray("cells")
            if (cellsArr != null) {
                for (i in 0 until cellsArr.length()) {
                    val rowArr = cellsArr.getJSONArray(i)
                    val row = mutableListOf<String>()
                    for (j in 0 until rowArr.length()) row.add(rowArr.getString(j))
                    cellsList.add(row)
                }
            }
            return PageElement(
                type = o.getString("type"),
                x = o.getInt("x"), y = o.getInt("y"),
                w = o.getInt("w"), h = o.getInt("h"),
                fontSize = o.optDouble("fontSize", 16.0).toFloat(),
                text = o.optString("text", ""),
                colorSpansJson = o.optString("colorSpans", ""),
                image = o.optString("image", ""),
                rows = o.optInt("rows", 0),
                cols = o.optInt("cols", 0),
                cells = cellsList
            )
        }
    }
}

object ElementsJson {
    fun serialize(list: List<PageElement>): String {
        val arr = JSONArray()
        for (e in list) arr.put(e.toJson())
        return arr.toString()
    }

    fun deserialize(json: String): MutableList<PageElement> {
        val out = mutableListOf<PageElement>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                out.add(PageElement.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun buildSearchText(title: String, dateText: String, freeText: String, list: List<PageElement>): String {
        val sb = StringBuilder()
        sb.append(title).append(' ').append(dateText).append(' ').append(freeText).append(' ')
        for (e in list) {
            when (e.type) {
                TYPE_TEXT -> sb.append(e.text).append(' ')
                TYPE_TABLE -> for (row in e.cells) for (c in row) sb.append(c).append(' ')
            }
        }
        return sb.toString().lowercase()
    }

    fun buildPreviewText(freeText: String, list: List<PageElement>): String {
        val sb = StringBuilder()
        if (freeText.isNotBlank()) sb.append(freeText.trim()).append("  ")
        for (e in list) {
            when (e.type) {
                TYPE_TEXT -> if (e.text.isNotBlank()) sb.append(e.text.trim()).append("  ")
                TYPE_TABLE -> for (row in e.cells) for (c in row) if (c.isNotBlank()) sb.append(c.trim()).append(" ")
                TYPE_IMAGE -> sb.append("[ছবি] ")
            }
            if (sb.length > 140) break
        }
        return sb.toString().take(160)
    }
}
