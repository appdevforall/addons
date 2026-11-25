package org.appdevforall.customidebuilder.core

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Checks a template bundle (.cgt) before it is accepted. A .cgt is a zip that Code on the Go unpacks into a
 * folder, so a file that only starts with the zip signature is not enough: it has to open as a zip, and none of
 * its entries may name a place outside the folder it is unpacked into.
 */
object TemplateCheck {
    /** Why [file] is not a template bundle this plugin will carry, or null when it opens and every entry name is safe. */
    fun problem(file: File): String? {
        val zip =
            try {
                ZipFile(file)
            } catch (e: IOException) {
                return "not a template bundle (it is not a readable zip file)"
            } catch (e: RuntimeException) {
                return "not a template bundle (it is not a readable zip file)"
            }
        zip.use {
            try {
                for (entry in it.entries()) unsafeName(entry.name)?.let { why -> return "not a template bundle (an entry name is $why)" }
            } catch (e: IOException) {
                return "not a template bundle (its entries cannot be read)"
            } catch (e: RuntimeException) {
                return "not a template bundle (its entries cannot be read)"
            }
        }
        return null
    }

    /** What is wrong with the entry [name] as a path to unpack, or null. */
    fun unsafeName(name: String): String? =
        when {
            name.isEmpty() -> "empty"
            name.any { it == '\\' } -> "written with a backslash"
            name.any { it == '\u0000' || it.isISOControl() } -> "written with a control character"
            name.startsWith("/") -> "an absolute path"
            name.length >= 2 && name[1] == ':' && name[0].isLetter() -> "an absolute path"
            name.split('/').any { it == ".." } -> "a path that climbs out of the folder (..)"
            else -> null
        }
}
