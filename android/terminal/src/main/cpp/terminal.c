// JNI bridge between sh.ahmd.wallflower.terminal.Terminal and libghostty-vt.
//
// One handle per pane: the IO thread writes pane output, the UI thread packs
// what changed into a frame buffer once per vsync. The frame layout is
// documented in FrameBuffer.kt; the constants here must match it.

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <ghostty/vt.h>

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

typedef struct {
  GhosttyTerminal term;
  GhosttyRenderState state;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  pthread_mutex_t lock;
  bool held;
  int64_t held_since;
  uint8_t* scratch;
  size_t scratch_len;
} Term;

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

static int32_t style_color(GhosttyStyleColor c, const GhosttyRenderStateColors* colors) {
  switch (c.tag) {
    case GHOSTTY_STYLE_COLOR_RGB: return rgb(c.value.rgb);
    case GHOSTTY_STYLE_COLOR_PALETTE: return rgb(colors->palette[c.value.palette]);
    default: return NO_COLOR;
  }
}

JNIEXPORT jlong JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeNew(JNIEnv* env, jclass cls, jint cols, jint rows, jint scrollback) {
  (void)env; (void)cls;
  Term* t = calloc(1, sizeof(Term));
  if (!t) return 0;
  if (ghostty_terminal_new(NULL, &t->term, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &t->state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &t->rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &t->cells) != GHOSTTY_SUCCESS) {
    if (t->cells) ghostty_render_state_row_cells_free(t->cells);
    if (t->rows) ghostty_render_state_row_iterator_free(t->rows);
    if (t->state) ghostty_render_state_free(t->state);
    if (t->term) ghostty_terminal_free(t->term);
    free(t);
    return 0;
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
  return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeFree(JNIEnv* env, jclass cls, jlong h) {
  (void)env; (void)cls;
  Term* t = (Term*)(intptr_t)h;
  if (!t) return;
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->state);
  ghostty_terminal_free(t->term);
  pthread_mutex_destroy(&t->lock);
  free(t->scratch);
  free(t);
}

JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeWrite(JNIEnv* env, jclass cls, jlong h, jbyteArray data, jint off, jint len) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  if ((size_t)len > t->scratch_len) {
    uint8_t* grown = realloc(t->scratch, (size_t)len);
    if (!grown) { pthread_mutex_unlock(&t->lock); return; }
    t->scratch = grown;
    t->scratch_len = (size_t)len;
  }
  (*env)->GetByteArrayRegion(env, data, off, len, (jbyte*)t->scratch);
  ghostty_terminal_vt_write(t->term, t->scratch, (size_t)len);
  pthread_mutex_unlock(&t->lock);
}

JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeResize(JNIEnv* env, jclass cls, jlong h, jint cols, jint rows, jint cellW, jint cellH) {
  (void)env; (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  ghostty_terminal_resize(t->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cellW, (uint32_t)cellH);
  pthread_mutex_unlock(&t->lock);
}

// mode: 0 delta (rows, negative is up), 1 top, 2 bottom.
JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeScroll(JNIEnv* env, jclass cls, jlong h, jint mode, jint delta) {
  (void)env; (void)cls;
  Term* t = (Term*)(intptr_t)h;
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

// palette: 16 or 256 ints of 0xRRGGBB; the rest of the 256 keep their defaults.
JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeSetColors(JNIEnv* env, jclass cls, jlong h, jint fg, jint bg, jint cursor, jintArray palette) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  GhosttyColorRgb colors[256];
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, colors);
  jsize n = (*env)->GetArrayLength(env, palette);
  jint* p = (*env)->GetIntArrayElements(env, palette, NULL);
  for (jsize i = 0; i < n && i < 256; i++) {
    colors[i] = (GhosttyColorRgb){(uint8_t)(p[i] >> 16), (uint8_t)(p[i] >> 8), (uint8_t)p[i]};
  }
  (*env)->ReleaseIntArrayElements(env, palette, p, JNI_ABORT);
  GhosttyColorRgb f = {(uint8_t)(fg >> 16), (uint8_t)(fg >> 8), (uint8_t)fg};
  GhosttyColorRgb b = {(uint8_t)(bg >> 16), (uint8_t)(bg >> 8), (uint8_t)bg};
  GhosttyColorRgb c = {(uint8_t)(cursor >> 16), (uint8_t)(cursor >> 8), (uint8_t)cursor};
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, colors);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &f);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &b);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c);
  pthread_mutex_unlock(&t->lock);
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
JNIEXPORT jint JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeFrame(JNIEnv* env, jclass cls, jlong h, jobject buffer, jboolean force) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  Out o = {
    .buf = (*env)->GetDirectBufferAddress(env, buffer),
    .cap = (size_t)(*env)->GetDirectBufferCapacity(env, buffer) / sizeof(int32_t),
  };

  pthread_mutex_lock(&t->lock);
  if (t->held && !force && now_ns() - t->held_since < HOLD_TIMEOUT_NS) {
    pthread_mutex_unlock(&t->lock);
    return 0;
  }
  ghostty_render_state_update(t->state, t->term);
  GhosttyTerminalScrollbar scroll = {0};
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scroll);
  pthread_mutex_unlock(&t->lock);

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  if (force) dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return 0;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(t->state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  size_t needed = HEADER_INTS + (size_t)rows * (ROW_HEADER_INTS + (size_t)cols * (CELL_INTS + 2 + MAX_GRAPHEME));
  if (o.cap < needed) return -(jint)(needed * sizeof(int32_t));

  int32_t cursor_flags = (cursor.visible ? 1 : 0) | (cursor.blinking ? 2 : 0) |
                         (cursor.wide_tail ? 4 : 0) | (cursor.viewport_has_value ? 8 : 0) |
                         (cursor.password_input ? 16 : 0);
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
  return (jint)(o.len * sizeof(int32_t));
}
