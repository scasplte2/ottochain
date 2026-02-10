// Dynamic versioning based on git tags and commits
// Use sbt-dynver for automatic semantic versioning
// Format: 1.2.3+4-deadbeef (tag + distance + short hash)
// If no tags exist, defaults to 0.1.0-SNAPSHOT

ThisBuild / dynverVTagPrefix := true // Use 'v' prefix on tags (default)
ThisBuild / dynverSonatypeSnapshots := true // Add -SNAPSHOT for non-tag versions
