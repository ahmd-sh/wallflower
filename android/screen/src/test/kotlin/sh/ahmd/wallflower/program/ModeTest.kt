package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.screen.captured

/** Claude Code's permission modes, from captures at 54 columns. */
class ModeTest {
    @Test
    fun itsFooterSaysWhichModeItsIn() {
        assertEquals(Mode("manual", "⏸"), Claude.mode(captured("claude-mode-manual.ansi", 54)))
        assertEquals(Mode("accept edits", "⏵⏵"), Claude.mode(captured("claude-mode-accept-edits.ansi", 54)))
        assertEquals(Mode("plan", "⏸"), Claude.mode(captured("claude-mode-plan.ansi", 54)))
        assertEquals(Mode("auto", "⏵⏵"), Claude.mode(captured("claude-mode-auto.ansi", 54)))
        assertEquals(Mode("auto", "⏵⏵"), Claude.mode(captured("claude-working.ansi", 90)))
    }

    @Test
    fun outOfVimsInsertItsStillThere() {
        assertEquals(Mode("accept edits", "⏵⏵"), Claude.mode(captured("claude-mode-normal.ansi", 54)))
    }

    @Test
    fun withoutItsBoxItCantBeSeen() {
        assertNull(Claude.mode(captured("claude-bash-permission.ansi", 54)))
        assertNull(Claude.mode(captured("claude-transcript.ansi", 54)))
        assertNull(Claude.mode(captured("claude-btw.ansi", 54)))
    }

    @Test
    fun theCycleIsWhatsBeenSeenToFollow() {
        val manual = Mode("manual", "⏸")
        val edits = Mode("accept edits", "⏵⏵")
        val plan = Mode("plan", "⏸")
        val auto = Mode("auto", "⏵⏵")
        val cycle = Cycle(Claude.modes)
        // Nothing seen: the current, then what it may have.
        assertEquals(listOf(auto, Mode("manual", ""), Mode("accept edits", ""), Mode("plan", "")), cycle.from(auto))
        cycle.stepped(auto, manual)
        cycle.stepped(manual, edits)
        assertEquals(listOf(auto, manual, edits, Mode("plan", "")), cycle.from(auto))
        assertEquals(listOf(edits, auto, manual, Mode("plan", "")), cycle.from(edits)) // nothing seen after it yet: the rest as they came
        cycle.stepped(edits, plan)
        cycle.stepped(plan, auto)
        assertEquals(listOf(plan, auto, manual, edits), cycle.from(plan))
        // One a round didn't come to goes, till it's seen.
        cycle.missed("plan")
        assertEquals(listOf(auto, manual, edits), cycle.from(auto))
        cycle.saw(plan)
        assertEquals(listOf(auto, manual, edits, plan), cycle.from(auto))
    }

    @Test
    fun aModeNotKnownIsOneSeen() {
        val cycle = Cycle(Claude.modes)
        val bypass = Mode("bypass permissions", "⏵⏵")
        cycle.saw(bypass)
        assertEquals(listOf("auto", "bypass permissions", "manual", "accept edits", "plan"), cycle.from(Mode("auto", "⏵⏵")).map { it.name })
    }
}
