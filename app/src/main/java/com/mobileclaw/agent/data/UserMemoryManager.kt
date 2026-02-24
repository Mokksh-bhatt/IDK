package com.mobileclaw.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log

/**
 * Persistent memory layer that survives app updates.
 * Uses a separate DataStore from settings so it's never accidentally cleared.
 * 
 * Stores:
 * - Learned contacts (who the user messages/calls frequently)
 * - App preferences (which apps the user opens often)
 * - Task patterns (common task types and how they were completed)
 * - User corrections (things the AI got wrong and how the user corrected it)
 */
private val Context.memoryStore: DataStore<Preferences> by preferencesDataStore(name = "mobileclaw_memory")

class UserMemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "UserMemory"
        private const val MAX_MEMORY_ENTRIES = 50
        private const val MAX_FACTS = 30

        // Keys
        val CONTACTS = stringSetPreferencesKey("frequent_contacts")
        val APPS = stringSetPreferencesKey("frequent_apps")
        val TASK_HISTORY = stringPreferencesKey("task_history")   // JSON array of recent tasks
        val USER_FACTS = stringSetPreferencesKey("user_facts")    // Things learned about the user
    }

    // ===== READ =====

    val frequentContacts: Flow<Set<String>> = context.memoryStore.data.map { prefs ->
        prefs[CONTACTS] ?: emptySet()
    }

    val frequentApps: Flow<Set<String>> = context.memoryStore.data.map { prefs ->
        prefs[APPS] ?: emptySet()
    }

    val userFacts: Flow<Set<String>> = context.memoryStore.data.map { prefs ->
        prefs[USER_FACTS] ?: emptySet()
    }

    val taskHistory: Flow<String> = context.memoryStore.data.map { prefs ->
        prefs[TASK_HISTORY] ?: "[]"
    }

    // ===== WRITE =====

    /**
     * Record that the user contacted someone. Builds a frequency-based list.
     */
    suspend fun recordContact(name: String) {
        context.memoryStore.edit { prefs ->
            val existing = prefs[CONTACTS]?.toMutableSet() ?: mutableSetOf()
            existing.add(name.trim())
            if (existing.size > MAX_MEMORY_ENTRIES) {
                // Keep most recent by removing oldest (sets don't track order, but limit size)
                val trimmed = existing.toList().takeLast(MAX_MEMORY_ENTRIES).toSet()
                prefs[CONTACTS] = trimmed
            } else {
                prefs[CONTACTS] = existing
            }
        }
        Log.d(TAG, "Recorded contact: $name")
    }

    /**
     * Record that the user opened an app.
     */
    suspend fun recordApp(appName: String) {
        context.memoryStore.edit { prefs ->
            val existing = prefs[APPS]?.toMutableSet() ?: mutableSetOf()
            existing.add(appName.trim())
            prefs[APPS] = existing
        }
        Log.d(TAG, "Recorded app: $appName")
    }

    /**
     * Learn a fact about the user (e.g., "User's dad's name is Ravi", "Mom's contact is listed as 'Mama'").
     */
    suspend fun learnFact(fact: String) {
        context.memoryStore.edit { prefs ->
            val existing = prefs[USER_FACTS]?.toMutableSet() ?: mutableSetOf()
            existing.add(fact.trim())
            if (existing.size > MAX_FACTS) {
                prefs[USER_FACTS] = existing.toList().takeLast(MAX_FACTS).toSet()
            } else {
                prefs[USER_FACTS] = existing
            }
        }
        Log.d(TAG, "Learned: $fact")
    }

    /**
     * Record a completed task for pattern learning.
     */
    suspend fun recordTask(taskDescription: String, success: Boolean) {
        val entry = "${if (success) "✓" else "✗"} $taskDescription"
        context.memoryStore.edit { prefs ->
            val history = prefs[TASK_HISTORY] ?: ""
            val lines = history.split("\n").toMutableList()
            lines.add(entry)
            // Keep last 20 tasks
            val trimmed = lines.takeLast(20).joinToString("\n")
            prefs[TASK_HISTORY] = trimmed
        }
    }

    /**
     * Build a memory context string to inject into the AI prompt.
     * This gives the AI knowledge about the user across sessions.
     */
    suspend fun getMemoryContext(): String {
        val sb = StringBuilder()

        val contacts = frequentContacts.first()
        if (contacts.isNotEmpty()) {
            sb.appendLine("Known contacts: ${contacts.joinToString(", ")}")
        }

        val apps = frequentApps.first()
        if (apps.isNotEmpty()) {
            sb.appendLine("Frequently used apps: ${apps.joinToString(", ")}")
        }

        val facts = userFacts.first()
        if (facts.isNotEmpty()) {
            sb.appendLine("User info: ${facts.joinToString(". ")}")
        }

        val history = taskHistory.first()
        if (history.isNotBlank()) {
            val recent = history.split("\n").takeLast(5).joinToString("\n")
            sb.appendLine("Recent tasks:\n$recent")
        }

        return sb.toString().trim()
    }

    /**
     * Extract learnable info from a task description.
     * Called automatically when a task starts.
     */
    suspend fun extractAndLearn(taskDescription: String) {
        val lower = taskDescription.lowercase()

        // Learn contact names from messaging/calling tasks
        val contactPatterns = listOf(
            Regex("(?:message|msg|text|call|phone|whatsapp|ring)\\s+(?:my\\s+)?(\\w+)", RegexOption.IGNORE_CASE),
            Regex("(?:send|write)\\s+(?:a\\s+)?(?:message|msg|text)\\s+to\\s+(?:my\\s+)?(\\w+)", RegexOption.IGNORE_CASE),
        )
        for (pattern in contactPatterns) {
            val match = pattern.find(taskDescription)
            if (match != null) {
                val contact = match.groupValues[1]
                if (contact.length > 1 && contact.lowercase() !in listOf("the", "a", "an", "my", "on", "in", "to")) {
                    recordContact(contact)
                }
            }
        }

        // Learn app names from open/launch tasks
        val appPatterns = listOf(
            Regex("(?:open|launch|start|go to)\\s+(\\w+(?:\\s+\\w+)?)", RegexOption.IGNORE_CASE),
        )
        for (pattern in appPatterns) {
            val match = pattern.find(taskDescription)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                if (appName.length > 2) {
                    recordApp(appName)
                }
            }
        }
    }
}
