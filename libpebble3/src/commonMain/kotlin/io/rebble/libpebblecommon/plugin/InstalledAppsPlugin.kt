package io.rebble.libpebblecommon.plugin

import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.plugin.ActionDeclaration.Companion.PARAM_INSTANCE_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

fun interface WatchAppLauncher {
    /** @return false when no watch is connected to open it on. */
    suspend fun launch(uuid: Uuid): Boolean
}

class ConnectedWatchesAppLauncher(
    private val watches: Watches,
) : WatchAppLauncher {
    override suspend fun launch(uuid: Uuid): Boolean {
        val connected = watches.watches.value.filterIsInstance<ConnectedPebbleDevice>()
        connected.forEach { it.launchApp(uuid) }
        return connected.isNotEmpty()
    }
}

/**
 * Built-in plugin for the watchapps that are on the watch, system apps included, and for opening
 * one of them — so a watchapp can open another through its PKJS, which the watch SDK has no call
 * for.
 *
 * Watchfaces are left out: opening one from the phone makes it the watch's default face.
 */
class InstalledAppsPlugin(
    private val locker: LockerApi,
    private val watchConfig: WatchConfigFlow,
    private val launcher: WatchAppLauncher,
) : NativePlugin {
    override val pluginUuid: Uuid = BUILT_IN_INSTALLED_APPS_UUID
    override val name: String = "Watch Apps"

    override val sources: List<SourceDeclaration> = listOf(
        SourceDeclaration(
            category = CATEGORY,
            items = listOf(ITEM_APP),
            properties = mapOf(
                SourceInstance.PROPERTY_NAME to TEXT_SHAPES,
                PROPERTY_SYSTEM to listOf(SourceShapeNames.BOOLEAN),
            ),
            supportsMultiple = true,
        ),
    )

    override val actions: List<ActionDeclaration> = listOf(
        ActionDeclaration(
            name = ACTION_LAUNCH,
            description = "Open a watchapp on the watch.",
            parameters = Json.decodeFromString(
                JsonObject.serializer(),
                """
                {"type":"object","properties":{
                "$PARAM_INSTANCE_ID":{"type":"string","description":"Id from $CATEGORY/$ITEM_APP: the app's uuid."}
                },"required":["$PARAM_INSTANCE_ID"]}
                """,
            ),
            targets = listOf("$CATEGORY/$ITEM_APP"),
        ),
    )

    override fun observe(
        category: String,
        item: String,
        properties: List<String>?,
        iconPixelSize: IconPixelSize?,
    ): Flow<SourceEnvelope> {
        if (!serves(category, item)) return emptyFlow()
        return appsOnWatch().map { apps ->
            SourceEnvelope(
                pluginUuid = pluginUuid.toString(),
                instances = apps.map { it.toInstance() },
            )
        }
    }

    override suspend fun invoke(action: String, args: JsonObject): ActionResult {
        if (action != ACTION_LAUNCH) {
            return ActionResult.error(PluginErrors.PLUGIN_UNAVAILABLE, "no action $action")
        }
        val id = args[PARAM_INSTANCE_ID]?.jsonPrimitive?.content
        val uuid = id?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
        val app = appsOnWatch().first().firstOrNull { it.properties.id == uuid }
            ?: return ActionResult.error(PluginErrors.INVALID_ARGS, "no watchapp '$id' on the watch")
        if (!launcher.launch(app.properties.id)) {
            return ActionResult.error(PluginErrors.PLUGIN_UNAVAILABLE, "no watch connected")
        }
        return ActionResult(ok = true, text = app.properties.title)
    }

    /** System apps sit outside the sync limit: the watch always has them. */
    private fun appsOnWatch(): Flow<List<LockerWrapper>> = locker.getLocker(
        type = AppType.Watchapp,
        searchQuery = null,
        limit = watchConfig.value.lockerSyncLimitV2 + SystemApps.entries.size,
    ).map { apps ->
        apps.filter { app ->
            when (app) {
                is LockerWrapper.NormalApp -> app.sync
                is LockerWrapper.SystemApp -> true
            }
        }
    }

    private fun LockerWrapper.toInstance() = SourceInstance(
        instanceId = properties.id.toString(),
        properties = mapOf(
            SourceInstance.PROPERTY_NAME to mapOf(
                SourceShapeNames.SHORT_TEXT to encode(ShortTextShape(properties.title)),
                SourceShapeNames.LONG_TEXT to encode(LongTextShape(properties.title)),
            ),
            PROPERTY_SYSTEM to mapOf(
                SourceShapeNames.BOOLEAN to encode(BooleanShape(this is LockerWrapper.SystemApp)),
            ),
        ),
    )

    private inline fun <reified T> encode(value: T): JsonElement =
        Json.encodeToJsonElement(value)

    companion object {
        const val CATEGORY = "watch"
        const val ITEM_APP = "installed_app"
        const val PROPERTY_SYSTEM = "system"
        const val ACTION_LAUNCH = "launch_app"

        private val TEXT_SHAPES =
            listOf(SourceShapeNames.SHORT_TEXT, SourceShapeNames.LONG_TEXT)

        // Reserved built-in UUID namespace: prefix 00000000-0000-0000-0001-* for built-ins.
        val BUILT_IN_INSTALLED_APPS_UUID: Uuid =
            Uuid.parse("00000000-0000-0000-0001-000000000007")
    }
}
