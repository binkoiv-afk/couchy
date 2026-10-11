package app.couchy.data

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.couchy.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One person in the family: their own history, places to resume, lists and YouTube follows. */
data class Profile(val id: String, val name: String, val color: Long)

/**
 * Profiles keep each person's things apart: every profile has its own library database, lists
 * and sync file. Settings, playlists and downloads stay shared. Switching restarts Couchy.
 */
object Profiles {
    const val MAIN = "main"
    private val colors = listOf(0xFF4F8EF7, 0xFFE5484D, 0xFF30A46C, 0xFFF5A524, 0xFF8E4EC6, 0xFF12A594, 0xFFE93D82)

    private fun prefs(context: Context) = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun list(context: Context): List<Profile> {
        val stored = runCatching { JSONArray(prefs(context).getString("list", "[]")) }.getOrDefault(JSONArray())
        val others = (0 until stored.length()).mapNotNull { i ->
            stored.optJSONObject(i)?.let { Profile(it.getString("id"), it.optString("name"), it.optLong("color", colors[0])) }
        }
        val main = others.firstOrNull { it.id == MAIN } ?: Profile(MAIN, "Основной", colors[0])
        return listOf(main) + others.filter { it.id != MAIN }
    }

    fun currentId(context: Context): String = prefs(context).getString("current", MAIN)!!.takeIf { id -> list(context).any { it.id == id } } ?: MAIN

    fun current(context: Context): Profile = list(context).first { it.id == currentId(context) }

    /** Added to file names: nothing for the first profile, so what was there before stays its own. */
    fun suffix(context: Context): String = currentId(context).let { if (it == MAIN) "" else "-$it" }

    private fun save(context: Context, list: List<Profile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        prefs(context).edit().putString("list", a.toString()).apply()
    }

    fun add(context: Context, name: String): Profile {
        val all = list(context)
        val p = Profile(System.currentTimeMillis().toString(36), name.trim().ifBlank { "Профиль ${all.size + 1}" }, colors[all.size % colors.size])
        save(context, all + p)
        return p
    }

    fun rename(context: Context, id: String, name: String) {
        if (name.isBlank()) return
        save(context, list(context).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /** Removes a profile and everything it kept; the main one and the one in use stay. */
    fun remove(context: Context, id: String) {
        if (id == MAIN || id == currentId(context)) return
        save(context, list(context).filter { it.id != id })
        val s = "-$id"
        context.deleteDatabase("couchy$s.db")
        listOf("marks$s", "youtube$s").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
        }
    }

    /** Asked on launch when the family has more than one profile, but not right after switching. */
    fun shouldAsk(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean("switched", false)) {
            p.edit().putBoolean("switched", false).apply()
            return false
        }
        return list(context).size > 1
    }

    /** Starts Couchy again in [id]'s profile. */
    fun switch(activity: Activity, id: String) {
        if (id == currentId(activity)) return
        prefs(activity).edit().putString("current", id).putBoolean("switched", true).commit()
        activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }
}
