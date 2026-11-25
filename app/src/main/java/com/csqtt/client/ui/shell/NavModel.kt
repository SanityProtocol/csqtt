package com.csqtt.client.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** The tabs in the bar's order. «Сервер» is there for a user with a server; the other four for everyone (D28, D29). */
internal enum class Tab { Home, Tunnel, Server, Logs, Routes }

/**
 * The bar (K4, D20, D29): «Сервер» for anyone with a server, and while it is the open tab — so a tab restored before the
 * store has answered is never taken away (§4.2 rule 3), and the way in for a first server keeps it while it is used.
 */
internal fun tabsFor(hasServer: Boolean, open: Tab): List<Tab> =
    Tab.entries.filter { it != Tab.Server || hasServer || open == Tab.Server }

/** What an open overlay is: a screen over the tabs, or a sheet or dialog over whatever is open. */
internal enum class Layer { Screen, Sheet, Dialog }

/**
 * One open overlay: a route of the table ([Routes]), its layer and a short argument, an id or a pasted link. Never a
 * list (the saved state has a size limit), never a password (§4.2 rule 2: see [SecretDrafts]).
 */
internal data class Dest(val route: String, val layer: Layer, val arg: String = "") {
    /**
     * The key of this overlay's group and of its saved state: a route is open once, so its name and argument say which.
     * Strings, because the key must be the same in every process: Compose finds a group's saved state again by the hash
     * of its key, and after a process death that is all that is left to find a sheet's draft by.
     */
    val stateKey: String get() = route + SEPARATOR + arg

    /** The same in every process too, for any use of a [Dest] as a key: an enum's own hash is its identity, new each time. */
    override fun hashCode(): Int = 31 * (31 * route.hashCode() + layer.ordinal) + arg.hashCode()

    private companion object {
        const val SEPARATOR = "\u0000"
    }
}

/**
 * Where the user is (§4.2 rule 2): the open tab and the overlays over it. It outlives a recreate as a ViewModel and a
 * process death through [saved]; an overlay's own drafts are its rememberSaveable state.
 */
internal class NavModel(private val saved: SavedStateHandle) : ViewModel() {
    var tab: Tab by mutableStateOf(saved.get<String>(TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.Home)
        private set

    /**
     * The overlays, the last one on top: the screens, then the sheets and dialogs. A sheet or a dialog is a window over
     * everything, so it stays above a screen opened after it, and is answered or closed first.
     */
    var stack: List<Dest> by mutableStateOf(decode(saved.get<ArrayList<String>>(STACK)))
        private set

    /** Shows [tab]: a screen over the tabs would hide it, so the screens close. A question in a dialog stays. */
    fun select(tab: Tab) {
        if (stack.any { it.layer == Layer.Screen }) put(stack.filter { it.layer != Layer.Screen })
        this.tab = tab
        saved[TAB] = tab.name
    }

    /**
     * Opens [dest]: a sheet or a dialog on top, a screen on top of the screens. A route is open once: opened again, it
     * takes the place of the first (a second link replaces the one still asked about).
     */
    fun open(dest: Dest) {
        val others = stack.filter { it.route != dest.route }
        val (screens, windows) = others.partition { it.layer == Layer.Screen }
        put(if (dest.layer == Layer.Screen) screens + dest + windows else others + dest)
    }

    fun close(dest: Dest) = put(stack - dest)

    /** Whether Back has something to do here; when not, it leaves the app. */
    val canGoBack: Boolean get() = stack.isNotEmpty() || tab != Tab.Home

    /**
     * Back (§4.2 rule 4) closes what is on top: a sheet or a dialog, then the screen under it; with nothing open it
     * returns to «Главная». False when there is nothing left to do, and Back leaves the app.
     */
    fun back(): Boolean = when {
        stack.isNotEmpty() -> { put(stack.dropLast(1)); true }
        tab != Tab.Home -> { select(Tab.Home); true }
        else -> false
    }

    private fun put(next: List<Dest>) {
        stack = next
        saved[STACK] = ArrayList(next.map { listOf(it.layer.name, it.route, it.arg).joinToString(SEPARATOR) })
    }

    private companion object {
        const val TAB = "tab"
        const val STACK = "stack"
        const val SEPARATOR = "\u0000"

        /** An entry another build saved and this one cannot read is dropped, not guessed. */
        fun decode(stored: List<String>?): List<Dest> = stored.orEmpty().mapNotNull { entry ->
            val parts = entry.split(SEPARATOR, limit = 3)
            val layer = Layer.entries.firstOrNull { it.name == parts[0] }
            if (parts.size == 3 && layer != null) Dest(parts[1], layer, parts[2]) else null
        }
    }
}

/**
 * The text of password fields being edited (M-174), by the field's key. It is kept in memory for the life of the process
 * and nowhere else: not in [NavModel]'s saved state, not in rememberSaveable. A recreate finds the draft still here,
 * masked again; a process death loses it, and the form shows what is stored. The form clears its keys when it is done.
 */
internal object SecretDrafts {
    private val drafts = mutableStateMapOf<String, String>()

    operator fun get(key: String): String = drafts[key].orEmpty()

    operator fun set(key: String, value: String) {
        if (value.isEmpty()) drafts.remove(key) else drafts[key] = value
    }

    /** A form that saved or was cancelled drops its drafts: every key that starts with [prefix]. */
    fun clear(prefix: String) {
        drafts.keys.filter { it.startsWith(prefix) }.forEach(drafts::remove)
    }
}
