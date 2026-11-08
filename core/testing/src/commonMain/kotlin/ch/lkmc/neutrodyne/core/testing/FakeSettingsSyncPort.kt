// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.LocalSyncedSetting
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A [SettingsSyncPort] fixture: recordings in, scripted remote applies, no DataStore. */
class FakeSettingsSyncPort : SettingsSyncPort {
    private var recorderRef: (suspend (key: SettingKey<*>, json: String) -> Unit)? = null
    private var materializedRef: (suspend (key: SettingKey<*>, json: String) -> Unit)? = null
    private val locals = MutableSharedFlow<LocalSyncedSetting>(extraBufferCapacity = 16)

    override val localSyncedChanges: Flow<LocalSyncedSetting> = locals

    val recordings = mutableListOf<Pair<SettingKey<*>, String>>()
    var remoteResult: Boolean = true
    val applied = mutableListOf<Pair<SettingKey<*>, String>>()

    /** What [currentJson] reports per key — the fixture's stand-in for DataStore contents. */
    val current = mutableMapOf<String, String>()
    val materialized = mutableListOf<Pair<SettingKey<*>, String>>()

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        applied += key to json
        return remoteResult
    }

    override fun registerRecorder(recorder: (suspend (key: SettingKey<*>, json: String) -> Unit)?) {
        recorderRef = recorder
    }

    override fun registerMaterialized(listener: (suspend (SettingKey<*>, String) -> Unit)?) {
        materializedRef = listener
    }

    override suspend fun currentJson(key: SettingKey<*>): String? = current[key.name]

    override fun encode(
        key: SettingKey<*>,
        value: Any?,
    ): String? =
        when (value) {
            null -> {
                "null"
            }

            is Boolean -> {
                JsonPrimitive(value).toString()
            }

            is Int -> {
                JsonPrimitive(value).toString()
            }

            is Long -> {
                JsonPrimitive(value).toString()
            }

            is Float -> {
                JsonPrimitive(value).toString()
            }

            is Double -> {
                JsonPrimitive(value).toString()
            }

            is String -> {
                JsonPrimitive(value).toString()
            }

            is Set<*> -> {
                JsonArray(value.sortedBy { it.toString() }.map { JsonPrimitive(it.toString()) }).toString()
            }

            is Enum<*> -> {
                JsonPrimitive(value.name).toString()
            }

            else -> {
                null
            }
        }

    override suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    ) {
        val json = encode(key, value) ?: return
        recordings += key to json
        recorderRef?.invoke(key, json)
    }

    override suspend fun emitLocal(
        key: SettingKey<*>,
        value: Any?,
        atMs: Long,
    ) {
        val json = encode(key, value) ?: return
        materialized += key to json
        materializedRef?.invoke(key, json)
        locals.tryEmit(LocalSyncedSetting(key, json, atMs))
    }
}
