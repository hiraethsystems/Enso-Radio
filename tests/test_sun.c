/* Tests for core/sun.c, against times whose answers are known. */
#include <math.h>

#include "sun.h"
#include "test.h"

#define NEAR(a, b, eps) (fabs((a) - (b)) <= (eps))

void run_sun_tests(void)
{
    double lat, lon;

    /* March equinox, 2024-03-20 03:06 UTC: the Sun is over the equator. */
    sun_subsolar(1710903960.0, &lat, &lon);
    CHECK(NEAR(lat, 0.0, 0.05));
    /* June solstice, 2026-06-21 08:24 UTC: over the Tropic of Cancer. */
    sun_subsolar(1782030240.0, &lat, &lon);
    CHECK(NEAR(lat, 23.43, 0.05));
    /* December solstice, 2025-12-21 15:03 UTC: over the Tropic of Capricorn. */
    sun_subsolar(1766329380.0, &lat, &lon);
    CHECK(NEAR(lat, -23.43, 0.05));

    /* 2026-02-11 12:00 UTC: the equation of time is about -14.2 minutes
     * (noon at Greenwich comes at 12:14), so the Sun is still 3.55 E. */
    sun_subsolar(1770811200.0, &lat, &lon);
    CHECK(NEAR(lon, 3.55, 0.1));
    /* 2025-11-03 12:00 UTC: about +16.4 minutes (noon at 11:44), so the
     * Sun is already 4.1 W. */
    sun_subsolar(1762171200.0, &lat, &lon);
    CHECK(NEAR(lon, -4.1, 0.1));
    /* Six hours later the Sun is 90 degrees further west, near enough. */
    {
        double lat2, lon2;
        sun_subsolar(1762171200.0 + 6 * 3600.0, &lat2, &lon2);
        CHECK(NEAR(lon2, lon - 90.0, 0.1));
        CHECK(lon2 >= -180.0 && lon2 < 180.0);
    }
}
