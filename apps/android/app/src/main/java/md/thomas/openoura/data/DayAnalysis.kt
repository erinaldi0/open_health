package md.thomas.openoura.data

/**
 * The per-day reports a user can rerun on demand — the Android counterpart of
 * `DayAnalysisKind` in apps/ios/OuraApp/Core.swift. [`label`] is the tab name, as
 * rendered in the day report header.
 */
enum class DayAnalysisKind(val label: String) {
    SLEEP("Sleep"),
    ACTIVITY("Activity"),
}

/** A request to rerun the analysis for [day] (`YYYY-MM-DD`). Mirror of iOS `DayAnalysisRequest`. */
data class DayAnalysisRequest(val day: String, val kind: DayAnalysisKind)