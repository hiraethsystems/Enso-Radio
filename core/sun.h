/*
 * Where the Sun is overhead, for the globe's day and night. Low-precision
 * formulas (the Astronomical Almanac's, good to about 0.01 degrees over
 * 1950-2050), far more than a terminator on a phone needs.
 */
#ifndef ENSO_SUN_H
#define ENSO_SUN_H

/* The subsolar point at `unix_s` (seconds since 1970-01-01 UTC): latitude
 * (the Sun's declination) and longitude in [-180, 180), degrees. */
void sun_subsolar(double unix_s, double *lat, double *lon);

#endif
