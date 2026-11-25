package com.sench.scroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/** Кадры видео: уменьшение и склейка в одну раскадровку с номерами. */
object Frames {
    const val CELL_W = 240

    /** Обрезаем полоски интерфейса сверху и снизу и уменьшаем до ширины ячейки. */
    fun cell(full: Bitmap): Bitmap {
        val top = (full.height * 0.06).toInt()
        val bottom = (full.height * 0.91).toInt()
        val src = Rect(0, top, full.width, bottom)
        val h = (CELL_W.toFloat() * src.height() / src.width()).toInt()
        val out = Bitmap.createBitmap(CELL_W, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(full, src, Rect(0, 0, CELL_W, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun cols(n: Int) = when { n <= 2 -> n; n <= 4 -> 2; else -> 3 }

    /** Сетка кадров с номерами 1, 2, 3… */
    fun sheet(cells: List<Bitmap>): Bitmap {
        if (cells.size == 1) return cells[0]
        val c = cols(cells.size); val r = (cells.size + c - 1) / c
        val cw = cells[0].width; val ch = cells.maxOf { it.height }; val gap = 4
        val out = Bitmap.createBitmap(c * cw + (c - 1) * gap, r * ch + (r - 1) * gap, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(Color.BLACK)
        val box = Paint().apply { color = Color.parseColor("#FF1F3D") }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
        cells.forEachIndexed { i, b ->
            val x = (i % c) * (cw + gap); val y = (i / c) * (ch + gap)
            cv.drawBitmap(b, x.toFloat(), y.toFloat(), null)
            cv.drawRect(x.toFloat(), y.toFloat(), x + 34f, y + 34f, box)
            cv.drawText("${i + 1}", x + 9f, y + 27f, txt)
        }
        return out
    }
}
