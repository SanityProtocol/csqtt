package com.csqtt.client

import java.text.Collator
import java.util.Locale

/**
 * Home's two layouts (P3-PLAN §1.3a; the operator, 2026-11-26): the one connection, which is the default, or the list of
 * profiles. The header's switch on «Главная» goes from one to the other, and the choice is kept.
 */
internal enum class HomeLayout(val stored: String) {
    One("one"),
    List("list");

    val other: HomeLayout get() = if (this == One) List else One

    companion object {
        /** Nothing stored, or a value this build does not know, is the default. */
        fun fromStored(value: String?): HomeLayout = entries.firstOrNull { it.stored == value } ?: One
    }
}

/** How the lists of profiles are ordered (the canvas, F-HomeSort): the user's own order unless they pick another. */
internal enum class ProfileSort(val stored: String) {
    Custom("custom"),
    Ping("ping"),
    Name("name"),
    Recent("recent"),
    Added("added");

    companion object {
        fun fromStored(value: String?): ProfileSort = entries.firstOrNull { it.stored == value } ?: Custom
    }
}

internal enum class ProfileKind { OwnServer, Link, Subscription }

/**
 * One profile as a list shows it. [peer], [link] and [subscriptionTitle] are what its second line is made of, by [kind].
 * [pingMs] is its last on-demand ping, which nothing stores. [lastConnectedAt] and [addedAt] are wall-clock milliseconds,
 * 0 when not known: a profile from before these were kept.
 */
internal data class ProfileEntry(
    val index: Int,
    val name: String,
    val kind: ProfileKind = ProfileKind.OwnServer,
    val peer: String = "",
    val link: String = "",
    val subscriptionTitle: String = "",
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val pingMs: Int? = null,
    val lastConnectedAt: Long = 0L,
    val addedAt: Long = 0L,
)

internal object ProfileOrder {
    /**
     * The profiles a list shows, in [sort]'s order. A hidden profile is in none of them. Pinned ones come first, in the
     * same order among themselves. [custom] is the user's own order, by profile index: a profile it does not name yet (a
     * new one) comes after those it names. What an order cannot tell apart keeps the user's own order.
     */
    fun shown(entries: List<ProfileEntry>, sort: ProfileSort, custom: List<Int>): List<ProfileEntry> {
        val place = custom.withIndex().associate { (position, index) -> index to position }
        val own = compareBy<ProfileEntry>({ place[it.index] ?: Int.MAX_VALUE }, { it.index })
        val order = when (sort) {
            ProfileSort.Custom -> own
            // no answer and never pinged come last
            ProfileSort.Ping -> compareBy<ProfileEntry> { it.pingMs ?: Int.MAX_VALUE }.then(own)
            ProfileSort.Name -> {
                // as Russian orders names, capitals and small letters together; its own instance, which no other thread has
                val names = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
                Comparator<ProfileEntry> { a, b -> names.compare(a.name, b.name) }.then(own)
            }
            // the latest first; one never connected, or added before the date was kept, after those that have it
            ProfileSort.Recent -> compareByDescending<ProfileEntry> { it.lastConnectedAt }.then(own)
            ProfileSort.Added -> compareByDescending<ProfileEntry> { it.addedAt }.then(own)
        }
        return entries.filterNot { it.hidden }.sortedWith(compareByDescending<ProfileEntry> { it.pinned }.then(order))
    }

    /**
     * The user's own order after the profile [moved] is put at [position] among the [shown] ones (a drag): the rows as
     * they stand with that one moved, then the profiles the list does not show, in the order they had in [custom].
     */
    fun moved(shown: List<ProfileEntry>, custom: List<Int>, moved: Int, position: Int): List<Int> {
        val rows = shown.map { it.index }.filter { it != moved }.toMutableList()
        rows.add(position.coerceIn(0, rows.size), moved)
        return rows + custom.filter { it !in rows }
    }

    fun encode(order: List<Int>): String = order.joinToString(",")

    /** A stored order: what is not a profile index, or comes twice, is dropped. */
    fun decode(stored: String?): List<Int> = stored.orEmpty().split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX }
        .distinct()
}
