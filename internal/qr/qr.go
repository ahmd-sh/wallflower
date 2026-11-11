// Package qr makes QR codes (ISO/IEC 18004), bytes in at error correction
// level L or M, and draws them for a terminal.
package qr

import (
	"errors"
	"strings"
)

// Level is how much of a code can be lost and still read: L about 7%, M 15%.
type Level int

const (
	L Level = iota
	M
)

// Code is a QR code's modules, true for dark, Size on a side.
type Code struct {
	Size    int
	modules [][]bool
}

// Dark is whether the module at column x, row y is dark.
func (c *Code) Dark(x, y int) bool { return c.modules[y][x] }

// Encode makes the smallest code that holds data at level.
func Encode(data []byte, level Level) (*Code, error) {
	for v := 1; v <= 40; v++ {
		capacity := dataCodewords(v, level) * 8
		count := 8
		if v >= 10 {
			count = 16
		}
		if 4+count+8*len(data) <= capacity {
			return build(v, level, codewords(data, v, level, count))
		}
	}
	return nil, errors.New("qr: too much data")
}

// codewords are data's bits as byte mode lays them out, padded to the
// version's capacity.
func codewords(data []byte, v int, level Level, count int) []byte {
	var b bits
	b.put(0b0100, 4)
	b.put(len(data), count)
	for _, d := range data {
		b.put(int(d), 8)
	}
	capacity := dataCodewords(v, level) * 8
	b.put(0, min(4, capacity-len(b)))
	b.put(0, (8-len(b)%8)%8)
	for pad := 0xEC; len(b) < capacity; pad ^= 0xEC ^ 0x11 {
		b.put(pad, 8)
	}
	out := make([]byte, len(b)/8)
	for i, bit := range b {
		if bit {
			out[i/8] |= 0x80 >> (i % 8)
		}
	}
	return out
}

type bits []bool

func (b *bits) put(v, n int) {
	for i := n - 1; i >= 0; i-- {
		*b = append(*b, v>>i&1 == 1)
	}
}

type grid struct {
	size     int
	modules  [][]bool
	function [][]bool
}

func build(v int, level Level, data []byte) (*Code, error) {
	size := v*4 + 17
	g := &grid{size: size, modules: square(size), function: square(size)}
	g.patterns(v)
	g.place(interleave(data, v, level))
	best, penalty := 0, -1
	for mask := range 8 {
		g.mask(mask)
		g.format(level, mask)
		if p := g.penalty(); penalty < 0 || p < penalty {
			best, penalty = mask, p
		}
		g.mask(mask) // undone: it flips the same modules back
	}
	g.mask(best)
	g.format(level, best)
	return &Code{Size: size, modules: g.modules}, nil
}

func square(n int) [][]bool {
	rows := make([][]bool, n)
	for i := range rows {
		rows[i] = make([]bool, n)
	}
	return rows
}

func (g *grid) set(x, y int, dark bool) {
	g.modules[y][x] = dark
	g.function[y][x] = true
}

// patterns draws what every code of version v has: timing rows, finders,
// alignment marks, and room for the format (and version) bits.
func (g *grid) patterns(v int) {
	for i := range g.size {
		g.set(6, i, i%2 == 0)
		g.set(i, 6, i%2 == 0)
	}
	for _, c := range [][2]int{{3, 3}, {g.size - 4, 3}, {3, g.size - 4}} {
		for dy := -4; dy <= 4; dy++ {
			for dx := -4; dx <= 4; dx++ {
				x, y := c[0]+dx, c[1]+dy
				if x >= 0 && x < g.size && y >= 0 && y < g.size {
					d := max(abs(dx), abs(dy))
					g.set(x, y, d != 2 && d != 4)
				}
			}
		}
	}
	at := alignments(v)
	for i, x := range at {
		for j, y := range at {
			if i == 0 && j == 0 || i == 0 && j == len(at)-1 || i == len(at)-1 && j == 0 {
				continue
			}
			for dy := -2; dy <= 2; dy++ {
				for dx := -2; dx <= 2; dx++ {
					g.set(x+dx, y+dy, max(abs(dx), abs(dy)) != 1)
				}
			}
		}
	}
	g.format(L, 0) // a placeholder, so the data goes around it
	if v >= 7 {
		r := v
		for range 12 {
			r = r<<1 ^ (r>>11)*0x1F25
		}
		word := v<<12 | r
		for i := range 18 {
			dark := word>>i&1 == 1
			a, b := g.size-11+i%3, i/3
			g.set(a, b, dark)
			g.set(b, a, dark)
		}
	}
}

// format writes the level and mask, twice, with the dark module.
func (g *grid) format(level Level, mask int) {
	data := []int{L: 1, M: 0}[level]<<3 | mask
	r := data
	for range 10 {
		r = r<<1 ^ (r>>9)*0x537
	}
	word := (data<<10 | r) ^ 0x5412
	bit := func(i int) bool { return word>>i&1 == 1 }
	for i := range 6 {
		g.set(8, i, bit(i))
	}
	g.set(8, 7, bit(6))
	g.set(8, 8, bit(7))
	g.set(7, 8, bit(8))
	for i := 9; i < 15; i++ {
		g.set(14-i, 8, bit(i))
	}
	for i := range 8 {
		g.set(g.size-1-i, 8, bit(i))
	}
	for i := 8; i < 15; i++ {
		g.set(8, g.size-15+i, bit(i))
	}
	g.set(8, g.size-8, true)
}

// place lays the codewords in the two-column zigzag, up then down, from the
// right, around what's drawn.
func (g *grid) place(data []byte) {
	i := 0
	for right := g.size - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		for vert := range g.size {
			for j := range 2 {
				x := right - j
				y := vert
				if (right+1)&2 == 0 {
					y = g.size - 1 - vert
				}
				if !g.function[y][x] && i < len(data)*8 {
					g.modules[y][x] = data[i/8]>>(7-i%8)&1 == 1
					i++
				}
			}
		}
	}
}

func (g *grid) mask(m int) {
	for y := range g.size {
		for x := range g.size {
			if g.function[y][x] {
				continue
			}
			var flip bool
			switch m {
			case 0:
				flip = (x+y)%2 == 0
			case 1:
				flip = y%2 == 0
			case 2:
				flip = x%3 == 0
			case 3:
				flip = (x+y)%3 == 0
			case 4:
				flip = (x/3+y/2)%2 == 0
			case 5:
				flip = x*y%2+x*y%3 == 0
			case 6:
				flip = (x*y%2+x*y%3)%2 == 0
			case 7:
				flip = ((x+y)%2+x*y%3)%2 == 0
			}
			g.modules[y][x] = g.modules[y][x] != flip
		}
	}
}

// penalty scores how hard the code is to read, as the standard counts it:
// long runs, 2×2 blocks, things like a finder, and too much of one colour.
func (g *grid) penalty() int {
	n := g.size
	at := func(x, y int, across bool) bool {
		if across {
			return g.modules[y][x]
		}
		return g.modules[x][y]
	}
	score := 0
	finder := []bool{true, false, true, true, true, false, true}
	for _, across := range []bool{true, false} {
		for y := range n {
			run := 1
			for x := 1; x <= n; x++ {
				if x < n && at(x, y, across) == at(x-1, y, across) {
					run++
					continue
				}
				if run >= 5 {
					score += 3 + run - 5
				}
				run = 1
			}
			for x := 0; x+7 <= n; x++ {
				match := true
				for k, d := range finder {
					if at(x+k, y, across) != d {
						match = false
						break
					}
				}
				if !match {
					continue
				}
				light := func(from, to int) bool {
					for k := from; k < to; k++ {
						if k >= 0 && k < n && at(k, y, across) {
							return false
						}
					}
					return true
				}
				if light(x-4, x) || light(x+7, x+11) {
					score += 40
				}
			}
		}
	}
	dark := 0
	for y := range n {
		for x := range n {
			if g.modules[y][x] {
				dark++
			}
			if x+1 < n && y+1 < n {
				c := g.modules[y][x]
				if c == g.modules[y][x+1] && c == g.modules[y+1][x] && c == g.modules[y+1][x+1] {
					score += 3
				}
			}
		}
	}
	score += abs(dark*100/(n*n)-50) / 5 * 10
	return score
}

// interleave splits data into the version's blocks, adds each block's
// Reed-Solomon codewords, and interleaves them all.
func interleave(data []byte, v int, level Level) []byte {
	blocks := numBlocks[level][v]
	ecc := eccPerBlock[level][v]
	raw := rawModules(v) / 8
	short := blocks - raw%blocks
	shortLen := raw / blocks
	divisor := rsDivisor(ecc)
	var all [][]byte
	k := 0
	for i := range blocks {
		n := shortLen - ecc
		if i >= short {
			n++
		}
		block := append([]byte{}, data[k:k+n]...)
		k += n
		if i < short {
			block = append(block, 0)
		}
		all = append(all, append(block, rsRemainder(data[k-n:k], divisor)...))
	}
	var out []byte
	for i := range all[0] {
		for j, block := range all {
			if i != shortLen-ecc || j >= short {
				out = append(out, block[i])
			}
		}
	}
	return out
}

func rsDivisor(degree int) []byte {
	out := make([]byte, degree)
	out[degree-1] = 1
	root := byte(1)
	for range degree {
		for j := range out {
			out[j] = gfMul(out[j], root)
			if j+1 < degree {
				out[j] ^= out[j+1]
			}
		}
		root = gfMul(root, 2)
	}
	return out
}

func rsRemainder(data, divisor []byte) []byte {
	out := make([]byte, len(divisor))
	for _, b := range data {
		factor := b ^ out[0]
		copy(out, out[1:])
		out[len(out)-1] = 0
		for i, d := range divisor {
			out[i] ^= gfMul(d, factor)
		}
	}
	return out
}

func gfMul(x, y byte) byte {
	var z int
	for i := 7; i >= 0; i-- {
		z = z<<1 ^ (z>>7)*0x11D
		z ^= int(y>>i&1) * int(x)
	}
	return byte(z)
}

func alignments(v int) []int {
	if v == 1 {
		return nil
	}
	n := v/7 + 2
	step := (v*4 + n*2 + 1) / (n*2 - 2) * 2
	if v == 32 {
		step = 26
	}
	out := make([]int, n)
	out[0] = 6
	for i, pos := n-1, v*4+17-7; i >= 1; i, pos = i-1, pos-step {
		out[i] = pos
	}
	return out
}

// rawModules is how many modules of version v hold codewords.
func rawModules(v int) int {
	n := (16*v+128)*v + 64
	if v >= 2 {
		a := v/7 + 2
		n -= (25*a-10)*a - 55
		if v >= 7 {
			n -= 36
		}
	}
	return n
}

func dataCodewords(v int, level Level) int {
	return rawModules(v)/8 - eccPerBlock[level][v]*numBlocks[level][v]
}

var eccPerBlock = [2][41]int{
	L: {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
	M: {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
}

var numBlocks = [2][41]int{
	L: {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
	M: {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// Terminal draws the code two rows to a line in half blocks, black on
// white whatever the terminal's colours, with a quiet zone of quiet
// modules around it.
func (c *Code) Terminal(quiet int) string {
	dark := func(x, y int) bool {
		x, y = x-quiet, y-quiet
		return x >= 0 && y >= 0 && x < c.Size && y < c.Size && c.modules[y][x]
	}
	side := c.Size + 2*quiet
	var b strings.Builder
	for y := 0; y < side; y += 2 {
		b.WriteString("\x1b[38;5;16;48;5;231m")
		for x := range side {
			top, bottom := dark(x, y), y+1 < side && dark(x, y+1)
			switch {
			case top && bottom:
				b.WriteString("█")
			case top:
				b.WriteString("▀")
			case bottom:
				b.WriteString("▄")
			default:
				b.WriteString(" ")
			}
		}
		b.WriteString("\x1b[0m\n")
	}
	return b.String()
}
