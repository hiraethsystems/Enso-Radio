/* The subsolar point. See sun.h. */
#include "sun.h"

#include <math.h>

#define PI 3.14159265358979323846
#define DEG (PI / 180.0)

void sun_subsolar(double unix_s, double *lat, double *lon)
{
    /* Days since J2000.0 (2000-01-01 12:00 UTC). */
    double d = unix_s / 86400.0 - 10957.5;
    double g = (357.529 + 0.98560028 * d) * DEG;    /* mean anomaly */
    double q = 280.459 + 0.98564736 * d;            /* mean longitude, degrees */
    double L = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG; /* ecliptic longitude */
    double e = (23.439 - 0.00000036 * d) * DEG;     /* obliquity */
    double ra = atan2(cos(e) * sin(L), cos(L)) / DEG;
    double gmst = fmod(280.46061837 + 360.98564736629 * d, 360.0);
    double lo = fmod(ra - gmst, 360.0);
    if (lo < -180.0)
        lo += 360.0;
    if (lo >= 180.0)
        lo -= 360.0;
    *lat = asin(sin(e) * sin(L)) / DEG;
    *lon = lo;
}
