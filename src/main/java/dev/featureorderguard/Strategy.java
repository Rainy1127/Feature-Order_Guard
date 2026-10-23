package dev.featureorderguard;

/** What to do when vanilla finds a feature order cycle. */
public enum Strategy {
    /** Break the cycle with the fewest possible changes, keep loading, write the report. */
    RESOLVE,
    /** Write the report, then crash like vanilla (for pack makers who want to fix the data by hand). */
    REPORT_ONLY,
    /** Do nothing at all: pure vanilla behaviour, no analysis, no report. */
    CRASH
}
