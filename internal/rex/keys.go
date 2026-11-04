package rex

import (
	"strconv"
	"strings"
)

// A key is a key press as Rex takes it: the physical key, by its W3C code,
// and the modifiers held. The terminal encodes it for its program's modes.
type key struct {
	Key                string `json:"key"`
	Mods               string `json:"mods"`
	ConsumedMods       string `json:"consumed_mods"`
	Text               string `json:"text"`
	UnshiftedCodepoint int    `json:"unshifted_codepoint"`
	Composing          bool   `json:"composing"`
	Action             string `json:"action"`
}

var named = map[string]string{
	"Enter": "enter", "Escape": "escape", "Tab": "tab", "BTab": "tab", "BSpace": "backspace", "Space": "space",
	"Up": "arrow_up", "Down": "arrow_down", "Left": "arrow_left", "Right": "arrow_right",
	"Home": "home", "End": "end", "PageUp": "page_up", "PPage": "page_up", "PageDown": "page_down", "NPage": "page_down",
	"IC": "insert", "DC": "delete",
}

// Where a character is on a US keyboard, which is how Rex reads one sent
// without a keyboard, and whether it takes shift.
var keyboard = map[rune]struct {
	key   string
	shift bool
}{}

func init() {
	for i := 1; i <= 12; i++ {
		named["F"+strconv.Itoa(i)] = "f" + strconv.Itoa(i)
	}
	put := func(plain, shifted, key string) {
		keyboard[rune(plain[0])] = struct {
			key   string
			shift bool
		}{key, false}
		keyboard[rune(shifted[0])] = struct {
			key   string
			shift bool
		}{key, true}
	}
	for c := 'a'; c <= 'z'; c++ {
		put(string(c), strings.ToUpper(string(c)), "key_"+string(c))
	}
	for i, shifted := range ")!@#$%^&*(" {
		put(strconv.Itoa(i), string(shifted), "digit_"+strconv.Itoa(i))
	}
	for _, k := range [][3]string{
		{"-", "_", "minus"}, {"=", "+", "equal"}, {"[", "{", "bracket_left"}, {"]", "}", "bracket_right"},
		{`\`, "|", "backslash"}, {";", ":", "semicolon"}, {"'", `"`, "quote"}, {",", "<", "comma"},
		{".", ">", "period"}, {"/", "?", "slash"}, {"`", "~", "backquote"},
	} {
		put(k[0], k[1], k[2])
	}
}

// keyOf is a key by wallflower's name (C-c, M-Enter, S-Up, BTab) as Rex's,
// or false for text to type as it is.
func keyOf(name string) (key, bool) {
	var shift, ctrl, alt bool
	rest := name
	for len(rest) > 2 && rest[1] == '-' {
		switch rest[0] {
		case 'C':
			ctrl = true
		case 'M':
			alt = true
		case 'S':
			shift = true
		default:
			return key{}, false
		}
		rest = rest[2:]
	}
	k := key{Action: "press"}
	if code, ok := named[rest]; ok {
		k.Key = code
		shift = shift || rest == "BTab"
		if rest == "Space" {
			k.Text, k.UnshiftedCodepoint = " ", ' '
		}
	} else {
		r := []rune(rest)
		pos, ok := keyboard[r[0]]
		if len(r) != 1 || !ok || !ctrl && !alt {
			return key{}, false // text, typed as it is
		}
		k.Key = pos.key
		k.UnshiftedCodepoint = unshifted(pos.key)
		if pos.shift {
			shift = true
			k.ConsumedMods = "shift"
		}
		if !ctrl {
			k.Text = string(r)
		}
	}
	var mods []string
	for _, m := range []struct {
		on   bool
		name string
	}{{shift, "shift"}, {ctrl, "ctrl"}, {alt, "alt"}} {
		if m.on {
			mods = append(mods, m.name)
		}
	}
	k.Mods = strings.Join(mods, "+")
	return k, true
}

// unshifted is the character a key types without shift.
func unshifted(code string) int {
	for c, pos := range keyboard {
		if pos.key == code && !pos.shift {
			return int(c)
		}
	}
	return 0
}
