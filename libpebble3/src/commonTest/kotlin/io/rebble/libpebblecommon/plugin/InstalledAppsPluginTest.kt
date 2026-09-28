package io.rebble.libpebblecommon.plugin

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.locker.AppBasicProperties
import io.rebble.libpebblecommon.locker.AppProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.locker.wrap
import io.rebble.libpebblecommon.plugin.ActionDeclaration.Companion.PARAM_INSTANCE_ID
import io.rebble.libpebblecommon.web.LockerEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

private class FakeLocker(private val apps: List<LockerWrapper>) : LockerApi {
    override fun getLocker(type: AppType, searchQuery: String?, limit: Int) =
        flowOf(apps.filter { it.properties.type == type }.take(limit))

    override suspend fun sideloadApp(pbwPath: Path, loadOnWatch: Boolean) = error("unused")
    override fun getAllLockerBasicInfo(): Flow<List<AppBasicProperties>> = error("unused")
    override fun getAllLockerUuids(): Flow<List<Uuid>> = error("unused")
    override fun getLockerApp(id: Uuid): Flow<LockerWrapper?> = error("unused")
    override suspend fun appConfigPageUrl(uuid: Uuid): String? = error("unused")
    override suspend fun setAppOrder(id: Uuid, order: Int) = error("unused")
    override suspend fun waitUntilAppSyncedToWatch(
        id: Uuid,
        identifier: PebbleIdentifier,
        timeout: Duration,
    ): Boolean = error("unused")
    override suspend fun removeApp(id: Uuid): Boolean = error("unused")
    override suspend fun addAppToLocker(app: LockerEntry) = error("unused")
    override suspend fun addAppsToLocker(apps: List<LockerEntry>) = error("unused")
    override fun restoreSystemAppOrder() = error("unused")
    override val activeWatchface: StateFlow<LockerWrapper?> get() = error("unused")
}

private class FakeLauncher(var connected: Boolean = true) : WatchAppLauncher {
    val launched = mutableListOf<Uuid>()

    override suspend fun launch(uuid: Uuid): Boolean {
        if (connected) launched += uuid
        return connected
    }
}

private fun app(title: String, type: AppType = AppType.Watchapp, sync: Boolean = true) =
    LockerWrapper.NormalApp(
        properties = AppProperties(
            id = Uuid.random(),
            type = type,
            title = title,
            developerName = "",
            developerId = null,
            platforms = emptyList(),
            version = null,
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

private val weather = app("Weather Radar")
private val notSynced = app("Too Far Down", sync = false)
private val face = app("A Face", type = AppType.Watchface)
private val settings = SystemApps.Settings.wrap(order = -10)

private fun plugin(launcher: WatchAppLauncher = FakeLauncher()) = InstalledAppsPlugin(
    locker = FakeLocker(listOf(settings, weather, notSynced, face)),
    watchConfig = WatchConfigFlow(MutableStateFlow(LibPebbleConfig())),
    launcher = launcher,
)

private suspend fun InstalledAppsPlugin.instances() =
    observe(InstalledAppsPlugin.CATEGORY, InstalledAppsPlugin.ITEM_APP).first().instances

private fun SourceInstance.shape(property: String, shape: String) =
    properties[property]?.get(shape)?.toString()

private fun launch(id: String): JsonObject =
    Json.decodeFromString(JsonObject.serializer(), """{"$PARAM_INSTANCE_ID":"$id"}""")

class InstalledAppsPluginTest {

    @Test
    fun listsTheWatchappsOnTheWatchBySystemAndOwn() = runTest {
        val instances = plugin().instances()

        assertEquals(
            listOf(settings.properties.id.toString(), weather.properties.id.toString()),
            instances.map { it.instanceId },
        )
        assertEquals(
            """{"text":"Weather Radar"}""",
            instances[1].shape(SourceInstance.PROPERTY_NAME, SourceShapeNames.LONG_TEXT),
        )
        assertEquals(
            """{"value":true}""",
            instances[0].shape(InstalledAppsPlugin.PROPERTY_SYSTEM, SourceShapeNames.BOOLEAN),
        )
        assertEquals(
            """{"value":false}""",
            instances[1].shape(InstalledAppsPlugin.PROPERTY_SYSTEM, SourceShapeNames.BOOLEAN),
        )
    }

    @Test
    fun servesNothingElse() = runTest {
        val plugin = plugin()
        assertEquals(
            emptyList(),
            plugin.observe(InstalledAppsPlugin.CATEGORY, "app_setting").toList(),
        )
    }

    @Test
    fun launchesAnAppOnTheWatch() = runTest {
        val launcher = FakeLauncher()
        val result = plugin(launcher).invoke(
            InstalledAppsPlugin.ACTION_LAUNCH,
            launch(weather.properties.id.toString()),
        )

        assertTrue(result.ok)
        assertEquals("Weather Radar", result.text)
        assertEquals(listOf(weather.properties.id), launcher.launched)
    }

    @Test
    fun launchesASystemApp() = runTest {
        val launcher = FakeLauncher()
        val result = plugin(launcher).invoke(
            InstalledAppsPlugin.ACTION_LAUNCH,
            launch(settings.properties.id.toString().uppercase()),
        )

        assertTrue(result.ok)
        assertEquals(listOf(SystemApps.Settings.uuid), launcher.launched)
    }

    @Test
    fun refusesWhatIsNotAWatchappOnTheWatch() = runTest {
        val launcher = FakeLauncher()
        val plugin = plugin(launcher)
        for (id in listOf(
            notSynced.properties.id.toString(),
            face.properties.id.toString(),
            Uuid.random().toString(),
            "not a uuid",
        )) {
            val result = plugin.invoke(InstalledAppsPlugin.ACTION_LAUNCH, launch(id))
            assertFalse(result.ok, id)
            assertEquals(PluginErrors.INVALID_ARGS, result.code, id)
        }
        assertEquals(emptyList(), launcher.launched)
    }

    @Test
    fun saysSoWhenNoWatchIsConnected() = runTest {
        val result = plugin(FakeLauncher(connected = false)).invoke(
            InstalledAppsPlugin.ACTION_LAUNCH,
            launch(weather.properties.id.toString()),
        )

        assertFalse(result.ok)
        assertEquals(PluginErrors.PLUGIN_UNAVAILABLE, result.code)
    }

    @Test
    fun launchCanBeBoundToATile() {
        val launch = plugin().action(InstalledAppsPlugin.ACTION_LAUNCH)
        assertEquals(listOf(PARAM_INSTANCE_ID), launch?.requiredParams)
        assertTrue(launch?.bindable == true)
    }
}
