/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.framefx

import android.os.Parcel
import android.os.ServiceManager

/**
 * system_server's framefx broker: it keeps each game's channel sections, arms the game for its
 * next start, and hands live changes to a game already armed. Layouts are
 * frameworks/libs/framefx/include/framefx/channel.h.
 */
object FramefxBroker {
    private const val SERVICE = "yasvara_framefx"
    private const val DESCRIPTOR = "yasvara.framefx.IBroker"
    private const val SET_SECTION = 2
    private const val GET_SECTION = 3
    private const val STATUS = 4

    // Sections that arm a game: Luwes, Kelir, upscaling written on, and Lapis with its text tap.
    // Otherwise Lapis and upscaling off only qualify what those run with: kept alone, they would arm
    // the game for nothing.
    private const val LUWES = 1
    private const val KELIR = 2
    private const val LAPIS = 3
    private const val UPSCALE = 4
    private const val VIDEO_KELIR = 6
    private const val VIDEO = 7
    private const val UPSCALE_MODE = 1  // 0 is off, whatever filter and sharpness it carries
    private const val LAPIS_FLAGS = 2
    private const val LAPIS_TEXT_TAP = 2

    /**
     * Empty bytes remove the section; a package left without sections is disarmed. A section
     * that only qualifies others never arms the game alone, and goes with the last that arms it.
     */
    fun setSection(pkg: String, id: Int, bytes: ByteArray): Boolean {
        if (bytes.isNotEmpty() && !arms(id, bytes) && !armed(pkg)) return false
        val ok = write(pkg, id, bytes)
        if (ok && !armed(pkg)) {
            for (other in intArrayOf(LAPIS, UPSCALE)) {
                if (getSection(pkg, other) != null) write(pkg, other, ByteArray(0))
            }
        }
        return ok
    }

    /** Luwes or Kelir arms the game: what upscaling's auto rides on. */
    fun armedFor(pkg: String): Boolean = getSection(pkg, LUWES) != null || getSection(pkg, KELIR) != null

    private fun arms(id: Int, bytes: ByteArray) =
        id == LUWES || id == KELIR || id == VIDEO_KELIR || id == VIDEO ||
            (id == UPSCALE && bytes.getOrNull(UPSCALE_MODE)?.toInt() != 0) ||
            (id == LAPIS && bytes.size > LAPIS_FLAGS && bytes[LAPIS_FLAGS].toInt() and LAPIS_TEXT_TAP != 0)

    private fun armed(pkg: String) =
        armedFor(pkg) || getSection(pkg, VIDEO_KELIR) != null || getSection(pkg, VIDEO) != null ||
            getSection(pkg, UPSCALE)?.let { arms(UPSCALE, it) } == true ||
            getSection(pkg, LAPIS)?.let { arms(LAPIS, it) } == true

    private fun write(pkg: String, id: Int, bytes: ByteArray): Boolean =
        call(SET_SECTION, { writeString(pkg); writeInt(id); writeByteArray(bytes) }) { readInt() == 0 } == true

    fun getSection(pkg: String, id: Int): ByteArray? =
        call(GET_SECTION, { writeString(pkg); writeInt(id) }) { if (readInt() == 0) createByteArray() else null }

    /** The status page of every process of [pkg] that opened its channel, i.e. runs armed. */
    fun statusPages(pkg: String): List<ByteArray> =
        call(STATUS, { writeString(pkg) }) {
            List(readInt()) {
                readInt()
                createByteArray() ?: ByteArray(0)
            }
        }.orEmpty()

    private fun <T> call(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T? {
        val broker = ServiceManager.checkService(SERVICE) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            broker.transact(code, data, reply, 0)
            reply.read()
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
