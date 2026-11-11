package gateway

import (
	"io/fs"
	"syscall"
)

// hiddenFlag is whether the Mac hides a file from its Finder (chflags
// hidden), as it does ~/Library.
func hiddenFlag(info fs.FileInfo) bool {
	st, ok := info.Sys().(*syscall.Stat_t)
	return ok && st.Flags&0x8000 != 0 // UF_HIDDEN
}
