package org.appdevforall.maps.ui

import org.junit.Assert.fail
import org.junit.Test

/**
 * The plugin must load on devices without Google services, so no Play Services class may be on
 * its classpath. The unit-test runtime classpath carries every `implementation` dependency.
 */
class NoPlayServicesTest {

    @Test
    fun `play services location is not a dependency`() {
        try {
            Class.forName("com.google.android.gms.location.LocationServices")
            fail("com.google.android.gms.location.LocationServices is on the classpath")
        } catch (expected: ClassNotFoundException) {
        }
    }
}
