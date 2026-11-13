// libghostty-vt behind cterminal.h: Android's terminal.c without JNI.

#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <ghostty/vt.h>

#include "cterminal.h"

#define HEADER_INTS 14
#define ROW_HEADER_INTS 2
#define CELL_INTS 5
#define MAX_GRAPHEME 16
#define HOLD_TIMEOUT_NS 1000000000LL
#define NO_COLOR (-1)

enum {
  FLAG_BOLD = 1 << 0,
  FLAG_ITALIC = 1 << 1,
  FLAG_FAINT = 1 << 2,
  FLAG_BLINK = 1 << 3,
  FLAG_INVERSE = 1 << 4,
  FLAG_INVISIBLE = 1 << 5,
  FLAG_STRIKETHROUGH = 1 << 6,
  FLAG_OVERLINE = 1 << 7,
  FLAG_UNDERLINE_SHIFT = 8,  // 3 bits, GHOSTTY_SGR_UNDERLINE_*
  FLAG_WIDE_SHIFT = 11,      // 2 bits, GHOSTTY_CELL_WIDE_*
  FLAG_SELECTED = 1 << 13,
  FLAG_GRAPHEME = 1 << 14,   // more codepoints follow in the row's extras
};

struct WFTerm {
  GhosttyTerminal term;
  GhosttyRenderState state;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  pthread_mutex_t lock;
  bool held;
  int64_t held_since;
  GhosttyMouseEncoder mouse; // created on first wheel event
  int32_t cursor_was[4];      // flags, x, y, style, as the last frame had it
  GhosttyTrackedGridRef anchor[2]; // the word a press selected, a drag extends from
};
typedef struct WFTerm Term;

static int64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// Synchronized output (mode 2026): the app is mid-update and asks us not to
// draw. Runs inside vt_write, so the lock is held.
static void on_render_hold(GhosttyTerminal term, void* userdata, bool held) {
  (void)term;
  Term* t = userdata;
  t->held = held;
  if (held) t->held_since = now_ns();
}

static int32_t rgb(GhosttyColorRgb c) { return (c.r << 16) | (c.g << 8) | c.b; }

// A grey that apps use for secondary text: bright black, the middle of the
// 256-color grey ramp and the cube's greys, or an RGB grey neither dark nor
// light.
static bool muted(GhosttyStyleColor c) {
  if (c.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
    uint8_t p = c.value.palette;
    return p == 8 || (p >= 240 && p <= 250) || p == 59 || p == 102 || p == 145;
  }
  if (c.tag != GHOSTTY_STYLE_COLOR_RGB) return false;
  GhosttyColorRgb v = c.value.rgb;
  int hi = v.r > v.g ? (v.r > v.b ? v.r : v.b) : (v.g > v.b ? v.g : v.b);
  int lo = v.r < v.g ? (v.r < v.b ? v.r : v.b) : (v.g < v.b ? v.g : v.b);
  return hi - lo <= 24 && hi >= 90 && lo <= 200;
}

static int32_t style_color(GhosttyStyleColor c, const GhosttyRenderStateColors* colors) {
  switch (c.tag) {
    case GHOSTTY_STYLE_COLOR_RGB: return rgb(c.value.rgb);
    case GHOSTTY_STYLE_COLOR_PALETTE: return rgb(colors->palette[c.value.palette]);
    default: return NO_COLOR;
  }
}

WFTerm *wf_term_new(int cols, int rows, int scrollback) {
  Term* t = calloc(1, sizeof(Term));
  if (!t) return NULL;
  if (ghostty_terminal_new(NULL, &t->term, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &t->state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &t->rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &t->cells) != GHOSTTY_SUCCESS) {
    if (t->cells) ghostty_render_state_row_cells_free(t->cells);
    if (t->rows) ghostty_render_state_row_iterator_free(t->rows);
    if (t->state) ghostty_render_state_free(t->state);
    if (t->term) ghostty_terminal_free(t->term);
    free(t);
    return NULL;
  }
  pthread_mutex_init(&t->lock, NULL);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void*)on_render_hold);
  size_t lines = (size_t)scrollback;
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
  // tmux clusters graphemes (ZWJ sequences, flags, VS16 are one wide cell),
  // so cell widths only agree with it in grapheme mode, kept across resets.
  GhosttyTerminalModeConfig grapheme = {GHOSTTY_MODE_GRAPHEME_CLUSTER, true};
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &grapheme);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_MODE, &grapheme);
  // No WRITE_PTY: tmux already answers the app's queries, so replies are dropped.
  return t;
}

void wf_term_free(WFTerm *t) {
  if (!t) return;
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->state);
  if (t->mouse) ghostty_mouse_encoder_free(t->mouse);
  for (int i = 0; i < 2; i++) ghostty_tracked_grid_ref_free(t->anchor[i]);
  ghostty_terminal_free(t->term);
  pthread_mutex_destroy(&t->lock);
  free(t);
}

void wf_term_write(WFTerm *t, const uint8_t *data, size_t len) {
  pthread_mutex_lock(&t->lock);
  ghostty_terminal_vt_write(t->term, data, len);
  pthread_mutex_unlock(&t->lock);
}

void wf_term_resize(WFTerm *t, int cols, int rows, int cellW, int cellH) {
  pthread_mutex_lock(&t->lock);
  ghostty_terminal_resize(t->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cellW, (uint32_t)cellH);
  pthread_mutex_unlock(&t->lock);
}

// mode: 0 delta (rows, negative is up), 1 top, 2 bottom.
void wf_term_scroll(WFTerm *t, int mode, int delta) {
  GhosttyTerminalScrollViewport s = {0};
  switch (mode) {
    case 1: s.tag = GHOSTTY_SCROLL_VIEWPORT_TOP; break;
    case 2: s.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM; break;
    default: s.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA; s.value.delta = delta; break;
  }
  pthread_mutex_lock(&t->lock);
  ghostty_terminal_scroll_viewport(t->term, s);
  pthread_mutex_unlock(&t->lock);
}

// A wheel notch at a cell, for full-screen apps that own their scrolling.
// Returns the bytes to send if the app tracks the mouse, 0 if it's on the
// alternate screen without mouse tracking (send arrows instead), and -1 on
// the primary screen, where scrollback is local.
int wf_term_wheel(WFTerm *t, bool up, int col, int row, uint8_t *out) {
  char buf[64];
  size_t len = 0;
  int result = 0;
  pthread_mutex_lock(&t->lock);
  bool tracking = false;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  if (tracking && (t->mouse || ghostty_mouse_encoder_new(NULL, &t->mouse) == GHOSTTY_SUCCESS)) {
    uint16_t cols = 0, rows = 0;
    ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
    ghostty_mouse_encoder_setopt_from_terminal(t->mouse, t->term);
    // Positions are in cells: one pixel per cell.
    GhosttyMouseEncoderSize size = {
      .size = sizeof(GhosttyMouseEncoderSize), .screen_width = cols, .screen_height = rows,
      .cell_width = 1, .cell_height = 1,
    };
    ghostty_mouse_encoder_setopt(t->mouse, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    GhosttyMouseEvent ev = NULL;
    if (ghostty_mouse_event_new(NULL, &ev) == GHOSTTY_SUCCESS) {
      ghostty_mouse_event_set_action(ev, GHOSTTY_MOUSE_ACTION_PRESS);
      ghostty_mouse_event_set_button(ev, up ? GHOSTTY_MOUSE_BUTTON_FOUR : GHOSTTY_MOUSE_BUTTON_FIVE);
      ghostty_mouse_event_set_position(ev, (GhosttyMousePosition){ col + 0.5f, row + 0.5f });
      if (ghostty_mouse_encoder_encode(t->mouse, ev, buf, sizeof buf, &len) == GHOSTTY_SUCCESS) result = (int)len;
      ghostty_mouse_event_free(ev);
    }
  } else if (screen == GHOSTTY_TERMINAL_SCREEN_PRIMARY) {
    result = -1;
  }
  pthread_mutex_unlock(&t->lock);
  if (result > 0) memcpy(out, buf, (size_t)result);
  return result;
}

// The active screen, for reading what an app's input line holds. Layout:
// u16 cols, u16 rows, u16 cursor x, u16 cursor y, then per row a flags byte
// (1: soft-wrapped onto the next row) and per cell an attribute byte and a
// codepoint count, then that many i32 codepoints. Attributes: bits 0-1 the
// OSC 133 role (0 output, 1 input, 2 prompt), 4 faint, 8 in palette color 8
// (autosuggestion grey), 16 the second half of a wide cell, 32 in a grey (see
// muted), 64 highlighted: inverse, or on a background color.
uint8_t *wf_term_screen(WFTerm *t, size_t *len_out) {
  pthread_mutex_lock(&t->lock);
  uint16_t cols = 0, rows = 0, cx = 0, cy = 0;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_CURSOR_X, &cx);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &cy);
  size_t cap = 8 + (size_t)rows * (1 + (size_t)cols * 6) + 64, len = 0;
  uint8_t* out = malloc(cap);
  if (!out) {
    pthread_mutex_unlock(&t->lock);
    return NULL;
  }
  uint16_t head[4] = {cols, rows, cx, cy};
  memcpy(out, head, sizeof head);
  len = sizeof head;
  for (uint16_t y = 0; y < rows; y++) {
    size_t flags_at = len++;
    out[flags_at] = 0;
    for (uint16_t x = 0; x < cols; x++) {
      uint32_t cps[8];
      size_t n = 0;
      uint8_t attr = 0;
      GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = x, .y = y}}};
      GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
      GhosttyCell cell;
      if (ghostty_terminal_grid_ref(t->term, pt, &ref) == GHOSTTY_SUCCESS && ghostty_grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS) {
        if (x == 0) {
          GhosttyRow row;
          bool wrap = false;
          if (ghostty_grid_ref_row(&ref, &row) == GHOSTTY_SUCCESS) ghostty_row_get(row, GHOSTTY_ROW_DATA_WRAP, &wrap);
          if (wrap) out[flags_at] |= 1;
        }
        GhosttyCellSemanticContent role = GHOSTTY_CELL_SEMANTIC_OUTPUT;
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_SEMANTIC_CONTENT, &role);
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        attr |= (uint8_t)(role & 3);
        if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) attr |= 16;
        GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
        if (ghostty_grid_ref_style(&ref, &style) == GHOSTTY_SUCCESS) {
          if (style.faint) attr |= 4;
          if (style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE && style.fg_color.value.palette == 8) attr |= 8;
          if (muted(style.fg_color)) attr |= 32;
          if (style.inverse || style.bg_color.tag != GHOSTTY_STYLE_COLOR_NONE) attr |= 64;
        }
        if (ghostty_grid_ref_graphemes(&ref, cps, 8, &n) != GHOSTTY_SUCCESS) n = 0;
      }
      if (len + 2 + n * 4 > cap) {
        cap = cap * 2 + n * 4;
        uint8_t* grown = realloc(out, cap);
        if (!grown) {
          free(out);
          pthread_mutex_unlock(&t->lock);
          return NULL;
        }
        out = grown;
      }
      out[len++] = attr;
      out[len++] = (uint8_t)n;
      memcpy(out + len, cps, n * 4);
      len += n * 4;
    }
  }
  pthread_mutex_unlock(&t->lock);
  *len_out = len;
  return out;
}

// palette: 16 or 256 ints of 0xRRGGBB; the rest of the 256 keep their defaults.
void wf_term_set_colors(WFTerm *t, int32_t fg, int32_t bg, int32_t cursor, const int32_t *p, int n) {
  pthread_mutex_lock(&t->lock);
  GhosttyColorRgb colors[256];
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, colors);
  for (int i = 0; i < n && i < 256; i++) {
    colors[i] = (GhosttyColorRgb){(uint8_t)(p[i] >> 16), (uint8_t)(p[i] >> 8), (uint8_t)p[i]};
  }
  GhosttyColorRgb f = {(uint8_t)(fg >> 16), (uint8_t)(fg >> 8), (uint8_t)fg};
  GhosttyColorRgb b = {(uint8_t)(bg >> 16), (uint8_t)(bg >> 8), (uint8_t)bg};
  GhosttyColorRgb c = {(uint8_t)(cursor >> 16), (uint8_t)(cursor >> 8), (uint8_t)cursor};
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, colors);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &f);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &b);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c);
  pthread_mutex_unlock(&t->lock);
}

// Selection: what's chosen to copy, kept by the terminal on the cells it
// covers however they scroll. Cells are the viewport's; a selection's rows
// scrolled out of view come back outside 0..rows-1.

// What ends a word: blanks, quotes, brackets and rules, but not the colons
// and slashes of paths and URLs, which select whole.
static const uint32_t WORD_BOUNDARY[] = {
  ' ', '\t', 0xA0, '"', '\'', '`', '|', 0x2502, ';', ',', '(', ')', '[', ']', '{', '}', '<', '>',
};

// The cell at a viewport point, kept within the viewport.
static bool cell_ref(Term* t, int col, int row, GhosttyGridRef* out) {
  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  if (cols == 0 || rows == 0) return false;
  col = col < 0 ? 0 : col >= cols ? cols - 1 : col;
  row = row < 0 ? 0 : row >= rows ? rows - 1 : row;
  GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value = {.coordinate = {.x = (uint16_t)col, .y = (uint32_t)row}}};
  *out = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  return ghostty_terminal_grid_ref(t->term, pt, out) == GHOSTTY_SUCCESS;
}

static bool on_screen(Term* t, const GhosttyGridRef* ref, GhosttyPointCoordinate* out) {
  return ghostty_terminal_point_from_grid_ref(t->term, ref, GHOSTTY_POINT_TAG_SCREEN, out) == GHOSTTY_SUCCESS;
}

// Reading order: negative if a comes before b.
static int compare(Term* t, const GhosttyGridRef* a, const GhosttyGridRef* b) {
  GhosttyPointCoordinate pa = {0}, pb = {0};
  if (!on_screen(t, a, &pa) || !on_screen(t, b, &pb)) return 0;
  if (pa.y != pb.y) return pa.y < pb.y ? -1 : 1;
  return (int)pa.x - (int)pb.x;
}

static void install(Term* t, const GhosttyGridRef* start, const GhosttyGridRef* end) {
  GhosttySelection s = GHOSTTY_INIT_SIZED(GhosttySelection);
  s.start = *start;
  s.end = *end;
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, &s);
}

static void drop_anchor(Term* t) {
  for (int i = 0; i < 2; i++) {
    ghostty_tracked_grid_ref_free(t->anchor[i]);
    t->anchor[i] = NULL;
  }
}

// Selects the word at a cell, remembered as where a drag extends from.
// Returns 0 if there's no word there.
static int select_word(Term* t, int col, int row) {
  drop_anchor(t);
  GhosttyGridRef ref;
  if (!cell_ref(t, col, row, &ref)) return 0;
  GhosttyTerminalSelectWordOptions o = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
  o.ref = ref;
  o.boundary_codepoints = WORD_BOUNDARY;
  o.boundary_codepoints_len = sizeof WORD_BOUNDARY / sizeof WORD_BOUNDARY[0];
  GhosttySelection word = GHOSTTY_INIT_SIZED(GhosttySelection), f = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_select_word(t->term, &o, &word) != GHOSTTY_SUCCESS ||
      ghostty_terminal_selection_ordered(t->term, &word, GHOSTTY_SELECTION_ORDER_FORWARD, &f) != GHOSTTY_SUCCESS) {
    return 0;
  }
  GhosttyPointCoordinate ends[2] = {{0}, {0}};
  bool tracked = on_screen(t, &f.start, &ends[0]) && on_screen(t, &f.end, &ends[1]);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, &f);
  // Tracked from points, not the refs: setting the selection outdated those.
  for (int i = 0; tracked && i < 2; i++) {
    GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value = {.coordinate = ends[i]}};
    if (ghostty_terminal_grid_ref_track(t->term, pt, &t->anchor[i]) != GHOSTTY_SUCCESS) t->anchor[i] = NULL;
  }
  return 1;
}

// The selection from the word a press chose to a cell, the word always in it.
static void select_extend(Term* t, int col, int row) {
  GhosttyGridRef a = GHOSTTY_INIT_SIZED(GhosttyGridRef), b = GHOSTTY_INIT_SIZED(GhosttyGridRef), p;
  if (!t->anchor[0] || !t->anchor[1] ||
      ghostty_tracked_grid_ref_snapshot(t->anchor[0], &a) != GHOSTTY_SUCCESS ||
      ghostty_tracked_grid_ref_snapshot(t->anchor[1], &b) != GHOSTTY_SUCCESS || !cell_ref(t, col, row, &p)) {
    return;
  }
  if (compare(t, &p, &a) < 0) install(t, &b, &p);
  else if (compare(t, &p, &b) > 0) install(t, &a, &p);
  else install(t, &a, &b);
}

// Keeps one end (0: the first in reading order, 1: the last) free for
// select_to to move, the other held where it is.
static void select_hold(Term* t, int end) {
  drop_anchor(t);
  GhosttySelection s = GHOSTTY_INIT_SIZED(GhosttySelection), f = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SELECTION, &s) != GHOSTTY_SUCCESS ||
      ghostty_terminal_selection_ordered(t->term, &s, GHOSTTY_SELECTION_ORDER_FORWARD, &f) != GHOSTTY_SUCCESS) {
    return;
  }
  if (end) install(t, &f.start, &f.end);
  else install(t, &f.end, &f.start);
}

static void select_to(Term* t, int col, int row) {
  GhosttySelection s = GHOSTTY_INIT_SIZED(GhosttySelection);
  GhosttyGridRef p;
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SELECTION, &s) == GHOSTTY_SUCCESS && cell_ref(t, col, row, &p)) {
    install(t, &s.start, &p);
  }
}

static void select_view(Term* t) {
  drop_anchor(t);
  GhosttyGridRef a, b;
  if (cell_ref(t, 0, 0, &a) && cell_ref(t, INT32_MAX, INT32_MAX, &b)) install(t, &a, &b);
}

static void unselect(Term* t) {
  drop_anchor(t);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

// The selection's first and last cells in reading order (col, row, col, row).
static int selection(Term* t, int32_t* out) {
  GhosttySelection s = GHOSTTY_INIT_SIZED(GhosttySelection), f = GHOSTTY_INIT_SIZED(GhosttySelection);
  GhosttyGridRef top;
  GhosttyPointCoordinate a = {0}, b = {0}, v = {0};
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SELECTION, &s) != GHOSTTY_SUCCESS ||
      ghostty_terminal_selection_ordered(t->term, &s, GHOSTTY_SELECTION_ORDER_FORWARD, &f) != GHOSTTY_SUCCESS ||
      !cell_ref(t, 0, 0, &top) || !on_screen(t, &f.start, &a) || !on_screen(t, &f.end, &b) || !on_screen(t, &top, &v)) {
    return 0;
  }
  out[0] = a.x;
  out[1] = (int32_t)a.y - (int32_t)v.y;
  out[2] = b.x;
  out[3] = (int32_t)b.y - (int32_t)v.y;
  return 1;
}

// The selected text, as a copy should have it: rows the program wrapped
// joined, trailing blanks gone. malloc'd, NUL-terminated; NULL with none.
static uint8_t* selection_text(Term* t, size_t* len_out) {
  GhosttyTerminalSelectionFormatOptions o = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  o.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  o.unwrap = true;
  o.trim = true;
  uint8_t* p = NULL;
  size_t n = 0;
  if (ghostty_terminal_selection_format_alloc(t->term, NULL, o, &p, &n) != GHOSTTY_SUCCESS) return NULL;
  uint8_t* out = malloc(n + 1);
  if (out) {
    if (n) memcpy(out, p, n);
    out[n] = 0;
    *len_out = n;
  }
  ghostty_free(NULL, p, n);
  return out;
}

typedef struct {
  int32_t* buf;
  size_t cap;  // in ints
  size_t len;
} Out;

static bool put(Out* o, int32_t v) {
  if (o->len >= o->cap) return false;
  o->buf[o->len++] = v;
  return true;
}

// Packs the rows that changed since the last frame (all rows if force) into
// buffer. Returns the number of bytes written, 0 if nothing changed or a
// synchronized update is in progress, or -needed if the buffer is too small.
int wf_term_frame(WFTerm *t, int32_t *buf, size_t cap, bool force) {
  Out o = {.buf = buf, .cap = cap};

  pthread_mutex_lock(&t->lock);
  if (t->held && !force && now_ns() - t->held_since < HOLD_TIMEOUT_NS) {
    pthread_mutex_unlock(&t->lock);
    return 0;
  }
  ghostty_render_state_update(t->state, t->term);
  GhosttyTerminalScrollbar scroll = {0};
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scroll);
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  pthread_mutex_unlock(&t->lock);

  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);
  int32_t cursor_flags = (cursor.visible ? 1 : 0) | (cursor.blinking ? 2 : 0) |
                         (cursor.wide_tail ? 4 : 0) | (cursor.viewport_has_value ? 8 : 0) |
                         (cursor.password_input ? 16 : 0) |
                         (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 32 : 0);
  int32_t cursor_now[4] = {cursor_flags, cursor.viewport_x, cursor.viewport_y, (int32_t)cursor.visual_style};

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  if (force) dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  // Rows are dirty only when cells change: a cursor that moved alone (an arrow at a prompt) is a frame of no rows.
  if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE && memcmp(cursor_now, t->cursor_was, sizeof cursor_now) != 0) dirty = GHOSTTY_RENDER_STATE_DIRTY_PARTIAL;
  if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return 0;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  size_t needed = HEADER_INTS + (size_t)rows * (ROW_HEADER_INTS + (size_t)cols * (CELL_INTS + 2 + MAX_GRAPHEME));
  if (o.cap < needed) return -(int)(needed * sizeof(int32_t));
  memcpy(t->cursor_was, cursor_now, sizeof cursor_now);

  put(&o, dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL ? 2 : 1);
  put(&o, cols);
  put(&o, rows);
  put(&o, cursor_flags);
  put(&o, cursor.viewport_x);
  put(&o, cursor.viewport_y);
  put(&o, (int32_t)cursor.visual_style);
  put(&o, rgb(colors.background));
  put(&o, rgb(colors.foreground));
  put(&o, colors.cursor_has_value ? rgb(colors.cursor) : NO_COLOR);
  size_t row_count_at = o.len;
  put(&o, 0);
  put(&o, (int32_t)scroll.total);
  put(&o, (int32_t)scroll.offset);
  put(&o, (int32_t)scroll.len);

  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &t->rows);
  int32_t row_count = 0;
  uint16_t y = 0;
  uint32_t cps[MAX_GRAPHEME];
  for (uint16_t next = 0;; next++) {
    if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL) {
      if (!ghostty_render_state_row_iterator_next(t->rows)) break;
      y = next;  // a full pass visits every row, top to bottom
    } else if (!ghostty_render_state_row_iterator_next_dirty(t->rows, &y)) {
      break;
    }
    row_count++;
    put(&o, y);
    size_t extras_at = o.len;
    put(&o, 0);
    size_t cells_at = o.len;
    memset(&o.buf[cells_at], 0, (size_t)cols * CELL_INTS * sizeof(int32_t));
    o.len += (size_t)cols * CELL_INTS;
    int32_t extras = 0;

    ghostty_render_state_row_get(t->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &t->cells);
    for (uint16_t x = 0; x < cols && ghostty_render_state_row_cells_next(t->cells); x++) {
      int32_t* cell = &o.buf[cells_at + (size_t)x * CELL_INTS];
      uint32_t glen = 0;
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
      if (glen > MAX_GRAPHEME) glen = MAX_GRAPHEME;
      if (glen > 0) ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);

      GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
      GhosttyColorRgb c;
      int32_t fg = ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &c) == GHOSTTY_SUCCESS ? rgb(c) : NO_COLOR;
      int32_t bg = ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &c) == GHOSTTY_SUCCESS ? rgb(c) : NO_COLOR;
      bool selected = false;
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
      GhosttyCell raw = 0;
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
        ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      }

      int32_t flags = (style.bold ? FLAG_BOLD : 0) | (style.italic ? FLAG_ITALIC : 0) |
                      (style.faint ? FLAG_FAINT : 0) | (style.blink ? FLAG_BLINK : 0) |
                      (style.inverse ? FLAG_INVERSE : 0) | (style.invisible ? FLAG_INVISIBLE : 0) |
                      (style.strikethrough ? FLAG_STRIKETHROUGH : 0) | (style.overline ? FLAG_OVERLINE : 0) |
                      ((style.underline & 7) << FLAG_UNDERLINE_SHIFT) | (((int32_t)wide & 3) << FLAG_WIDE_SHIFT) |
                      (selected ? FLAG_SELECTED : 0) | (glen > 1 ? FLAG_GRAPHEME : 0);
      cell[0] = glen > 0 ? (int32_t)cps[0] : 0;
      cell[1] = fg;
      cell[2] = bg;
      cell[3] = style_color(style.underline_color, &colors);
      cell[4] = flags;

      if (glen > 1) {
        put(&o, x);
        put(&o, (int32_t)glen - 1);
        for (uint32_t i = 1; i < glen; i++) put(&o, (int32_t)cps[i]);
        extras++;
      }
    }
    o.buf[extras_at] = extras;
  }
  o.buf[row_count_at] = row_count;
  ghostty_render_state_clean(t->state);
  return (int)(o.len * sizeof(int32_t));
}

int wf_term_select_word(WFTerm *t, int col, int row) {
  pthread_mutex_lock(&t->lock);
  int found = select_word(t, col, row);
  pthread_mutex_unlock(&t->lock);
  return found;
}

void wf_term_select_extend(WFTerm *t, int col, int row) {
  pthread_mutex_lock(&t->lock);
  select_extend(t, col, row);
  pthread_mutex_unlock(&t->lock);
}

void wf_term_select_hold(WFTerm *t, int end) {
  pthread_mutex_lock(&t->lock);
  select_hold(t, end);
  pthread_mutex_unlock(&t->lock);
}

void wf_term_select_to(WFTerm *t, int col, int row) {
  pthread_mutex_lock(&t->lock);
  select_to(t, col, row);
  pthread_mutex_unlock(&t->lock);
}

void wf_term_select_view(WFTerm *t) {
  pthread_mutex_lock(&t->lock);
  select_view(t);
  pthread_mutex_unlock(&t->lock);
}

void wf_term_unselect(WFTerm *t) {
  pthread_mutex_lock(&t->lock);
  unselect(t);
  pthread_mutex_unlock(&t->lock);
}

int wf_term_selection(WFTerm *t, int32_t *out) {
  pthread_mutex_lock(&t->lock);
  int has = selection(t, out);
  pthread_mutex_unlock(&t->lock);
  return has;
}

uint8_t *wf_term_selection_text(WFTerm *t, size_t *len) {
  pthread_mutex_lock(&t->lock);
  uint8_t *out = selection_text(t, len);
  pthread_mutex_unlock(&t->lock);
  return out;
}
