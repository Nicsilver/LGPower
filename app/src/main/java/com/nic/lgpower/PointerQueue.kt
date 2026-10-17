package com.nic.lgpower

/**
 * Input that arrives before the pointer socket is open waits here and goes out on open.
 * Not thread-safe: the owner guards it.
 *
 * Moves and scrolls describe where the cursor was a moment ago, so they are dropped once
 * stale. A click is only meaningful at the cursor position the user saw, so it follows the
 * same age limit and is also dropped behind any dropped move; a button press stands alone
 * and always survives.
 */
internal class PointerQueue(
    private val clock: () -> Long,
    private val maxFrames: Int,
    private val staleMs: Long,
) {
    enum class Kind { KEY, CLICK, MOVE, SCROLL }

    private class Frame(val text: String, val kind: Kind, val at: Long)

    private val frames = ArrayList<Frame>()
    private var tailMove: IntArray? = null
    private var droppedMove = false

    val size get() = frames.size

    fun add(kind: Kind, text: String) {
        require(kind != Kind.MOVE) { "moves go through addMove" }
        append(Frame(text, kind, clock()), merges = false)
    }

    /** Consecutive moves fold into one; the folded frame is as fresh as its newest part. */
    fun addMove(dx: Int, dy: Int) {
        val tail = tailMove
        if (tail != null) {
            tail[0] += dx; tail[1] += dy
            frames[frames.lastIndex] = Frame(moveFrame(tail[0], tail[1]), Kind.MOVE, clock())
        } else {
            append(Frame(moveFrame(dx, dy), Kind.MOVE, clock()), merges = true)
            tailMove = intArrayOf(dx, dy)
        }
    }

    /** Frames still worth sending, oldest first. Empties the queue. */
    fun drain(): List<String> {
        val now = clock()
        var movesLost = droppedMove
        val out = ArrayList<String>(frames.size)
        for (f in frames) {
            when (f.kind) {
                Kind.KEY -> out.add(f.text)
                Kind.MOVE, Kind.SCROLL ->
                    if (now - f.at > staleMs) movesLost = true else out.add(f.text)
                // A late click lands wherever the cursor is by then, not where the user tapped
                Kind.CLICK -> if (!movesLost && now - f.at <= staleMs) out.add(f.text)
            }
        }
        clear()
        return out
    }

    fun clear() {
        frames.clear()
        tailMove = null
        droppedMove = false
    }

    private fun append(frame: Frame, merges: Boolean) {
        if (!merges) tailMove = null
        if (frames.size >= maxFrames) {
            // A press can not be recreated later, a stale move can
            val idx = frames.indexOfFirst { it.kind != Kind.KEY }.takeIf { it >= 0 } ?: 0
            val evicted = frames.removeAt(idx)
            if (evicted.kind == Kind.MOVE || evicted.kind == Kind.SCROLL) droppedMove = true
            if (idx == frames.size) tailMove = null
        }
        frames.add(frame)
    }

    companion object {
        fun moveFrame(dx: Int, dy: Int) = "type:move\ndx:$dx\ndy:$dy\n\n"
    }
}
