package com.csqtt.client

import java.text.Collator
import java.util.Locale
import kotlin.math.abs

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

/**
 * The corner control of «Одно подключение» (the operator, 2026-11-27): that layout has no list, so its card is the way
 * to another connection. [Switch] opens «Профили»; with a single connection there is nothing to switch to and the
 * control is [Add], which opens «Добавить профиль».
 */
internal enum class HomePick { Switch, Add }

/** Which of the two the control is. A hidden profile counts: it is found, and shown again, in the sheet. */
internal fun homePick(entries: List<ProfileEntry>): HomePick = if (entries.size >= 2) HomePick.Switch else HomePick.Add

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
 * One profile as a list shows it. [peer] with [port], [link] and [subscriptionTitle] are what its second line is made
 * of, by [kind]; [workers] is the streams it is set to. [subscriptionUrl] tells one subscription's nodes from another's.
 * [originalName] is the name it came with, which a rename can be taken back to. [pingMs] is its last on-demand ping,
 * which nothing stores. [lastConnectedAt] and [addedAt] are wall-clock milliseconds, 0 when not known: a profile from
 * before these were kept.
 */
internal data class ProfileEntry(
    val index: Int,
    val name: String,
    val kind: ProfileKind = ProfileKind.OwnServer,
    val peer: String = "",
    val link: String = "",
    val subscriptionTitle: String = "",
    val subscriptionUrl: String = "",
    val originalName: String = name,
    val port: Int = 0,
    val workers: Int = 0,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val pingMs: Int? = null,
    val lastConnectedAt: Long = 0L,
    val addedAt: Long = 0L,
)

/** What a profile's row can do, as its swipe and its ⋮ menu offer it (the canvas, F-ProfileActions, F-NodeActions). */
internal enum class ProfileAction { Show, Rename, Pin, Unpin, Hide, OpenTunnel, Delete }

internal object ProfileMenu {
    /**
     * A row's actions, in the order they are drawn. A subscription's node has no delete: it is hidden, and its
     * subscription is deleted whole (the operator, 2026-11-26). The [selected] profile cannot be hidden, so it is not
     * offered. A hidden one is shown first; pinning and opening wait until it is.
     */
    fun items(entry: ProfileEntry, selected: Boolean = false): List<ProfileAction> = buildList {
        if (entry.hidden) add(ProfileAction.Show)
        add(ProfileAction.Rename)
        if (!entry.hidden) {
            add(if (entry.pinned) ProfileAction.Unpin else ProfileAction.Pin)
            if (!selected) add(ProfileAction.Hide)
            add(ProfileAction.OpenTunnel)
        }
        if (entry.kind != ProfileKind.Subscription) add(ProfileAction.Delete)
    }
}

/**
 * Where a swiped row's strip of actions comes to rest when the finger lifts (the operator, 2026-11-28: «it needs to
 * slide easier»). A place runs from 0, shut, down to `open` — negative, the whole strip shown.
 */
internal object RowSwipe {
    /** The part of the strip's way a slow drag covers before the strip goes on to the other end by itself. */
    const val GO_ON = 1f / 3

    /**
     * A flick — a lift at [flick] px a second or faster, [velocity] negative to the left — decides by its direction
     * alone, however short it was. A slow lift goes by how far the strip was moved from the end the drag found it
     * nearer to ([start]): [GO_ON] of the way or more and it goes on to the other end, less and it returns.
     */
    fun rest(start: Float, value: Float, open: Float, velocity: Float, flick: Float): Float {
        if (velocity <= -flick) return open
        if (velocity >= flick) return 0f
        val from = if (start < open / 2) open else 0f
        val other = if (from == 0f) open else 0f
        return if (abs(value - from) >= abs(open) * GO_ON) other else from
    }
}

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

    /** How many rows Home shows before «Все профили и подписки» (the canvas, F-Home). */
    const val HOME_ROWS = 3

    /**
     * Home's rows out of the [shown] ones: the first [HOME_ROWS] of the order. The [selected] profile is always among
     * them, so the selection is never out of sight: when the order puts it further down it takes the last place.
     */
    fun homeRows(shown: List<ProfileEntry>, selected: Int): List<ProfileEntry> {
        val top = shown.take(HOME_ROWS)
        val chosen = shown.firstOrNull { it.index == selected }
        return if (chosen == null || chosen in top) top else top.dropLast(1) + chosen
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

    /**
     * The user's own order after [moved] is put at [position] among its [group] — one card of the profiles sheet, its
     * rows in the order shown. It goes before the row that follows it there, or after the group's last one; the other
     * groups' rows keep their places around it. Null when the group holds nothing else: nothing moves.
     */
    fun movedInGroup(shown: List<ProfileEntry>, custom: List<Int>, group: List<Int>, moved: Int, position: Int): List<Int>? {
        val others = group.filter { it != moved }
        if (others.isEmpty()) return null
        val flat = shown.map { it.index }.filter { it != moved }
        val at = others.getOrNull(position.coerceAtLeast(0))?.let(flat::indexOf) ?: (flat.indexOf(others.last()) + 1)
        return moved(shown, custom, moved, at)
    }

    fun encode(order: List<Int>): String = order.joinToString(",")

    /** A stored order: what is not a profile index, or comes twice, is dropped. */
    fun decode(stored: String?): List<Int> = stored.orEmpty().split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX }
        .distinct()
}
