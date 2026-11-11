//go:build !darwin

package gateway

import "io/fs"

// hiddenFlag is whether the system hides a file beyond its name: never,
// off a Mac.
func hiddenFlag(fs.FileInfo) bool { return false }
