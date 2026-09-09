package com.flino.config.health

import com.flino.config.config.ConfigStoreProperties
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Backs both liveness and readiness (`management.endpoint.health.probes.enabled`)
 * with the one dependency this service actually has: the mounted config
 * volume. `Files.isWritable` alone isn't trusted here -- POSIX permission
 * bits can say "writable" on a read-only NFS mount -- so this performs a
 * real probe: create a tiny temp file, delete it. Exposed at
 * `/actuator/health` as the `configVolume` component.
 */
@Component
class ConfigVolumeHealthIndicator(props: ConfigStoreProperties) : HealthIndicator {

    private val directory: Path = Paths.get(props.directory)

    override fun health(): Health {
        return try {
            Files.createDirectories(directory)
            val probe = Files.createTempFile(directory, ".health-probe-", ".tmp")
            Files.deleteIfExists(probe)
            Health.up()
                .withDetail("directory", directory.toString())
                .build()
        } catch (e: IOException) {
            Health.down()
                .withDetail("directory", directory.toString())
                .withDetail("error", e.message ?: e.javaClass.simpleName)
                .build()
        }
    }
}
