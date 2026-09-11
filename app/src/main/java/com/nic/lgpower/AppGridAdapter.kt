package com.nic.lgpower

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class AppGridAdapter(
    private val context: Context,
    private val client: WebOsClient,
    private val maxSelected: Int = 8,
    private val onChanged: (List<WebOsClient.TvApp>) -> Unit = {}
) : RecyclerView.Adapter<AppGridAdapter.VH>() {

    /** Set by the activity; a long-press on a selected app hands the holder to ItemTouchHelper. */
    var onStartDrag: ((VH) -> Unit)? = null

    private val selected   = mutableListOf<WebOsClient.TvApp>()
    private val unselected = mutableListOf<WebOsClient.TvApp>()
    private val uiHandler  = Handler(Looper.getMainLooper())

    val selectedApps:  List<WebOsClient.TvApp> get() = selected.toList()
    val selectedCount: Int get() = selected.size

    fun setApps(all: List<WebOsClient.TvApp>, currentSelection: List<WebOsClient.TvApp>) {
        val selectedIds = currentSelection.map { it.id }.toSet()
        selected.clear()
        unselected.clear()
        currentSelection.mapNotNull { s -> all.find { it.id == s.id } }.forEach { selected.add(it) }
        all.filter { it.id !in selectedIds }.forEach { unselected.add(it) }
        notifyDataSetChanged()
    }

    override fun getItemCount() = selected.size + unselected.size

    fun getApp(pos: Int): WebOsClient.TvApp =
        if (pos < selected.size) selected[pos] else unselected[pos - selected.size]

    fun isSelected(pos: Int) = pos in 0 until selected.size

    /** Rebind a single item after its icon has been cached. */
    fun refreshItem(appId: String) {
        val pos = (0 until itemCount).firstOrNull { getApp(it).id == appId } ?: return
        notifyItemChanged(pos)
    }

    /** Called by ItemTouchHelper during drag: only the move, badges and saving wait for [commitOrder]. */
    fun moveItem(from: Int, to: Int) {
        val item = selected.removeAt(from)
        selected.add(to, item)
        notifyItemMoved(from, to)
    }

    /** Drag finished: renumber the badges and persist. Rebinding mid-drag made the drag stutter. */
    fun commitOrder() {
        // Payload rebind touches only the badge; a full rebind swaps holders through the
        // change animation, which flickers icons and strands a scaled holder for reuse
        notifyItemRangeChanged(0, selected.size, PAYLOAD_BADGE)
        onChanged(selected.toList())
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (PAYLOAD_BADGE in payloads) bindBadge(holder, position) else super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindBadge(holder: VH, position: Int) {
        val app = getApp(position)
        if (isSelected(position)) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = (selected.indexOf(app) + 1).toString()
        } else {
            holder.badge.visibility = View.GONE
        }
    }

    companion object {
        private const val PAYLOAD_BADGE = "badge"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(context).inflate(R.layout.item_app_grid, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getApp(position)
        val sel = isSelected(position)

        holder.label.text = app.title
        holder.icon.alpha = if (sel) 1f else 0.5f
        // A holder recycled mid drag-animation would otherwise keep the lifted look
        holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f; holder.itemView.alpha = 1f
        bindBadge(holder, position)

        holder.icon.setImageBitmap(makeLetterBitmap(app.title, 160))

        Thread {
            val bmp = client.loadCachedIcon(app.id) ?: return@Thread
            val circle = toCircle(bmp)
            uiHandler.post {
                if (holder.bindingAdapterPosition == position) holder.icon.setImageBitmap(circle)
            }
        }.start()

        // Explicit drag start: ItemTouchHelper's own long-press detection is easily lost
        // to the scroll view this grid lives in
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos >= 0 && isSelected(pos)) { onStartDrag?.invoke(holder); true } else false
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos < 0) return@setOnClickListener
            val clickedApp = getApp(pos)
            if (isSelected(pos)) {
                selected.remove(clickedApp)
                val idx = unselected.indexOfFirst { it.title.lowercase() > clickedApp.title.lowercase() }
                unselected.add(if (idx == -1) unselected.size else idx, clickedApp)
            } else {
                if (selected.size >= maxSelected) {
                    Toast.makeText(context, "Max $maxSelected shortcuts", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                unselected.remove(clickedApp)
                selected.add(clickedApp)
            }
            notifyDataSetChanged()
            onChanged(selected.toList())
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon:  ImageView = view.findViewById(R.id.app_icon)
        val badge: TextView  = view.findViewById(R.id.app_badge)
        val label: TextView  = view.findViewById(R.id.app_label)
    }

    private fun toCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val sx = (src.width - size) / 2; val sy = (src.height - size) / 2
        canvas.drawBitmap(src, Rect(sx, sy, sx + size, sy + size),
            RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        return out
    }

    private fun makeLetterBitmap(title: String, size: Int): Bitmap {
        val theme  = ThemeManager.getActiveTheme(context)
        val bmp    = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.windowBg }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.apply {
            color     = theme.secondaryText
            textSize  = size * 0.42f
            textAlign = Paint.Align.CENTER
            typeface  = Typeface.DEFAULT_BOLD
        }
        val letter = title.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bmp
    }
}
