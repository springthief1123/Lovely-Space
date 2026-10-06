package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.roomIdentity
import java.util.Locale

enum class RadarTargetSort { LAST_CONFIRMED, LAST_OBSERVED, NAME }

/** ピン留めを先頭に置き、照合がまだ無い追跡先は日時順の末尾へ置く。 */
fun List<TrackedRoom>.organized(sort: RadarTargetSort): List<TrackedRoom> {
    val order = when (sort) {
        RadarTargetSort.LAST_CONFIRMED -> compareByDescending<TrackedRoom> { it.confirmedAt ?: Long.MIN_VALUE }
        RadarTargetSort.LAST_OBSERVED -> compareByDescending<TrackedRoom> { it.observedAt ?: Long.MIN_VALUE }
        RadarTargetSort.NAME -> compareBy<TrackedRoom> { it.identity.name?.lowercase(Locale.ROOT) ?: "\uffff" }
    }
    return sortedWith(compareByDescending<TrackedRoom> { it.pinned }.then(order).thenBy { roomIdentity(it.identity) })
}
