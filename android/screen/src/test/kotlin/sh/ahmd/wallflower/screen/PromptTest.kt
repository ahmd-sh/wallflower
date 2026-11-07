package sh.ahmd.wallflower.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.screen.Prompt.Line
import sh.ahmd.wallflower.screen.Prompt.Option
import sh.ahmd.wallflower.screen.Prompt.Tab
import sh.ahmd.wallflower.screen.Screen.Companion.HIGHLIGHT
import sh.ahmd.wallflower.screen.Screen.Companion.MUTED

/** Screens as Claude Code draws its prompts, from captures at 56 columns. */
class PromptTest {
    /** Rows of text; in [marks], m is grey and h highlighted, one character per cell. */
    private fun screen(vararg rows: Pair<String, String>): Screen {
        val cols = 56
        val text = Array(cols * rows.size) { "" }
        val attrs = IntArray(cols * rows.size)
        rows.forEachIndexed { y, (row, marks) ->
            row.forEachIndexed { x, c -> if (x < cols) text[y * cols + x] = if (c == ' ') "" else c.toString() }
            marks.forEachIndexed { x, m ->
                attrs[y * cols + x] = when (m) {
                    'm' -> MUTED
                    'h' -> HIGHLIGHT
                    else -> 0
                }
            }
        }
        return Screen(cols, rows.size, 0, 0, text, attrs, BooleanArray(rows.size))
    }

    private fun plain(row: String) = row to ""

    private val rule = "─".repeat(56)
    private val dashes = "╌".repeat(56)

    @Test
    fun aPermissionPrompt() {
        val menu = Prompt.read(
            screen(
                plain("⏺ Bash(touch /tmp/wf-probe-1)"),
                plain("  ⎿  Waiting…"),
                plain(""),
                plain(rule),
                plain(" Bash command"),
                plain(" Create empty file /tmp/wf-probe-1"),
                plain(dashes),
                plain(" touch /tmp/wf-probe-1"),
                plain(dashes),
                plain(" Do you want to proceed?"),
                plain(" ❯ 1. Yes"),
                plain("   2. Yes, and always allow access to /tmp from this"),
                plain("      project"),
                plain("   3. No"),
                plain(""),
                plain(" Esc to cancel · Tab to amend"),
            ),
        )!!
        assertEquals(
            listOf(Line("Bash command", false), Line("Create empty file /tmp/wf-probe-1", false), Line("touch /tmp/wf-probe-1", true), Line("Do you want to proceed?", false)),
            menu.lines,
        )
        assertEquals(
            listOf(
                Option(1, "Yes", "", null, current = true, muted = false),
                Option(2, "Yes, and always allow access to /tmp from this project", "", null, current = false, muted = false),
                Option(3, "No", "", null, current = false, muted = false),
            ),
            menu.options,
        )
        assertEquals(listOf("3"), menu.keys(menu.options[2]))
    }

    @Test
    fun aQuestionWithDescriptionsAndAnOptionBelowARule() {
        val menu = Prompt.read(
            screen(
                plain(rule),
                plain(" ☐ Color"),
                plain("Which color do you prefer?"),
                "❯ 1. Red" to "  mm",
                "     A bold, warm color that stands out." to "     " + "m".repeat(35),
                "  2. Green" to "  mm",
                "     A calm, natural color." to "     " + "m".repeat(22),
                "  3. Type something." to "  " + "m".repeat(18),
                plain(rule),
                "  4. Chat about this" to "  mm",
                plain("Enter to select · ↑/↓ to navigate · Esc to cancel"),
            ),
        )!!
        assertEquals(listOf(Tab("☐ Color", false)), menu.tabs)
        assertEquals(listOf(Line("Which color do you prefer?", false)), menu.lines)
        assertEquals(
            listOf(
                Option(1, "Red", "A bold, warm color that stands out.", null, current = true, muted = false),
                Option(2, "Green", "A calm, natural color.", null, current = false, muted = false),
                Option(3, "Type something.", "", null, current = false, muted = true),
                Option(4, "Chat about this", "", null, current = false, muted = false),
            ),
            menu.options,
        )
    }

    @Test
    fun aMultipleChoiceAmongQuestions() {
        val menu = Prompt.read(
            screen(
                plain(rule),
                "←  ☐ Fruit  ☐ Size  ✔ Submit  →" to "  hhhhhhhhh",
                plain("Which fruits do you like?"),
                "❯ 1. [ ] Apple" to "  mm",
                "         Crisp and sweet." to "         " + "m".repeat(16),
                "  2. [✔] Banana" to "  mm",
                "  3. [ ] Type something" to "  " + "m".repeat(21),
                plain("     Next"),
                plain(rule),
                "  4. Chat about this" to "  mm",
                plain("Enter to select · Tab/Arrow keys to navigate · Esc to"),
                plain("cancel"),
            ),
        )!!
        assertEquals(listOf(Tab("☐ Fruit", true), Tab("☐ Size", false), Tab("✔ Submit", false)), menu.tabs)
        assertEquals(
            listOf(
                Option(1, "Apple", "Crisp and sweet.", false, current = true, muted = false),
                Option(2, "Banana", "", true, current = false, muted = false),
                Option(3, "Type something", "", false, current = false, muted = true),
                Option(null, "Next", "", null, current = false, muted = false),
                Option(4, "Chat about this", "", null, current = false, muted = false),
            ),
            menu.options,
        )
        assertEquals(listOf("2"), menu.keys(menu.options[1]))
        assertEquals(listOf("Down", "Down", "Down", "Enter"), menu.keys(menu.options[3]))
        assertEquals(listOf("Right", "Right"), menu.keys(menu.tabs[2]))
    }

    @Test
    fun aPlanApprovalBelowThePlansOwnSteps() {
        val menu = Prompt.read(
            screen(
                plain(" Steps"),
                plain(" 1. Use Edit on /tmp/wf-probe-2.txt to replace the"),
                plain("    first line."),
                plain(" 2. Use Read on /tmp/wf-probe-2.txt."),
                plain(dashes),
                plain(rule),
                plain(" Claude has written up a plan and is ready to execute."),
                plain(" Would you like to proceed?"),
                plain(""),
                plain(" ❯ 1. Yes, and use auto mode"),
                plain("   2. Yes, manually approve edits"),
                "   3. Tell Claude what to change" to "   " + "m".repeat(29),
                "      shift+tab to approve with this feedback" to "      " + "m".repeat(39),
                plain(" ctrl+g to edit in Nvim · ~/.claude/plans/x.md"),
            ),
        )!!
        assertEquals(
            listOf(Line("Claude has written up a plan and is ready to execute. Would you like to proceed?", false)),
            menu.lines,
        )
        assertEquals(listOf("Yes, and use auto mode", "Yes, manually approve edits", "Tell Claude what to change"), menu.options.map { it.label })
        assertEquals("shift+tab to approve with this feedback", menu.options[2].detail)
    }

    @Test
    fun aRealPlanApproval() {
        val menu = Prompt.read(captured("claude-plan-approval.ansi", 54))!!
        assertEquals(
            listOf(Line("Claude has written up a plan and is ready to execute. Would you like to proceed?", false)),
            menu.lines,
        )
        assertEquals(listOf("Yes, and use auto mode", "Yes, manually approve edits", "Tell Claude what to change"), menu.options.map { it.label })
        assertEquals("shift+tab to approve with this feedback", menu.options[2].detail)
    }

    @Test
    fun aRealPermissionWithADescriptionBesideALabel() {
        val menu = Prompt.read(captured("claude-bash-permission.ansi", 54))!!
        assertEquals(Line("touch /tmp/wf-probe-1 && ls -l /tmp/wf-probe-1", true), menu.lines.single { it.code })
        assertEquals(
            listOf(
                Option(1, "Yes", "", null, current = true, muted = false),
                Option(2, "Yes, and allow access to /tmp and touch /tmp/wf-probe-1 commands", "", null, current = false, muted = false),
                Option(3, "Yes, and switch to auto mode", "auto mode handles these prompts for you", null, current = false, muted = false),
                Option(4, "No", "", null, current = false, muted = false),
            ),
            menu.options,
        )
    }

    @Test
    fun aRealRewindListPickedWithTheCursor() {
        val menu = Prompt.read(captured("claude-rewind-list.ansi", 54))!!
        assertEquals(listOf(Line("Rewind", false), Line("Restore the code and/or conversation to the point before…", false)), menu.lines)
        assertEquals(
            listOf(
                Option(null, "↑ 1 more", "", null, current = false, muted = true, scroll = -1),
                Option(null, "say just: two", "No code changes", null, current = false, muted = false),
                Option(null, "Use the Write tool to create /private/tmp/c…", "rw.txt +1", null, current = true, muted = false),
                Option(null, "↓ 1 more", "", null, current = false, muted = true, scroll = 1),
            ),
            menu.options.map { it.copy(muted = it.scroll != 0) },
        )
        assertEquals(listOf("Up", "Enter"), menu.keys(menu.options[1]))
        assertEquals(listOf("Up", "Up"), menu.keys(menu.options[0]))
        assertEquals(listOf("Down"), menu.keys(menu.options[3]))
    }

    @Test
    fun aRealRewindConfirmationQuotesItsMessage() {
        val menu = Prompt.read(captured("claude-rewind-confirm.ansi", 54))!!
        assertEquals(
            listOf("Restore code and conversation", "Restore conversation", "Restore code", "Summarize from here", "Summarize up to here"),
            menu.options.map { it.label },
        )
        assertEquals(listOf(true, false, false, false, false), menu.options.map { it.current })
        assertEquals(listOf("5"), menu.keys(menu.options[4]))
        assertEquals(
            "UsetheWritetooltocreate/private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txtcontaininghi,thensayjust:done",
            menu.quote.replace(" ", ""),
        )
    }

    @Test
    fun aRewindListInFullscreenModeWithItsFootPastTheBottom() {
        val menu = Prompt.read(captured("claude-rewind-list-fullscreen.ansi", 52))!!
        assertEquals(listOf(Line("Rewind", false), Line("Restore the code and/or conversation to the point before…", false)), menu.lines)
        assertEquals(listOf("↑ 1 more", "say just: two", "(current)"), menu.options.map { it.label })
        assertEquals(listOf(-1, 0, 0), menu.options.map { it.scroll })
        assertEquals("No code changes", menu.options[1].detail)
        assertEquals(listOf("Up", "Enter"), menu.keys(menu.options[1]))
    }

    @Test
    fun aRewindConfirmationInFullscreenModeIsAboutWhatsBelowItsRule() {
        val menu = Prompt.read(captured("claude-rewind-confirm-fullscreen.ansi", 52))!!
        assertEquals("Rewind", menu.lines.first().text)
        assertEquals(listOf("Restore conversation", "Summarize from here", "Summarize up to here"), menu.options.map { it.label })
        assertEquals("say just: two", menu.quote)
    }

    @Test
    fun aRealQuestionWithDescriptions() {
        val menu = Prompt.read(captured("claude-question.ansi", 52))!!
        assertEquals(listOf(Tab("☒ Fullscreen", false), Tab("☐ zsh hook", true), Tab("✔ Submit", false)), menu.tabs)
        assertEquals(listOf(Line("Where should the zsh side (the OSC 133 marks plus the command record) live?", false)), menu.lines)
        assertEquals(
            listOf(
                Option(
                    1, "wallflower repo (Recommended)",
                    "~/dev/wallflower/shell/wallflower.zsh holds the marks and the record. .zshrc keeps a single source line, and its OSC 133 lines move out. wallflower's needs stay in wallflower.",
                    null, current = true, muted = false,
                ),
                Option(2, "Inline in .zshrc", "Add the record's few lines next to the OSC 133 lines already in your .zshrc.", null, current = false, muted = false),
                Option(3, "Type something.", "", null, current = false, muted = true),
                Option(4, "Chat about this", "", null, current = false, muted = false),
            ),
            menu.options,
        )
        assertEquals(listOf("2"), menu.keys(menu.options[1]))
    }

    @Test
    fun aRealQuestionPreviewingItsOptionsBesideThem() {
        val menu = Prompt.read(captured("claude-question-previews.ansi", 52))!!
        assertEquals(listOf(Tab("☐ Fullscreen", true), Tab("☐ zsh hook", false), Tab("✔ Submit", false)), menu.tabs)
        assertEquals(
            listOf(Line("When a command goes fullscreen (nvim, htop, lazygit, git log's pager), what should a shell pane's read view do?", false)),
            menu.lines,
        )
        assertEquals(
            listOf(
                Option(1, "Hand over to terminal (Recommended)", "", null, current = true, muted = false),
                Option(2, "Live preview card", "", null, current = false, muted = false),
                Option(3, "Plain card", "", null, current = false, muted = false),
            ),
            menu.options,
        )
        // A number only moves the cursor there.
        assertEquals(listOf("3", "Enter"), menu.keys(menu.options[2]))
    }

    @Test
    fun aRealQuestionPreviewInAShortPane() {
        val menu = Prompt.read(captured("claude-question-previews-short.ansi", 52))!!
        assertEquals(listOf(Line("lazygit, git log's pager), what should a shell pane's read view do?", false)), menu.lines)
        assertEquals(listOf("Hand over to terminal (Recommended)", "Live preview card", "Plain card"), menu.options.map { it.label })
        assertEquals(listOf("2", "Enter"), menu.keys(menu.options[1]))
    }

    @Test
    fun aRealReviewOfAnswersQuotesTheQuestionItsOn() {
        val menu = Prompt.read(captured("claude-question-review.ansi", 52))!!
        assertEquals(
            listOf(
                "Review your answers",
                "● When a command goes fullscreen (nvim, htop, lazygit, git log's pager), what should a shell pane's read view do?",
                "→ Live preview card",
                "● Where should the zsh side (the OSC 133 marks plus the command record) live?",
                "→ Inline in .zshrc",
                "Ready to submit your answers?",
            ),
            menu.lines.map { it.text },
        )
        assertEquals(listOf("Submit answers", "Cancel"), menu.options.map { it.label })
        assertEquals(listOf("2"), menu.keys(menu.options[1]))
    }

    @Test
    fun aMultipleChoicesNextAndSubmitStayWithTheCursorOnThem() {
        val labels = mapOf(
            "claude-question-multi-next.ansi" to listOf("Red", "Green", "Blue", "Type something", "Next", "Chat about this"),
            "claude-question-multi-submit.ansi" to listOf("Apple", "Pear", "Plum", "Type something", "Submit", "Chat about this"),
        )
        for ((name, options) in labels) {
            val menu = Prompt.read(captured(name, 70))!!
            assertEquals(options, menu.options.map { it.label })
            val item = menu.options[4]
            assertEquals(null, item.number)
            assertEquals(true, item.current)
            assertEquals(listOf("Enter"), menu.keys(item))
        }
    }

    @Test
    fun aPromptsSuggestionsAreNotAList() {
        val s = screen(
            plain(rule),
            plain("❯ /rew"),
            plain(rule),
            plain("  ❯ /rewind   Restore the code and/or"),
            plain("              conversation to a previous po…"),
            plain("    /review   Review a pull request"),
        )
        assertNull(Prompt.read(s))
    }

    @Test
    fun aNumberedListIsNotAMenu() {
        val s = screen(
            plain("⏺ Two steps:"),
            plain("  1. Edit the file"),
            plain("  2. Read it back"),
            plain(rule),
            plain("❯ "),
            plain(rule),
        )
        assertNull(Prompt.read(s))
    }

    @Test
    fun aRewindConfirmationWithNoCodeChangesQuotesItsMessage() {
        val menu = Prompt.read(captured("claude-rewind-confirm-nocode.ansi", 54))!!
        assertEquals(listOf("Restore conversation", "Summarize from here", "Summarize up to here", "Never mind"), menu.options.map { it.label })
        assertEquals("reply with just the word guava", menu.quote)
    }

    @Test
    fun aQuestionsWrittenAnswerUnderTheCursorIsStillTheLesserOption() {
        val menu = Prompt.read(captured("claude-question-write.ansi", 54))!!
        val write = menu.options.first { it.label == "Type something." }
        assertEquals(true, write.current)
        assertEquals(true, write.muted)
        assertEquals(false, menu.options.first { it.label == "Summer" }.muted)
    }
}
