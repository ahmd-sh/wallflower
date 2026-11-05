package claude

import (
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

// Lines shaped like Claude Code's transcripts, trimmed to the fields read.
var conversation = []string{
	`{"type":"permission-mode","permissionMode":"default","sessionId":"s"}`,
	`{"type":"user","uuid":"u1","message":{"role":"user","content":"\n\n<pasted_content id=\"7\">\nfix the tests\n</pasted_content id=\"7\">"}}`,
	`{"type":"user","uuid":"m1","isMeta":true,"message":{"role":"user","content":"[Image: original 1280x2856]"}}`,
	`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"x"}]}}`,
	`{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"go test ./...","description":"Run tests"}}]}}`,
	`{"type":"assistant","uuid":"a3","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"a subagent's"}]}}`,
	`{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":true,"content":"\u001b[31mFAIL\u001b[0m x_test.go"}]}}`,
	`{"type":"assistant","uuid":"a4","message":{"role":"assistant","content":[{"type":"text","text":"**Fixed.** All pass."}]}}`,
	`{"type":"user","uuid":"u3","message":{"role":"user","content":"<command-name>/compact</command-name>"}}`,
	`{"type":"user","uuid":"u4","message":{"role":"user","content":"<local-command-stdout>Compacted</local-command-stdout>"}}`,
	`{"type":"user","uuid":"u5","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued..."}}`,
	`{"type":"user","uuid":"u6","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}},{"type":"text","text":"look"}]}}`,
	`{"type":"user","uuid":"u7","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}`,
	`{"type":"user","uuid":"u8","promptSource":"system","message":{"role":"user","content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n<summary>Background command \"Sleep\" completed (exit code 0)</summary>\n</task-notification>"}}`,
	`{"type":"user","uuid":"u9","promptSource":"system","message":{"role":"user","content":"<something-else>no summary</something-else>"}}`,
	`not json`,
}

func TestParse(t *testing.T) {
	p := newParser()
	var got []mobileview.Entry
	for _, l := range conversation {
		got = append(got, p.Line([]byte(l))...)
	}
	want := []mobileview.Entry{
		{ID: "u1.0", From: "user", Kind: "said", Text: "fix the tests"},
		{ID: "a2.0", From: "program", Kind: "step", Name: "Bash", Text: "go test ./...", Status: "running"},
		{ID: "a2.0", From: "program", Kind: "step", Name: "Bash", Text: "go test ./...", Status: "failed", Output: "FAIL x_test.go"},
		{ID: "a4.0", From: "program", Kind: "said", Text: "**Fixed.** All pass."},
		{ID: "u3.0", From: "user", Kind: "step", Name: "/compact", Status: "done"},
		{ID: "u3.0", From: "user", Kind: "step", Name: "/compact", Status: "done", Output: "Compacted"},
		{ID: "u5.0", From: "user", Kind: "note", Text: "Conversation compacted", Output: "This session is being continued..."},
		{ID: "u6.0", From: "user", Kind: "picture", Mime: "image/png", Data: "iVBORw0KGgo="},
		{ID: "u6.1", From: "user", Kind: "said", Text: "look"},
		{ID: "u7.0", From: "user", Kind: "note", Text: "[Request interrupted by user for tool use]"},
		{ID: "u8.0", From: "user", Kind: "note", Text: `Background command "Sleep" completed (exit code 0)`},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got\n%+v\nwant\n%+v", got, want)
	}
}

func TestCommandsAndTitles(t *testing.T) {
	p := newParser().(*parser)
	var got []mobileview.Entry
	for _, l := range []string{
		`{"type":"ai-title","aiTitle":"Fix the tests","sessionId":"s"}`,
		`{"type":"system","subtype":"compact_boundary","uuid":"b","content":"Conversation compacted"}`,
		`{"type":"user","uuid":"s1","parentUuid":"b","isCompactSummary":true,"message":{"content":"This session is being continued..."}}`,
		`{"type":"user","uuid":"c0","parentUuid":"s1","isMeta":true,"message":{"content":"<local-command-caveat>The command below was run directly</local-command-caveat>"}}`,
		`{"type":"user","uuid":"c1","parentUuid":"c0","message":{"content":"<command-name>/compact</command-name>\n            <command-message>compact</command-message>\n            <command-args></command-args>"}}`,
		`{"type":"user","uuid":"c2","parentUuid":"c1","message":{"content":"<local-command-stdout>\u001b[2mCompacted (ctrl+o to see full summary)\u001b[22m</local-command-stdout>"}}`,
		`{"type":"user","uuid":"c3","parentUuid":"c2","message":{"content":"<command-name>/add-dir</command-name>\n<command-message>add-dir</command-message>\n<command-args>~/dev/x</command-args>"}}`,
		`{"type":"user","uuid":"c4","parentUuid":"c3","message":{"content":"<local-command-stdout>Added \u001b[1m~/dev/x\u001b[22m as a working directory</local-command-stdout>"}}`,
		`{"type":"user","uuid":"c5","parentUuid":"c4","message":{"content":"<bash-input>ls</bash-input>"}}`,
		`{"type":"user","uuid":"c6","parentUuid":"c5","message":{"content":"<bash-stdout>a\nb</bash-stdout><bash-stderr>ls: c: No such file</bash-stderr>"}}`,
		`{"type":"system","subtype":"local_command","uuid":"c7","parentUuid":"c6","content":"<command-name>/rename</command-name>\n<command-message>rename</command-message>\n<command-args>tests</command-args>"}`,
		`{"type":"system","subtype":"local_command","uuid":"c8","parentUuid":"c7","content":"<local-command-stdout>Session renamed to: tests</local-command-stdout>"}`,
		`{"type":"custom-title","customTitle":"tests","sessionId":"s"}`,
	} {
		got = append(got, p.Line([]byte(l))...)
		if strings.Contains(l, `"ai-title"`) && p.Title() != "Fix the tests" {
			t.Errorf("title %q, want claude's", p.Title())
		}
	}
	got = collapse(got)
	want := []mobileview.Entry{
		{ID: "s1.0", From: "user", Kind: "note", Text: "Conversation compacted", Output: "This session is being continued..."},
		{ID: "c3.0", From: "user", Kind: "step", Name: "/add-dir", Text: "~/dev/x", Status: "done", Output: "Added ~/dev/x as a working directory"},
		{ID: "c5.0", From: "user", Kind: "step", Name: "!", Text: "ls", Status: "done", Output: "a\nb\nls: c: No such file"},
		{ID: "c7.0", From: "user", Kind: "step", Name: "/rename", Text: "tests", Status: "done", Output: "Session renamed to: tests"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got\n%+v\nwant\n%+v", got, want)
	}
	if p.Title() != "tests" {
		t.Errorf("title %q, want the user's", p.Title())
	}
}

// collapse keeps each entry's latest state, where it first came.
func collapse(entries []mobileview.Entry) []mobileview.Entry {
	var out []mobileview.Entry
	at := map[string]int{}
	for _, e := range entries {
		if i, ok := at[e.ID]; ok {
			out[i] = e
		} else {
			at[e.ID] = len(out)
			out = append(out, e)
		}
	}
	return out
}

func TestBigImagesAndOutputs(t *testing.T) {
	p := newParser()
	big := strings.Repeat("A", maxImage+1)
	got := p.Line([]byte(`{"type":"user","uuid":"u","message":{"content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"` + big + `"}}]}}`))
	if len(got) != 1 || got[0].Kind != "note" || got[0].Data != "" {
		t.Errorf("big image: %+v", got)
	}
	p.Line([]byte(`{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Read","input":{"file_path":"/x"}}]}}`))
	long := strings.Repeat("é", maxOutput)
	got = p.Line([]byte(`{"type":"user","uuid":"r","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":[{"type":"text","text":"` + long + `"},{"type":"image"}]}]}}`))
	// é is 2 bytes: half of it fits; the rest, and "\n[image]", are cut.
	if len(got) != 1 || len(got[0].Output) != maxOutput || got[0].OutputCut != maxOutput/2+len("\n[image]") || !strings.HasPrefix(got[0].Output, "é") {
		t.Errorf("long output: %d bytes, %d cut", len(got[0].Output), got[0].OutputCut)
	}
}

func TestContext(t *testing.T) {
	p := newParser().(mobileview.Measure)
	line := func(s string) { p.(mobileview.Parser).Line([]byte(s)) }
	if p.Context() != 0 {
		t.Errorf("before any reply: %d", p.Context())
	}
	line(`{"type":"assistant","uuid":"a","message":{"content":[],"usage":{"input_tokens":2,"cache_creation_input_tokens":736,"cache_read_input_tokens":543056,"output_tokens":319}}}`)
	if p.Context() != 544113 {
		t.Errorf("after a reply: %d", p.Context())
	}
	line(`{"type":"assistant","uuid":"s","isSidechain":true,"message":{"content":[],"usage":{"input_tokens":9000}}}`)
	line(`{"type":"assistant","uuid":"e","message":{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}}`)
	if p.Context() != 544113 {
		t.Errorf("a subagent's reply, or one without usage, changed it: %d", p.Context())
	}
	line(`{"type":"system","subtype":"compact_boundary","uuid":"c","content":"Conversation compacted","compactMetadata":{"trigger":"manual","preTokens":544113,"postTokens":17967}}`)
	if p.Context() != 0 {
		t.Errorf("after compacting, before the next reply: %d", p.Context())
	}
}

// A conversation rewound from three to edited, as claude 2.1.302 wrote it, with
// parallel tool calls on the way: each result goes on from its own call.
var rewound = []string{
	`{"type":"user","uuid":"u1","parentUuid":null,"message":{"content":"say just: one"}}`,
	`{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"content":[{"type":"text","text":"one"}],"usage":{"input_tokens":100}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s1","parentUuid":"a1"}`,
	`{"type":"user","uuid":"u2","parentUuid":"s1","message":{"content":"two, with two tools"}}`,
	`{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"content":[{"type":"tool_use","id":"A","name":"Read","input":{"file_path":"/a"}}]}}`,
	`{"type":"assistant","uuid":"a3","parentUuid":"a2","message":{"content":[{"type":"tool_use","id":"B","name":"Read","input":{"file_path":"/b"}}]}}`,
	`{"type":"user","uuid":"r1","parentUuid":"a2","message":{"content":[{"type":"tool_result","tool_use_id":"A","content":"a"}]}}`,
	`{"type":"user","uuid":"r2","parentUuid":"a3","message":{"content":[{"type":"tool_result","tool_use_id":"B","content":"b"}]}}`,
	`{"type":"assistant","uuid":"a4","parentUuid":"r2","message":{"content":[{"type":"text","text":"two"}],"usage":{"input_tokens":200}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s2","parentUuid":"a4"}`,
	`{"type":"user","uuid":"u3","parentUuid":"s2","message":{"content":"say just: three"}}`,
	`{"type":"assistant","uuid":"a5","parentUuid":"u3","message":{"content":[{"type":"text","text":"three"}],"usage":{"input_tokens":300}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s3","parentUuid":"a5"}`,
	`{"type":"user","uuid":"u4","parentUuid":"s2","message":{"content":"say just: edited"}}`,
	`{"type":"assistant","uuid":"a6","parentUuid":"s3","message":{"content":[{"type":"text","text":"late, on the branch left behind"}]}}`,
	`{"type":"assistant","uuid":"a7","parentUuid":"u4","message":{"content":[{"type":"text","text":"edited"}],"usage":{"input_tokens":250}}}`,
}

func TestRewound(t *testing.T) {
	p := newParser()
	var got []mobileview.Entry
	at := map[string]int{}
	for _, l := range rewound {
		for _, e := range p.Line([]byte(l)) {
			if i, ok := at[e.ID]; ok {
				got[i] = e // a tool's result
			} else {
				at[e.ID] = len(got)
				got = append(got, e)
			}
		}
	}
	got = p.(mobileview.Brancher).Kept(got)
	var ids []string
	for _, e := range got {
		ids = append(ids, e.ID+"="+e.Text)
	}
	if want := "u1.0=say just: one a1.0=one u2.0=two, with two tools a2.0=/a a3.0=/b a4.0=two u4.0=say just: edited a7.0=edited"; strings.Join(ids, " ") != want {
		t.Errorf("got  %s\nwant %s", strings.Join(ids, " "), want)
	}
	if got[3].Status != mobileview.Done || got[4].Status != mobileview.Done {
		t.Errorf("parallel results: %+v %+v", got[3], got[4])
	}
	if b := p.(mobileview.Brancher).Branches(); b != 1 {
		t.Errorf("branches: %d", b)
	}
	if c := p.(mobileview.Measure).Context(); c != 250 {
		t.Errorf("context: %d", c)
	}

	// Until the reply on the new branch, how full the context is isn't known.
	p = newParser()
	for _, l := range rewound[:14] {
		p.Line([]byte(l))
	}
	if c := p.(mobileview.Measure).Context(); c != 0 {
		t.Errorf("context after going back: %d", c)
	}
}

func TestDiffs(t *testing.T) {
	p := newParser()
	lines := []string{
		`{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"/x/main.go","old_string":"old","new_string":"new"}}]}}`,
		`{"type":"user","uuid":"u1","toolUseResult":{"filePath":"/x/main.go","oldString":"old","newString":"new","originalFile":"…","structuredPatch":[{"oldStart":3,"oldLines":3,"newStart":3,"newLines":3,"lines":[" a","-old","+new"]},{"oldStart":40,"oldLines":1,"newStart":40,"newLines":2,"lines":[" b","+c"]}]},"message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"The file /x/main.go has been updated successfully."}]}}`,
		`{"type":"assistant","uuid":"a2","message":{"content":[{"type":"tool_use","id":"t2","name":"Write","input":{"file_path":"/x/new.txt","content":"one\ntwo\n"}}]}}`,
		`{"type":"user","uuid":"u2","toolUseResult":{"type":"create","filePath":"/x/new.txt","content":"one\ntwo\n","structuredPatch":[],"originalFile":null},"message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"File created successfully at: /x/new.txt"}]}}`,
		`{"type":"assistant","uuid":"a3","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/x/main.go"}}]}}`,
		`{"type":"user","uuid":"u3","toolUseResult":"Error: String to replace not found in file.","message":{"content":[{"type":"tool_result","tool_use_id":"t3","is_error":true,"content":"String to replace not found in file."}]}}`,
		`{"type":"assistant","uuid":"a4","message":{"content":[{"type":"tool_use","id":"t4","name":"Bash","input":{"command":"ls"}}]}}`,
		`{"type":"user","uuid":"u4","toolUseResult":{"stdout":"main.go","stderr":"","interrupted":false},"message":{"content":[{"type":"tool_result","tool_use_id":"t4","content":"main.go"}]}}`,
	}
	var diffs []string
	for _, l := range lines {
		for _, e := range p.Line([]byte(l)) {
			if e.Status != mobileview.Running {
				diffs = append(diffs, e.Diff)
			}
		}
	}
	want := []string{
		"@@ -3,3 +3,3 @@\n a\n-old\n+new\n@@ -40,1 +40,2 @@\n b\n+c",
		"@@ -0,0 +1,2 @@\n+one\n+two",
		"",
		"",
	}
	if !reflect.DeepEqual(diffs, want) {
		t.Errorf("got %q\nwant %q", diffs, want)
	}

	// A big new file is cut at a line, saying how much is left.
	big := strings.Repeat("0123456789\n", 1000)
	got, cut := diff([]byte(`{"type":"create","content":` + strconv.Quote(big) + `,"structuredPatch":[]}`))
	if len(got) > maxDiff || cut != 335 || !strings.HasPrefix(got, "@@ -0,0 +1,1000 @@\n+0123456789\n") || !strings.HasSuffix(got, "\n+0123456789") {
		t.Errorf("cut to %d characters and %d lines, ending %q", len(got), cut, got[len(got)-40:])
	}
}

func TestPlans(t *testing.T) {
	p := newParser()
	got := p.Line([]byte(`{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Write","input":{"file_path":"/Users/me/.claude/plans/fix-it.md","content":"# Plan\n\n1. fix it\n"}}]}}`))
	if len(got) != 2 || got[0].Kind != "step" || got[1].Kind != "said" || got[1].Text != "# Plan\n\n1. fix it" || got[1].ID != "a.0.plan" {
		t.Errorf("plan: %+v", got)
	}
	got = p.Line([]byte(`{"type":"assistant","uuid":"b","message":{"content":[{"type":"tool_use","id":"u","name":"Write","input":{"file_path":"/Users/me/dev/plans/notes.md","content":"not a plan"}}]}}`))
	if len(got) != 1 {
		t.Errorf("any other write: %+v", got)
	}
}

func TestRunning(t *testing.T) {
	dir := t.TempDir()
	write := func(name, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(filepath.Join(dir, name)), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	me := os.Getpid()
	parent := os.Getppid()
	write("sessions/"+strconv.Itoa(me)+".json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"aaaa-1","cwd":"/Users/me/dev/x","status":"waiting","statusUpdatedAt":1792209270006}`)
	write("sessions/"+strconv.Itoa(parent)+".json", `{"pid":`+strconv.Itoa(parent)+`,"sessionId":"bbbb-2","cwd":"/Users/me/my.dir","status":"shell","statusUpdatedAt":1792209271000}`)
	write("sessions/1.key", `{}`)
	write("sessions/999999.json", `{"pid":999999,"sessionId":"cccc-3","cwd":"/"}`) // no such process
	write("sessions/bad.json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"../../etc"}`)
	write("projects/-Users-me-dev-x-moved/aaaa-1.jsonl", "")

	got := New(dir).running()
	want := map[int]session{
		me:     {path: filepath.Join(dir, "projects/-Users-me-dev-x-moved/aaaa-1.jsonl"), activity: mobileview.Waiting, since: 1792209270006}, // where it is
		parent: {path: filepath.Join(dir, "projects/-Users-me-my-dir/bbbb-2.jsonl"), activity: mobileview.Idle, since: 1792209271000},         // where it will be; done, with a background task left running
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v\nwant %v", got, want)
	}
}
