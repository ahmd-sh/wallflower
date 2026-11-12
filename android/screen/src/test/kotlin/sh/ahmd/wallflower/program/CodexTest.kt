package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.screen.captured

/** Codex CLI 0.167's screens, at 64 columns. */
class CodexTest {
    private fun screen(name: String) = captured("codex-$name.ansi", 64)

    @Test
    fun anEmptyComposerHoldsOnlyItsPlaceholder() {
        assertEquals("", Codex.line(screen("idle"))!!.text)
        assertNull(Codex.prompt(screen("idle")))
        assertNull(Codex.status(screen("idle")))
        assertNull(Codex.asked(screen("idle")))
    }

    @Test
    fun aLongLineIsWrappedAtWordsAndALongWordWhereTheRowEnds() {
        val line = Codex.line(screen("typed"))!!
        assertEquals(
            "the quick brown fox jumps over the lazy dog and keeps running far beyond the edge of this narrow pane " +
                "averyveryverylongwordthatcannotfitonarowatallnomatterhowhardittries end\nand a second line",
            line.text,
        )
        assertEquals(5, line.rows)
    }

    @Test
    fun theCommandsPopupIsAboveTheComposerNotInIt() {
        assertEquals("/", Codex.line(screen("slash"))!!.text)
        assertNull(Codex.prompt(screen("slash")))
    }

    @Test
    fun workingItSaysSoAboveTheComposer() {
        assertEquals("Working", Codex.status(screen("working")))
        assertEquals("", Codex.line(screen("working"))!!.text)
        assertNull(Codex.asked(screen("working")))
    }

    @Test
    fun itsQuestionsWaitBelowItsWorkingToBeBroughtUp() {
        val s = screen("working-asks")
        assertEquals("Working", Codex.status(s))
        assertEquals(Asked("1 question", listOf("S-Left")), Codex.asked(s))
        assertNull(Codex.prompt(s))
        assertEquals("a message typed while it works", Codex.line(screen("working-typed"))!!.text)
    }

    @Test
    fun broughtUpAQuestionTakesTheComposersPlace() {
        val s = screen("question")
        assertNull(Codex.line(s))
        assertNull(Codex.asked(s))
        val p = Codex.prompt(s)!!
        assertEquals(listOf("Red", "Blue", "Green", "Other"), p.options.map { it.label })
        assertEquals(listOf("Which colour do you pick?"), p.lines.map { it.text }.filter { it.isNotBlank() }) // not the conversation's rows above
        assertEquals(listOf("2"), p.keys(p.options[1])) // a number answers it at once
        assertEquals("Working", Codex.status(s))
    }

    @Test
    fun anApprovalIsAMenu() {
        val s = screen("approval")
        assertNull(Codex.line(s))
        val p = Codex.prompt(s)!!
        assertEquals(3, p.options.size)
        assertEquals("Yes, proceed (y)", p.options[0].label)
        assertEquals("No, and tell Codex what to do differently (esc)", p.options[2].label)
        assertEquals(true, p.options[0].current)
        assertEquals("Would you like to run the following command?", p.lines.first().text.trim())
    }

    @Test
    fun aFolderToTrustIsPickedByTheCursorAsANumberOnlyMovesToTrust() {
        val p = Codex.prompt(screen("trust"))!!
        assertEquals(listOf("Trust and continue", "Back to Agent Command Center"), p.options.map { it.label })
        assertEquals(listOf("Enter"), p.keys(p.options[0]))
        assertEquals(listOf("Down", "Enter"), p.keys(p.options[1]))
        assertEquals(listOf("1"), Codex.prompt(screen("approval"))!!.let { it.keys(it.options[0]) }) // elsewhere a number answers
    }

    @Test
    fun itsTitleIsTheConversationsNameThenTheProjects() {
        assertEquals("Approve touch command", Codex.label("Approve touch command | wallflower"))
        assertEquals("Approve touch command", Codex.label("⠴ Approve touch command | wallflower"))
        assertEquals("Approve touch command", Codex.label("[ ! ] Action Required | Approve touch command | wallflower"))
        assertNull(Codex.label("wallflower"))
        assertNull(Codex.label("[ . ] Action Required | wallflower"))
    }

    @Test
    fun itsModeIsPlanWhenItsFooterSaysSo() {
        assertEquals(Mode("plan", "", Hue.Aqua), Codex.mode(captured("codex-plan.ansi", 80)))
        assertEquals(Mode("default", ""), Codex.mode(screen("idle")))
        assertNull(Codex.mode(screen("question")))
    }

    @Test
    fun itHasItsOwnBarAndStop() {
        assertEquals(Codex, Programs.of("codex"))
        assertEquals(listOf("Escape"), Codex.keyBar.stop)
    }

    /** The agent command center, at 42 columns. */
    private fun agents(name: String = "") = captured("codex-agents$name.ansi", 42)

    @Test
    fun itsAgentsAreItsOwnListOfTasksInGroups() {
        val s = agents()
        assertNull(Codex.line(s))
        assertNull(Codex.prompt(s))
        val t = Codex.aside(s) as Aside.Tasks
        // Those that don't fit are there too, how many they hold unknown.
        assertEquals(listOf("All" to 25, "Needs you" to 1, "Working" to 0, "Ready" to null, "Inactive" to null), t.filters.map { it.name to it.count })
        assertEquals("All", t.filters.single { it.current }.name)
        assertEquals(listOf("Pinned" to "1", "/Users/sam/dev/wallflower" to "24"), t.groups.map { it.name to it.count })
        assertEquals(listOf("second task"), t.groups[0].tasks.map { it.name })
        val tasks = t.tasks
        assertEquals("Untitled task", tasks[1].name)
        assertEquals("Error: task number 22 with a fai…", tasks[2].name)
        assertEquals("task number 21 with a fairly lon…", tasks.single { it.current }.name)
        assertEquals(Aside.Tasks.Mark.Idle, tasks[1].mark)
        assertEquals(Aside.Tasks.Mark.Lit, tasks[2].mark) // ●, with something needing the user: it, or one out of sight
        assertEquals(false, t.above)
        assertEquals(true, t.below)
        assertNull(t.says)
        assertEquals(listOf("Escape"), t.leave)
    }

    @Test
    fun aTaskIsOpenedByMovingTheCursorToIt() {
        val t = Codex.aside(agents()) as Aside.Tasks
        assertEquals(listOf("Up", "Up", "Up", "Enter"), t.opening(0)) // past the group's heading and the row between
        assertEquals(listOf("Down", "Down", "Enter"), t.opening(5))
        assertEquals(listOf("Enter"), t.opening(t.tasks.indexOfFirst { it.current }))
        assertEquals(listOf("Tab", "Tab"), t.showing(2))
        assertEquals(listOf("Tab", "Tab", "Tab", "Tab"), t.showing(4)) // out of sight
        assertEquals(emptyList<String>(), t.showing(0))
    }

    @Test
    fun itsMarksSayWhatEachTaskIsDoingAsFarAsTheyCan() {
        val marks = (Codex.aside(agents("-marks")) as Aside.Tasks).tasks.associate { it.name to it.mark }
        assertEquals(Aside.Tasks.Mark.NeedsYou, marks["build fix"])
        assertEquals(Aside.Tasks.Mark.Lit, marks["docs pass"])
        val working = (Codex.aside(agents("-working")) as Aside.Tasks).tasks.associate { it.name to it.mark }
        assertEquals(Aside.Tasks.Mark.Lit, working["fix the build"]) // working, or needing the user: both are
        val needs = (Codex.aside(agents("-needs-you")) as Aside.Tasks).tasks.single()
        assertEquals(Aside.Tasks.Mark.NeedsYou, needs.mark) // what the filter holds
        assertEquals("/Users/…/dev/wallflower" to "1 of 24", (Codex.aside(agents("-needs-you")) as Aside.Tasks).groups.single().let { it.name to it.count })
    }

    @Test
    fun filteredItCanSayThereAreNoneOrShowOtherFilters() {
        val none = Codex.aside(agents("-none")) as Aside.Tasks
        assertEquals("No matching tasks", none.says)
        assertEquals(emptyList<Aside.Tasks.Group>(), none.groups)
        assertEquals("Working", none.filters.single { it.current }.name)
        val ready = Codex.aside(agents("-filtered")) as Aside.Tasks
        assertEquals(listOf("All" to null, "Needs you" to null, "Working" to 0, "Ready" to 1, "Inactive" to 23), ready.filters.map { it.name to it.count })
        assertEquals("Ready", ready.filters.single { it.current }.name)
        assertEquals(listOf("BTab"), ready.showing(2))
        assertEquals(listOf("BTab", "BTab", "BTab"), ready.showing(0)) // out of sight
        assertEquals(listOf("Tab"), ready.showing(4))
    }

    @Test
    fun scrolledThereIsMoreAboveAndItsGroupsHeadingIsOutOfSight() {
        val t = Codex.aside(agents("-scrolled")) as Aside.Tasks
        assertEquals(true, t.above)
        assertEquals(false, t.below)
        assertEquals("", t.groups.single().name)
        assertEquals("task number 19 with a fairly lon…", t.tasks.first().name)
        assertEquals("say hi", t.tasks.last().name)
        assertEquals("say hi", t.tasks.single { it.current }.name)
    }

    @Test
    fun itsKeysAreAViewOfItsOwn() {
        assertEquals(Aside.View("agents' keys", listOf("Escape")), Codex.aside(agents("-help")))
        assertNull(Codex.aside(screen("idle")))
        assertNull(Codex.aside(screen("trust")))
    }
}
