package com.vamshi.aiassistant.overlay

/** Display groups, not semantic turns: Live can deliver overlapping and late fragments. */
internal class LiveCaptions {
    data class Caption(val id: Long, val human: Boolean, val text: String)
    private data class Fragment(val text: String, val start: Long, val end: Long)
    private data class Group(val id: Long, val human: Boolean, val fragments: MutableList<Fragment>)
    private val groups = mutableListOf<Group>()
    private var nextId = 0L

    fun append(human: Boolean, text: String, startMs: Long, endMs: Long): Caption {
        val group = groups.filter { it.human == human }.minByOrNull { group ->
            distance(group, startMs, endMs)
        }?.takeIf { distance(it, startMs, endMs) <= 1_000 }
            ?: Group(nextId++, human, mutableListOf()).also(groups::add)
        group.fragments += Fragment(text, startMs, endMs)
        return Caption(group.id, human, group.fragments.sortedBy { it.start }.joinToString("") { it.text })
    }

    private fun distance(group: Group, start: Long, end: Long): Long =
        maxOf(0, start - group.fragments.maxOf { it.end }, group.fragments.minOf { it.start } - end)
}
