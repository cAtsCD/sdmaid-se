package eu.darken.sdmse.appcleaner.core.forensics.filter

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.appcleaner.core.AppCleanerSettings
import eu.darken.sdmse.appcleaner.core.forensics.BaseExpendablesFilter
import eu.darken.sdmse.appcleaner.core.forensics.ExpendablesFilter
import eu.darken.sdmse.appcleaner.core.forensics.sieves.dynamic.DynamicSieve
import eu.darken.sdmse.common.areas.DataArea
import eu.darken.sdmse.common.datastore.value
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.APath
import eu.darken.sdmse.common.files.APathLookup
import eu.darken.sdmse.common.files.GatewaySwitch
import eu.darken.sdmse.common.files.Segments
import eu.darken.sdmse.common.pkgs.Pkg
import eu.darken.sdmse.common.pkgs.toPkgId
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class WhatsAppSentFilter @Inject constructor(
    private val dynamicSieveFactory: DynamicSieve.Factory,
    private val gatewaySwitch: GatewaySwitch,
) : BaseExpendablesFilter() {

    private lateinit var sieve: DynamicSieve

    override suspend fun initialize() {
        log(TAG) { "initialize()" }

        val configs = listOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business"
        )
            .flatMap { (pkg, name) ->
                listOf(
                    WhatsAppMapping(
                        location = DataArea.Type.SDCARD,
                        pkg = pkg,
                        folder1 = name,
                        folder2 = name,
                    ),
                    WhatsAppMapping(
                        location = DataArea.Type.PUBLIC_MEDIA,
                        pkg = pkg,
                        folder1 = "$pkg/$name",
                        folder2 = name,
                    ),
                )
            }
            .map { (location, pkg, folder1, folder2) ->
                DynamicSieve.MatchConfig(
                    pkgNames = setOf(pkg.toPkgId()),
                    areaTypes = setOf(location),
                    contains = setOf("$folder1/Media/$folder2"),
                    patterns = setOf(
                        "(?>$folder1/Media/$folder2 Video/Sent/)(?:.+?)$",
                        "(?>$folder1/Media/$folder2 Animated Gifs/Sent/)(?:.+?)$",
                        "(?>$folder1/Media/$folder2 Images/Sent/)(?:.+?)$",
                        "(?>$folder1/Media/$folder2 Audio/Sent/)(?:.+?)$",
                        "(?>$folder1/Media/$folder2 Documents/Sent/)(?:.+?)$",
                    ),
                    exclusions = setOf(".nomedia"),
                )
            }
            .toSet()

        sieve = dynamicSieveFactory.create(configs)
    }

    override suspend fun match(
        pkgId: Pkg.Id,
        target: APathLookup<APath>,
        areaType: DataArea.Type,
        segments: Segments
    ): ExpendablesFilter.Match? {
        if (segments.isNotEmpty() && IGNORED_FILES.contains(segments[segments.size - 1])) return null

        return if (segments.isNotEmpty() && sieve.matches(pkgId, areaType, segments)) {
            target.toDeletionMatch()
        } else {
            null
        }
    }

    override suspend fun process(matches: Collection<ExpendablesFilter.Match>): ExpendablesFilter.ProcessResult {
        return matches.deleteAll(gatewaySwitch)
    }

    @Reusable
    class Factory @Inject constructor(
        private val settings: AppCleanerSettings,
        private val filterProvider: Provider<WhatsAppSentFilter>
    ) : ExpendablesFilter.Factory {
        override suspend fun isEnabled(): Boolean = settings.filterWhatsAppSentEnabled.value()
        override suspend fun create(): ExpendablesFilter = filterProvider.get()
    }

    @InstallIn(SingletonComponent::class)
    @Module
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: Factory): ExpendablesFilter.Factory
    }

    companion object {
        private val IGNORED_FILES: Collection<String> = listOf(
            ".nomedia",
        )
        private val TAG = logTag("AppCleaner", "Scanner", "Filter", "WhatsApp", "Sent")
    }
}