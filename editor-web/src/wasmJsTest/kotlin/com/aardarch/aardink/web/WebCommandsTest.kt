/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aardarch.aardink.web

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Monaco's trigger, addCommand, addAction and getAction, and the keybindings they take. */
class WebCommandsTest {

    private val mounted = mutableListOf<AardinkEditorHandle>()
    private val containers = mutableListOf<HTMLElement>()

    @AfterTest
    fun tearDown() {
        mounted.forEach(AardinkWeb::dispose)
        containers.forEach { it.remove() }
    }

    private fun mount(text: String): AardinkEditorHandle {
        val div = document.createElement("div") as HTMLElement
        div.id = "aardink-commands-test-${containers.size}"
        div.style.width = "600px"
        div.style.height = "400px"
        document.body!!.appendChild(div)
        containers += div
        return AardinkWeb.mount(div.id, text, WebEditorOptions(language = "xml")).also { mounted += it }
    }

    @Test
    fun `keybindings are read from text and from Monaco's numbers`() {
        assertEquals(KeyChord(Key.S, ctrlCmd = true), KeyChord.parse("CtrlCmd+S"))
        assertEquals(KeyChord(Key.F8, shift = true), KeyChord.parse("Shift+F8"))
        assertEquals(KeyChord(Key.Enter, alt = true, winCtrl = true), KeyChord.parse("ctrl+alt+enter"))
        assertEquals(KeyChord(Key.Period, ctrlCmd = true), KeyChord.parse("CtrlCmd+."))
        // KeyMod.CtrlCmd | KeyCode.KeyS, KeyMod.Shift | KeyMod.Alt | KeyCode.KeyF
        assertEquals(KeyChord(Key.S, ctrlCmd = true), KeyChord.parse((2048 or 49).toString()))
        assertEquals(KeyChord(Key.F, shift = true, alt = true), KeyChord.parse((1024 or 512 or 36).toString()))
        assertNull(KeyChord.parse("CtrlCmd+NoSuchKey"))
        assertNull(KeyChord.parse("Shift"))
    }

    @Test
    fun `trigger runs the editor's own actions by Monaco's ids`() {
        val handle = mount("<a/>")
        handle.state.value.selection = TextRange(4)
        assertTrue(AardinkWeb.trigger(handle, "editor.action.insertSnippet", """{ "snippet": "<b>$0</b>" }"""))
        assertEquals("<a/><b></b>", AardinkWeb.getValue(handle))
        assertTrue(AardinkWeb.trigger(handle, "undo"))
        assertEquals("<a/>", AardinkWeb.getValue(handle))
        assertFalse(AardinkWeb.trigger(handle, "no.such.action"))
        assertEquals("Format Document", AardinkWeb.actionLabel(handle, "editor.action.formatDocument"))
        assertNull(AardinkWeb.actionLabel(handle, "no.such.action"))
    }

    @Test
    fun `actions and commands the host adds run by trigger, and go away`() {
        val handle = mount("")
        var saved = 0
        AardinkWeb.addAction(handle, "app.save", "Save", listOf("CtrlCmd+S"), inContextMenu = true) { saved++ }
        assertEquals("Save", AardinkWeb.actionLabel(handle, "app.save"))
        assertTrue(AardinkWeb.canTrigger(handle, "app.save"))
        assertTrue(AardinkWeb.trigger(handle, "app.save"))
        assertEquals(1, saved)
        AardinkWeb.removeAction(handle, "app.save")
        assertFalse(AardinkWeb.trigger(handle, "app.save"))

        var pressed = 0
        val id = AardinkWeb.addCommand(handle, "F8") { pressed++ }
        assertTrue(id != null && AardinkWeb.trigger(handle, id))
        assertEquals(1, pressed)
        assertNull(AardinkWeb.addCommand(handle, "Ctrl+Nonsense") {})
    }
}
