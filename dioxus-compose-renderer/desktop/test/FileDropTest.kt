package dioxus.compose.test

import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.ui.joinPaths
import dioxus.compose.ui.node.NodeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How several paths travel as one string.
 *
 * The requirement asks for two files to arrive as two paths in one event, which means a
 * separator, and the separator has to be a byte no path may contain. A newline is not
 * that: a file called "notes\nfor tuesday" is legal on two of the three desktops, and
 * splitting on newlines would deliver it as two files that do not exist.
 */
class FileDropTest {

    @Test
    fun fr27_two_files_travel_as_one_string() {
        val joined = joinPaths(listOf("/tmp/one.txt", "/tmp/two.txt"))
        assertEquals(listOf("/tmp/one.txt", "/tmp/two.txt"), joined.split('\u0000'))
    }

    @Test
    fun fr27_a_path_with_a_newline_in_it_stays_one_path() {
        val awkward = "/tmp/notes\nfor tuesday.txt"
        val joined = joinPaths(listOf(awkward, "/tmp/plain.txt"))
        assertEquals(listOf(awkward, "/tmp/plain.txt"), joined.split('\u0000'))
    }

    @Test
    fun fr27_a_path_the_platform_could_not_give_is_dropped_and_the_rest_arrive() {
        // An empty string is what a path the toolkit could not turn into text comes back
        // as. Losing that one file must not lose the others.
        val joined = joinPaths(listOf("/tmp/kept.txt", "", "/tmp/also-kept.txt"))
        assertEquals(listOf("/tmp/kept.txt", "/tmp/also-kept.txt"), joined.split('\u0000'))
    }

    @Test
    fun fr27_nothing_dropped_is_an_empty_string() {
        assertEquals("", joinPaths(emptyList()))
    }
}

/**
 * Which nodes are places files may be dropped.
 *
 * The willingness is the widget, not a property: a handler is attached whether or not the
 * screen supplied one, so on any container every container in a tree would pay records
 * for saying nothing about files.
 */
class FileDropTargetTest {

    @Test
    fun fr27_a_drop_target_carries_the_file_handlers() {
        for (property in listOf(PropertyKind.OnFilesEntered, PropertyKind.OnFilesDropped)) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.FileDropTarget, property))
        }
    }

    @Test
    fun fr27_a_plain_container_is_not_a_drop_target() {
        val containers =
            listOf(WidgetKind.Box, WidgetKind.Column, WidgetKind.Card, WidgetKind.Surface)
        for (widget in containers) {
            for (property in listOf(PropertyKind.OnFilesEntered, PropertyKind.OnFilesDropped)) {
                assertFalse(NodeTable.supportsProperty(widget, property))
            }
        }
    }

    @Test
    fun fr27_a_drop_target_places_its_children_like_a_box() {
        assertTrue(NodeTable.supportsProperty(WidgetKind.FileDropTarget, PropertyKind.Alignment))
    }
}
