/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.yasvara.pentas.framefx.FramefxBroker
import org.yasvara.pentas.framefx.GameProcess
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.video.VideoSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Kelir, per game: three slots kept here, the running one handed to the game through the framefx
 * broker. A game with any filters in its slots is armed for its next start. A video app's slots
 * go to its decoder's frames instead: live, and never onto its own UI.
 */
@Singleton
class Kelir @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ActiveSessionStore,
) {

    private val store = KelirStore(File(context.filesDir, "kelir"))
    private val revision = AtomicInteger((System.currentTimeMillis() / 1000).toInt())
    private val _stored = MutableStateFlow(0)
    private val published = ConcurrentHashMap<String, Int>()

    /** Bumps on every stored change, for views of another editor's game. */
    val stored: StateFlow<Int> = _stored

    private val live: KelirLive by lazy {
        val handler = Handler(Looper.getMainLooper())
        val hz = panelHz()
        KelirLive(
            object : KelirLive.Clock {
                override fun now() = SystemClock.uptimeMillis()
                override fun after(ms: Long, block: () -> Unit) {
                    handler.postDelayed(block, ms)
                }
            },
            frameMs = (1000f / hz).roundToLong().coerceAtLeast(1),
            publish = { publish(it) },
            persist = {
                store.save(it.pkg, it.slots)
                _stored.value++
            },
        )
    }

    fun slots(pkg: String): KelirSlots = store.load(pkg)

    /** Games with slots stored, to copy from. */
    fun games(): List<String> = store.games()

    /** The sidebar's one tap: the next slot holding filters, then Off. */
    fun cycle(pkg: String): KelirSlots = slots(pkg).cycle().also { commit(pkg, it) }

    /** While a control moves: the game follows within a frame; nothing is stored. */
    fun preview(pkg: String, slots: KelirSlots, view: KelirCodec.View) =
        live.preview(KelirLive.Edit(pkg, slots, view))

    /** A finished change: published and stored. */
    fun commit(pkg: String, slots: KelirSlots, view: KelirCodec.View = KelirCodec.View()) =
        live.commit(KelirLive.Edit(pkg, slots, view))

    /** A video app, not a game: this session says so, or an earlier one armed it that way. */
    fun video(pkg: String): Boolean =
        (session.mode == SidebarMode.MODE_VIDEO && session.currentPackage == pkg) ||
            FramefxBroker.getSection(pkg, KelirCodec.VIDEO_SECTION_ID) != null ||
            FramefxBroker.getSection(pkg, VideoSettings.SECTION_ID) != null

    fun videoStatus(pkg: String): VideoStatus? =
        FramefxBroker.statusPages(pkg).mapNotNull(VideoStatus::decode).maxByOrNull { it.frames }

    /** A video app's decoder picks a change up by itself, within a second of playing. */
    fun needsRestart(pkg: String): Boolean = !video(pkg) && GameProcess.needsRestart(context, pkg)

    fun restart(pkg: String) = GameProcess.restart(context, pkg)

    /** The panel's live refresh rate: the frame Kelir's cost is measured against. */
    fun panelHz(): Float = context.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate ?: 60f

    /** The game is uninstalled. */
    fun forget(pkg: String) {
        store.remove(pkg)
        FramefxBroker.setSection(pkg, KelirCodec.SECTION_ID, ByteArray(0))
        FramefxBroker.setSection(pkg, KelirCodec.VIDEO_SECTION_ID, ByteArray(0))
        FramefxBroker.setSection(pkg, VideoSettings.SECTION_ID, ByteArray(0))
    }

    /** The revision last handed to [pkg]: a status that applied it describes what is shown. */
    fun published(pkg: String): Int = published[pkg] ?: 0

    /** Status of every process of [pkg] that runs armed. */
    fun status(pkg: String): List<KelirStatus?> = FramefxBroker.statusPages(pkg).map(KelirStatus::decode)

    /**
     * Off keeps an empty stack in the channel while any slot holds filters, so the game stays armed
     * and picking a slot again takes effect at once; with every slot empty the section goes. A
     * video app's stays once written: its running process holds this channel, and removing the last
     * section would leave it on a dead one.
     */
    private fun publish(edit: KelirLive.Edit): Boolean {
        val slots = edit.slots
        val empty = slots.slots.all { it.nodes.isEmpty() }
        val video = video(edit.pkg)
        val section = if (video) KelirCodec.VIDEO_SECTION_ID else KelirCodec.SECTION_ID
        if (video && empty && FramefxBroker.getSection(edit.pkg, section) == null) return true
        val bytes = if (empty && !video) {
            ByteArray(0)
        } else {
            val r = revision.incrementAndGet()
            published[edit.pkg] = r
            KelirCodec.encode(slots.running ?: KelirStack(), edit.view, r)
        }
        return FramefxBroker.setSection(edit.pkg, section, bytes)
    }
}
