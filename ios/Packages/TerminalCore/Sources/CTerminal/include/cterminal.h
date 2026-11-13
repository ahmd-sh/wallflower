// The bridge between TerminalCore.Terminal and libghostty-vt: the same
// frame and screen layouts as the Android JNI shim (terminal.c there),
// without JNI. One handle per pane: output is written from the connection's
// thread, frames are packed on the main thread.

#ifndef CTERMINAL_H
#define CTERMINAL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct WFTerm WFTerm;

WFTerm *wf_term_new(int cols, int rows, int scrollback);
void wf_term_free(WFTerm *t);
void wf_term_write(WFTerm *t, const uint8_t *data, size_t len);
void wf_term_resize(WFTerm *t, int cols, int rows, int cell_w, int cell_h);

// mode: 0 delta (rows, negative is up), 1 top, 2 bottom.
void wf_term_scroll(WFTerm *t, int mode, int delta);

// A wheel notch at a cell: the bytes to send (into out, 64 bytes) if the
// app tracks the mouse, 0 on the alternate screen without tracking (send
// arrows), -1 on the primary screen, where scrollback is local.
int wf_term_wheel(WFTerm *t, bool up, int col, int row, uint8_t *out);

// The active screen, malloc'd (the caller frees it), in the layout
// ScreenReading's Screen.parse reads.
uint8_t *wf_term_screen(WFTerm *t, size_t *len);

// palette: n ints of 0xRRGGBB; the rest of the 256 keep their defaults.
void wf_term_set_colors(WFTerm *t, int32_t fg, int32_t bg, int32_t cursor, const int32_t *palette, int n);

// Packs the rows that changed since the last frame (all rows if force)
// into buf (cap ints). Returns bytes written, 0 if nothing changed or a
// synchronized update is in progress, or -needed if buf is too small.
int wf_term_frame(WFTerm *t, int32_t *buf, size_t cap, bool force);

// Selection, in viewport cells. select_word picks the word at a cell (0 if
// there's none there), which select_extend then extends from to a cell, the
// word kept in it. select_hold frees one end (0: the first in reading order,
// 1: the last) for select_to to move. select_view selects what's in view.
int wf_term_select_word(WFTerm *t, int col, int row);
void wf_term_select_extend(WFTerm *t, int col, int row);
void wf_term_select_hold(WFTerm *t, int end);
void wf_term_select_to(WFTerm *t, int col, int row);
void wf_term_select_view(WFTerm *t);
void wf_term_unselect(WFTerm *t);

// The selection's first and last cells in reading order, into out as col,
// row, col, row; rows scrolled out of view are outside 0..rows-1. 0 with none.
int wf_term_selection(WFTerm *t, int32_t *out);

// The selected text as a copy has it (wrapped rows joined, trailing blanks
// gone), malloc'd and NUL-terminated; the caller frees it. NULL with none.
uint8_t *wf_term_selection_text(WFTerm *t, size_t *len);

#endif
