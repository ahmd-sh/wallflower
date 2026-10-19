// JNI bridge between sh.ahmd.wallflower.terminal.Terminal and libghostty-vt.
//
// One handle per pane: the IO thread writes pane output, the UI thread packs
// what changed into a frame buffer once per vsync. The frame layout is
// documented in FrameBuffer.kt; the constants here must match it. Rows of
// the scrollback pack into the same layout, for showing commands apart.

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
#define MAX_COMMANDS 4096

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
  FLAG_ROLE_SHIFT = 15,      // 2 bits, the OSC 133 role: 0 output, 1 input, 2 prompt
};

// A command the shell marked (OSC 133): pinned where its prompt starts and
// where its output starts, so it keeps its place as the screen scrolls,
// reflows and drops old scrollback.
typedef struct {
  int32_t id;
  GhosttyTrackedGridRef start;
  GhosttyTrackedGridRef output;  // NULL until it runs
  uint32_t trimmed_for;          // the length of the rows it had when trimmed was worked out; 0 for not yet
  uint32_t trimmed;              // rows up to its last with text
} Command;

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
  GhosttyMouseEncoder mouse; // created on first wheel event
  bool frame_stale;          // rows were packed, which can take changes from the frame's render state
  Command* commands;
  size_t command_count, command_cap;
  int32_t command_ids;
  bool commands_changed;
  // Rows have their own render state, packed one at a time.
  pthread_mutex_t rows_lock;
  GhosttyRenderState rows_state;
  GhosttyRenderStateRowIterator rows_rows;
  GhosttyRenderStateRowCells rows_cells;
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

static GhosttyPoint active_row(uint16_t y) {
  return (GhosttyPoint){.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = 0, .y = y}}};
}

static uint16_t cursor_row(Term* t) {
  uint16_t y = 0;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
  return y;
}

// Where a pinned row is now, counting from the top of the scrollback.
static bool row_of(GhosttyTrackedGridRef ref, uint32_t* y) {
  GhosttyPointCoordinate c;
  if (!ref || ghostty_tracked_grid_ref_point(ref, GHOSTTY_POINT_TAG_SCREEN, &c) != GHOSTTY_SUCCESS) return false;
  *y = c.y;
  return true;
}

static bool cursor_screen_row(Term* t, uint32_t* y) {
  GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  GhosttyPointCoordinate c;
  if (ghostty_terminal_grid_ref(t->term, active_row(cursor_row(t)), &ref) != GHOSTTY_SUCCESS ||
      ghostty_terminal_point_from_grid_ref(t->term, &ref, GHOSTTY_POINT_TAG_SCREEN, &c) != GHOSTTY_SUCCESS) {
    return false;
  }
  *y = c.y;
  return true;
}

static GhosttyTrackedGridRef pin_cursor_row(Term* t) {
  GhosttyTrackedGridRef ref = NULL;
  if (ghostty_terminal_grid_ref_track(t->term, active_row(cursor_row(t)), &ref) != GHOSTTY_SUCCESS) return NULL;
  return ref;
}

static void forget(Command* c) {
  ghostty_tracked_grid_ref_free(c->start);
  ghostty_tracked_grid_ref_free(c->output);
}

static void add_command(Term* t, GhosttyTrackedGridRef start) {
  if (!start) return;
  if (t->command_count == MAX_COMMANDS) {
    forget(&t->commands[0]);
    memmove(t->commands, t->commands + 1, (t->command_count - 1) * sizeof(Command));
    t->command_count--;
  }
  if (t->command_count == t->command_cap) {
    size_t cap = t->command_cap ? t->command_cap * 2 : 64;
    Command* grown = realloc(t->commands, cap * sizeof(Command));
    if (!grown) {
      ghostty_tracked_grid_ref_free(start);
      return;
    }
    t->commands = grown;
    t->command_cap = cap;
  }
  t->commands[t->command_count++] = (Command){.id = ++t->command_ids, .start = start};
  t->commands_changed = true;
}

// The shell marks its prompts and output (OSC 133). A primary prompt starts
// a command; one drawn again before anything ran is the same prompt where
// it was or higher up (redrawn, the screen cleared), or below, a new one
// after a line left unrun (Ctrl-C, an empty line). Runs inside vt_write, so
// the lock is held.
static void on_semantic_prompt(GhosttyTerminal term, void* userdata, const GhosttyTerminalSemanticPrompt* e) {
  (void)term;
  Term* t = userdata;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  if (screen != GHOSTTY_TERMINAL_SCREEN_PRIMARY) return;
  Command* last = t->command_count ? &t->commands[t->command_count - 1] : NULL;
  switch (e->kind) {
    case GHOSTTY_SEMANTIC_PROMPT_PROMPT_START: {
      if (e->prompt_kind != GHOSTTY_SEMANTIC_PROMPT_PROMPT_PRIMARY) return;
      uint32_t y = 0, at = 0;
      if (last && !last->output && cursor_screen_row(t, &y) && row_of(last->start, &at) && y <= at) {
        ghostty_tracked_grid_ref_set(last->start, t->term, active_row(cursor_row(t)));
        last->trimmed_for = 0;
        t->commands_changed = true;
        return;
      }
      add_command(t, pin_cursor_row(t));
      break;
    }
    case GHOSTTY_SEMANTIC_PROMPT_OUTPUT_START:
      if (!last || last->output) return;
      last->output = pin_cursor_row(t);
      t->commands_changed = true;
      break;
    default:
      break;
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
      ghostty_render_state_row_cells_new(NULL, &t->cells) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &t->rows_state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &t->rows_rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &t->rows_cells) != GHOSTTY_SUCCESS) {
    if (t->rows_cells) ghostty_render_state_row_cells_free(t->rows_cells);
    if (t->rows_rows) ghostty_render_state_row_iterator_free(t->rows_rows);
    if (t->rows_state) ghostty_render_state_free(t->rows_state);
    if (t->cells) ghostty_render_state_row_cells_free(t->cells);
    if (t->rows) ghostty_render_state_row_iterator_free(t->rows);
    if (t->state) ghostty_render_state_free(t->state);
    if (t->term) ghostty_terminal_free(t->term);
    free(t);
    return 0;
  }
  pthread_mutex_init(&t->lock, NULL);
  pthread_mutex_init(&t->rows_lock, NULL);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void*)on_render_hold);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT, (const void*)on_semantic_prompt);
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
  ghostty_render_state_row_cells_free(t->rows_cells);
  ghostty_render_state_row_iterator_free(t->rows_rows);
  ghostty_render_state_free(t->rows_state);
  for (size_t i = 0; i < t->command_count; i++) forget(&t->commands[i]);
  free(t->commands);
  if (t->mouse) ghostty_mouse_encoder_free(t->mouse);
  ghostty_terminal_free(t->term);
  pthread_mutex_destroy(&t->lock);
  pthread_mutex_destroy(&t->rows_lock);
  free(t->scratch);
  free(t);
}

// Returns whether the shell marked a command's prompt or output in it.
JNIEXPORT jboolean JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeWrite(JNIEnv* env, jclass cls, jlong h, jbyteArray data, jint off, jint len) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  if ((size_t)len > t->scratch_len) {
    uint8_t* grown = realloc(t->scratch, (size_t)len);
    if (!grown) { pthread_mutex_unlock(&t->lock); return JNI_FALSE; }
    t->scratch = grown;
    t->scratch_len = (size_t)len;
  }
  (*env)->GetByteArrayRegion(env, data, off, len, (jbyte*)t->scratch);
  t->commands_changed = false;
  ghostty_terminal_vt_write(t->term, t->scratch, (size_t)len);
  bool changed = t->commands_changed;
  pthread_mutex_unlock(&t->lock);
  return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeResize(JNIEnv* env, jclass cls, jlong h, jint cols, jint rows, jint cellW, jint cellH) {
  (void)env; (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  ghostty_terminal_resize(t->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cellW, (uint32_t)cellH);
  for (size_t i = 0; i < t->command_count; i++) t->commands[i].trimmed_for = 0; // rows reflow
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

// A wheel notch at a cell, for full-screen apps that own their scrolling.
// Returns the bytes to send if the app tracks the mouse, 0 if it's on the
// alternate screen without mouse tracking (send arrows instead), and -1 on
// the primary screen, where scrollback is local.
JNIEXPORT jint JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeWheel(JNIEnv* env, jclass cls, jlong h, jboolean up, jint col, jint row, jbyteArray out) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  char buf[64];
  size_t len = 0;
  jint result = 0;
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
      if (ghostty_mouse_encoder_encode(t->mouse, ev, buf, sizeof buf, &len) == GHOSTTY_SUCCESS) result = (jint)len;
      ghostty_mouse_event_free(ev);
    }
  } else if (screen == GHOSTTY_TERMINAL_SCREEN_PRIMARY) {
    result = -1;
  }
  pthread_mutex_unlock(&t->lock);
  if (result > 0) (*env)->SetByteArrayRegion(env, out, 0, result, (const jbyte*)buf);
  return result;
}

// The active screen, for reading what an app's input line holds. Layout:
// u16 cols, u16 rows, u16 cursor x, u16 cursor y, then per row a flags byte
// (1: soft-wrapped onto the next row) and per cell an attribute byte and a
// codepoint count, then that many i32 codepoints. Attributes: bits 0-1 the
// OSC 133 role (0 output, 1 input, 2 prompt), 4 faint, 8 in palette color 8
// (autosuggestion grey), 16 the second half of a wide cell, 32 in a grey (see
// muted), 64 highlighted: inverse, or on a background color.
JNIEXPORT jbyteArray JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeScreen(JNIEnv* env, jclass cls, jlong h) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
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
  jbyteArray result = (*env)->NewByteArray(env, (jsize)len);
  if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (const jbyte*)out);
  free(out);
  return result;
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

// Ints a frame of rows needs at most.
static size_t frame_ints(uint16_t cols, size_t rows) {
  return HEADER_INTS + rows * (ROW_HEADER_INTS + (size_t)cols * (CELL_INTS + 2 + MAX_GRAPHEME));
}

// Packs the row the iterator is on as row y.
static void pack_row(Out* o, GhosttyRenderStateRowIterator rows, GhosttyRenderStateRowCells cells, int32_t y, uint16_t cols,
                     const GhosttyRenderStateColors* colors) {
  put(o, y);
  size_t extras_at = o->len;
  put(o, 0);
  size_t cells_at = o->len;
  memset(&o->buf[cells_at], 0, (size_t)cols * CELL_INTS * sizeof(int32_t));
  o->len += (size_t)cols * CELL_INTS;
  int32_t extras = 0;
  uint32_t cps[MAX_GRAPHEME];

  ghostty_render_state_row_get(rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells);
  for (uint16_t x = 0; x < cols && ghostty_render_state_row_cells_next(cells); x++) {
    int32_t* cell = &o->buf[cells_at + (size_t)x * CELL_INTS];
    uint32_t glen = 0;
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
    if (glen > MAX_GRAPHEME) glen = MAX_GRAPHEME;
    if (glen > 0) ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);

    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
    GhosttyColorRgb c;
    int32_t fg = ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &c) == GHOSTTY_SUCCESS ? rgb(c) : NO_COLOR;
    int32_t bg = ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &c) == GHOSTTY_SUCCESS ? rgb(c) : NO_COLOR;
    bool selected = false;
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
    GhosttyCell raw = 0;
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    GhosttyCellSemanticContent role = GHOSTTY_CELL_SEMANTIC_OUTPUT;
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_SEMANTIC_CONTENT, &role);
    }

    int32_t flags = (style.bold ? FLAG_BOLD : 0) | (style.italic ? FLAG_ITALIC : 0) |
                    (style.faint ? FLAG_FAINT : 0) | (style.blink ? FLAG_BLINK : 0) |
                    (style.inverse ? FLAG_INVERSE : 0) | (style.invisible ? FLAG_INVISIBLE : 0) |
                    (style.strikethrough ? FLAG_STRIKETHROUGH : 0) | (style.overline ? FLAG_OVERLINE : 0) |
                    ((style.underline & 7) << FLAG_UNDERLINE_SHIFT) | (((int32_t)wide & 3) << FLAG_WIDE_SHIFT) |
                    (selected ? FLAG_SELECTED : 0) | (glen > 1 ? FLAG_GRAPHEME : 0) | (((int32_t)role & 3) << FLAG_ROLE_SHIFT);
    cell[0] = glen > 0 ? (int32_t)cps[0] : 0;
    cell[1] = fg;
    cell[2] = bg;
    cell[3] = style_color(style.underline_color, colors);
    cell[4] = flags;

    if (glen > 1) {
      put(o, x);
      put(o, (int32_t)glen - 1);
      for (uint32_t i = 1; i < glen; i++) put(o, (int32_t)cps[i]);
      extras++;
    }
  }
  o->buf[extras_at] = extras;
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
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  if (t->frame_stale) force = JNI_TRUE;
  t->frame_stale = false;
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

  size_t needed = frame_ints(cols, rows);
  if (o.cap < needed) return -(jint)(needed * sizeof(int32_t));

  int32_t cursor_flags = (cursor.visible ? 1 : 0) | (cursor.blinking ? 2 : 0) |
                         (cursor.wide_tail ? 4 : 0) | (cursor.viewport_has_value ? 8 : 0) |
                         (cursor.password_input ? 16 : 0) |
                         (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 32 : 0);
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
  for (uint16_t next = 0;; next++) {
    if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL) {
      if (!ghostty_render_state_row_iterator_next(t->rows)) break;
      y = next;  // a full pass visits every row, top to bottom
    } else if (!ghostty_render_state_row_iterator_next_dirty(t->rows, &y)) {
      break;
    }
    row_count++;
    pack_row(&o, t->rows, t->cells, y, cols, &colors);
  }
  o.buf[row_count_at] = row_count;
  ghostty_render_state_clean(t->state);
  return (jint)(o.len * sizeof(int32_t));
}

static bool row_has_text(Term* t, uint32_t y, uint32_t active_top, uint16_t cols) {
  for (uint16_t x = 0; x < cols; x++) {
    GhosttyPoint pt = y >= active_top
      ? (GhosttyPoint){.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = x, .y = y - active_top}}}
      : (GhosttyPoint){.tag = GHOSTTY_POINT_TAG_SCREEN, .value = {.coordinate = {.x = x, .y = y}}};
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyCell cell;
    bool text = false;
    if (ghostty_terminal_grid_ref(t->term, pt, &ref) == GHOSTTY_SUCCESS && ghostty_grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS &&
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_TEXT, &text) == GHOSTTY_SUCCESS && text) {
      return true;
    }
  }
  return false;
}

// Rows from `from` up to `to` that end with the last one with text, at least `least`.
static uint32_t trim(Term* t, uint32_t from, uint32_t to, uint32_t least, uint32_t active_top, uint16_t cols) {
  uint32_t end = to;
  while (end > from + least && !row_has_text(t, end - 1, active_top, cols)) end--;
  return end - from;
}

// The commands the shell marked on the primary screen, oldest first, as
// ints: alternate (1 while a full-screen app has the alternate screen, and
// nothing else follows), rows (the screen's, scrollback included), history
// (rows above the first command, up to the last with text), count, then per
// command its id, the row its prompt starts on, the row its output starts on
// (-1 if it hasn't run), and the row after its last with text. Commands
// whose prompt left the scrollback are forgotten.
JNIEXPORT jintArray JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeCommands(JNIEnv* env, jclass cls, jlong h) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  pthread_mutex_lock(&t->lock);
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  if (screen != GHOSTTY_TERMINAL_SCREEN_PRIMARY) {
    pthread_mutex_unlock(&t->lock);
    jintArray alt = (*env)->NewIntArray(env, 1);
    jint one = 1;
    if (alt) (*env)->SetIntArrayRegion(env, alt, 0, 1, &one);
    return alt;
  }
  size_t kept = 0;
  for (size_t i = 0; i < t->command_count; i++) {
    uint32_t y;
    if (!row_of(t->commands[i].start, &y)) {
      forget(&t->commands[i]);
      continue;
    }
    t->commands[kept++] = t->commands[i];
  }
  t->command_count = kept;

  size_t total = 0;
  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  uint32_t active_top = total > rows ? (uint32_t)(total - rows) : 0;
  size_t n = t->command_count;
  jint* out = malloc((4 + n * 4) * sizeof(jint));
  if (!out) {
    pthread_mutex_unlock(&t->lock);
    return NULL;
  }
  uint32_t first = 0;
  if (n == 0 || !row_of(t->commands[0].start, &first)) first = (uint32_t)total;
  out[0] = 0;
  out[1] = (jint)total;
  out[2] = (jint)trim(t, 0, first, 0, active_top, cols);
  out[3] = (jint)n;
  for (size_t i = 0; i < n; i++) {
    Command* c = &t->commands[i];
    uint32_t start = 0, output = 0, end = (uint32_t)total;
    row_of(c->start, &start);
    bool ran = row_of(c->output, &output);
    if (i + 1 < n) row_of(t->commands[i + 1].start, &end);
    if (end < start) end = start;
    // Older commands are done, so their rows only change as they reflow; the newest is still going.
    uint32_t length = end - start;
    if (i + 1 == n || c->trimmed_for != length) {
      c->trimmed = trim(t, start, end, 1, active_top, cols);
      c->trimmed_for = i + 1 == n ? 0 : length;
    }
    out[4 + i * 4] = c->id;
    out[5 + i * 4] = (jint)start;
    out[6 + i * 4] = ran ? (jint)output : -1;
    out[7 + i * 4] = (jint)(start + c->trimmed);
  }
  pthread_mutex_unlock(&t->lock);
  jsize len = (jsize)(4 + n * 4);
  jintArray result = (*env)->NewIntArray(env, len);
  if (result) (*env)->SetIntArrayRegion(env, result, 0, len, out);
  free(out);
  return result;
}

// Packs rows from..from+count of the primary screen (row 0 is the top of the
// scrollback) into buffer, as a full frame of count rows without a cursor:
// the viewport scrolls there for its own render state to read, then back.
// Returns the bytes written, 0 while a full-screen app has the alternate
// screen, or -needed if the buffer is too small.
JNIEXPORT jint JNICALL
Java_sh_ahmd_wallflower_terminal_Terminal_nativeRows(JNIEnv* env, jclass cls, jlong h, jobject buffer, jint from, jint count) {
  (void)cls;
  Term* t = (Term*)(intptr_t)h;
  Out o = {
    .buf = (*env)->GetDirectBufferAddress(env, buffer),
    .cap = (size_t)(*env)->GetDirectBufferCapacity(env, buffer) / sizeof(int32_t),
  };
  if (from < 0 || count <= 0) return 0;
  pthread_mutex_lock(&t->rows_lock);
  pthread_mutex_lock(&t->lock);
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  uint16_t cols = 0;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  size_t needed = frame_ints(cols, (size_t)count);
  jint result = screen != GHOSTTY_TERMINAL_SCREEN_PRIMARY ? 0 : o.cap < needed ? -(jint)(needed * sizeof(int32_t)) : 1;
  if (result != 1) {
    pthread_mutex_unlock(&t->lock);
    pthread_mutex_unlock(&t->rows_lock);
    return result;
  }

  GhosttyTerminalScrollbar was = {0};
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &was);
  bool bottom = was.offset + was.len >= was.total;
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  size_t head = o.len;
  o.len += HEADER_INTS;
  int32_t packed = 0;
  while (packed < count) {
    GhosttyTerminalScrollViewport to = {.tag = GHOSTTY_SCROLL_VIEWPORT_ROW, .value = {.row = (size_t)(from + packed)}};
    ghostty_terminal_scroll_viewport(t->term, to);
    ghostty_render_state_update(t->rows_state, t->term);
    GhosttyTerminalScrollbar at = {0};
    ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &at);
    ghostty_render_state_get(t->rows_state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    ghostty_render_state_get(t->rows_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &t->rows_rows);
    int32_t before = packed;
    for (int64_t y = (int64_t)at.offset; ghostty_render_state_row_iterator_next(t->rows_rows) && packed < count; y++) {
      if (y < from + packed) continue; // clamped above the rows asked for, at the bottom
      pack_row(&o, t->rows_rows, t->rows_cells, packed, cols, &colors);
      packed++;
    }
    if (packed == before) break; // past the last row
  }
  GhosttyTerminalScrollViewport back = bottom
    ? (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM}
    : (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_ROW, .value = {.row = (size_t)was.offset}};
  ghostty_terminal_scroll_viewport(t->term, back);
  t->frame_stale = true;
  pthread_mutex_unlock(&t->lock);
  pthread_mutex_unlock(&t->rows_lock);

  int32_t header[HEADER_INTS] = {2, cols, packed, 0, 0, 0, 0, rgb(colors.background), rgb(colors.foreground), NO_COLOR, packed, 0, 0, 0};
  memcpy(&o.buf[head], header, sizeof header);
  return (jint)(o.len * sizeof(int32_t));
}
