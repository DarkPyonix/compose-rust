package dev.darkpyonix.composerust.ui

private const val PATH_SEPARATOR = '\u0000'

/**
 * Joins the paths of dropped files into the one string an event carries.
 *
 * The separator is a byte no path may contain on any desktop. A newline is not that: a file
 * called "notes\nfor tuesday" is legal on two of the three desktops, and splitting on
 * newlines would deliver it as two files that do not exist. A path the platform could not
 * turn into text arrives empty and is dropped, so the others still arrive.
 */
internal fun joinPaths(paths: List<String>): String =
    paths.filter { it.isNotEmpty() }.joinToString(PATH_SEPARATOR.toString())
