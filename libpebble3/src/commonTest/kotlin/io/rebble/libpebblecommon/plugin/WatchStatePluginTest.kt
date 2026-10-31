package io.rebble.libpebblecommon.plugin

import io.rebble.libpebblecommon.locker.AppProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.locker.wrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private fun app(id: String, title: String, type: AppType = AppType.Watchapp, sync: Boolean = true) =
    LockerWrapper.NormalApp(
        properties = AppProperties(
            id = Uuid.parse(id),
            type = type,
            title = title,
            developerName = "Someone",
            developerId = null,
            platforms = emptyList(),
            version = "1.0",
            hearts = null,
            category = null,
            iosCompanion = null,
            androidCompanion = null,
            order = 0,
            sourceLink = null,
            storeId = null,
            capabilities = emptyList(),
        ),
        sideloaded = false,
        configurable = false,
        sync = sync,
    )

private fun SourceInstance.shape(property: String, shape: String) =
    properties[property]?.get(shape)?.toString()

class WatchStatePluginTest {
    private val uuid = WatchStatePlugin.BUILT_IN_WATCH_STATE_UUID

    @Test
    fun theSourceIsDeclaredWithWhatItCarries() {
        val source = WatchStatePlugin.INSTALLED_APPS_SOURCE
        assertEquals(WatchStatePlugin.CATEGORY, source.category)
        assertEquals(listOf(WatchStatePlugin.ITEM_INSTALLED_APP), source.items)
        assertEquals(
            setOf(SourceInstance.PROPERTY_NAME, WatchStatePlugin.PROPERTY_TYPE, WatchStatePlugin.PROPERTY_SYSTEM),
            source.properties.keys,
        )
    }

    @Test
    fun eachAppIsAnInstanceKeyedByTheUuidLaunchAppTakes() {
        val envelope = installedAppsEnvelope(
            uuid,
            listOf(app("11111111-2222-4333-8444-555555555555", "Transit")),
        )
        val instance = envelope.instances.single()
        assertEquals("11111111-2222-4333-8444-555555555555", instance.instanceId)
        assertEquals("{\"text\":\"Transit\"}", instance.shape(SourceInstance.PROPERTY_NAME, SourceShapeNames.SHORT_TEXT))
        assertEquals("{\"text\":\"Transit\"}", instance.shape(SourceInstance.PROPERTY_NAME, SourceShapeNames.LONG_TEXT))
        assertEquals("{\"text\":\"watchapp\"}", instance.shape(WatchStatePlugin.PROPERTY_TYPE, SourceShapeNames.SHORT_TEXT))
        assertEquals("{\"value\":false}", instance.shape(WatchStatePlugin.PROPERTY_SYSTEM, SourceShapeNames.BOOLEAN))
        assertEquals(uuid.toString(), envelope.pluginUuid)
    }

    @Test
    fun appsBeyondTheSyncLimitAreNotOnTheWatch() {
        val envelope = installedAppsEnvelope(
            uuid,
            listOf(
                app("11111111-2222-4333-8444-555555555555", "Synced"),
                app("11111111-2222-4333-8444-666666666666", "Left behind", sync = false),
            ),
        )
        assertEquals(listOf("Synced"), envelope.instances.map { it.title() })
    }

    @Test
    fun systemAppsAndWatchfacesAreIncludedAndSaySo() {
        val envelope = installedAppsEnvelope(
            uuid,
            listOf(
                SystemApps.Settings.wrap(SystemApps.Settings.defaultOrder),
                app("11111111-2222-4333-8444-555555555555", "Face", type = AppType.Watchface),
            ),
        )
        val (settings, face) = envelope.instances
        assertEquals(SystemApps.Settings.uuid.toString(), settings.instanceId)
        assertEquals("{\"value\":true}", settings.shape(WatchStatePlugin.PROPERTY_SYSTEM, SourceShapeNames.BOOLEAN))
        assertEquals("{\"text\":\"watchface\"}", face.shape(WatchStatePlugin.PROPERTY_TYPE, SourceShapeNames.SHORT_TEXT))
    }

    @Test
    fun theLockerOrderIsKept() {
        val envelope = installedAppsEnvelope(
            uuid,
            listOf(
                app("11111111-2222-4333-8444-555555555555", "Zebra"),
                app("11111111-2222-4333-8444-666666666666", "Aardvark"),
            ),
        )
        assertEquals(listOf("Zebra", "Aardvark"), envelope.instances.map { it.title() })
    }

    private fun SourceInstance.title() =
        shape(SourceInstance.PROPERTY_NAME, SourceShapeNames.SHORT_TEXT)?.removePrefix("{\"text\":\"")?.removeSuffix("\"}")
}
