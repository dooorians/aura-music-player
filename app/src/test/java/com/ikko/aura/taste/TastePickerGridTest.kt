package com.ikko.aura.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TastePickerGridTest {
    private fun a(vararg n: String) = n.map(::PickerArtist)

    @Test fun `new artists go right after the one they were found for`() {
        val out = TastePickerGrid.insertAfter(a("A", "B", "C"), PickerArtist("A").key, a("X", "Y"))
        assertEquals(listOf("A", "X", "Y", "B", "C"), out.map { it.name })
    }

    @Test fun `an artist already there is not added again, whatever its spelling or case`() {
        val out = TastePickerGrid.insertAfter(a("Angèle", "B"), PickerArtist("B").key, a("angele", "ANGÈLE", "Z", "Z"))
        assertEquals(listOf("Angèle", "B", "Z"), out.map { it.name })
    }

    @Test fun `successive expansions never produce a duplicate`() {
        var grid = TastePickerGrid.starters(listOf("A", "B", "a"))
        assertEquals(2, grid.size)
        grid = TastePickerGrid.insertAfter(grid, PickerArtist("A").key, a("C", "D"))
        grid = TastePickerGrid.insertAfter(grid, PickerArtist("B").key, a("D", "E", "A"))
        assertEquals(grid.size, grid.map { it.key }.distinct().size)
        assertEquals(listOf("A", "C", "D", "B", "E"), grid.map { it.name })
    }

    @Test fun `an unknown anchor sends them to the end`() {
        assertEquals(listOf("A", "X"), TastePickerGrid.insertAfter(a("A"), "nobody", a("X")).map { it.name })
    }

    @Test fun `a tap picks and a second tap takes it back`() {
        val picked = TastePickerGrid.toggle(emptySet(), "a")
        assertTrue("a" in picked)
        assertFalse("a" in TastePickerGrid.toggle(picked, "a"))
    }

    @Test fun `five picks unlock the confirmation, and the counter says how many are left`() {
        var s = TastePickerUiState(artists = a("A", "B", "C", "D", "E", "F"), loading = false)
        assertEquals(5, s.remaining); assertFalse(s.canConfirm)
        s = s.copy(selected = setOf("a", "b", "c", "d"))
        assertEquals(1, s.remaining); assertFalse(s.canConfirm)
        s = s.copy(selected = s.selected + "e")
        assertEquals(0, s.remaining); assertTrue(s.canConfirm)
        assertFalse(s.copy(saving = true).canConfirm)
    }

    @Test fun `an artist found by search joins the grid at the top once, and the search shows its own answer`() {
        val grid = TastePickerGrid.withArtist(a("A", "B"), PickerArtist("Z"))
        assertEquals(listOf("Z", "A", "B"), grid.map { it.name })
        assertEquals(grid, TastePickerGrid.withArtist(grid, PickerArtist("z")))
        val s = TastePickerUiState(artists = a("A", "B"), query = "ang", results = a("Angèle"), loading = false)
        assertEquals(listOf("Angèle"), s.shown.map { it.name })
        assertEquals(listOf("A", "B"), s.copy(query = "").shown.map { it.name })
    }

    @Test fun `once a base exists a test can be confirmed with a single artist`() {
        val first = TastePickerUiState(artists = a("A"), loading = false)
        assertEquals(5, first.minSelection)
        val later = first.copy(minSelection = TastePickerUiState.MIN_REFINE, selected = setOf("a"))
        assertEquals(0, later.remaining); assertTrue(later.canConfirm)
        assertFalse(later.copy(selected = emptySet()).canConfirm)
    }

    @Test fun `a search never shows an artist already chosen, and shows its neighbours instead`() {
        val picked = setOf(PickerArtist("Angèle").key)
        val out = TastePickerGrid.searchResults(
            found = a("Angèle", "Angèle Dubeau"), picked = picked, blocked = setOf(PickerArtist("Bloqué").key),
            closeToPicked = a("Clara Luciani", "Bloqué", "Angèle", "Clara Luciani"),
        )
        assertEquals(listOf("Clara Luciani", "Angèle Dubeau"), out.map { it.name })
    }

    @Test fun `a blocked artist is kept on the grid, marked, and is not a pick`() {
        val s = TastePickerUiState(artists = a("A", "B"), blocked = setOf("b"), selected = setOf("a"), loading = false)
        assertTrue("b" in s.blocked)
        assertEquals(listOf("A", "B"), s.shown.map { it.name })
    }

    @Test fun `a credit line is not an artist`() {
        assertEquals(listOf("Reol", "Simon & Garfunkel"), TastePickerGrid.starters(listOf("Reol", "Reol feat. 東京ゲゲゲイ", "Simon & Garfunkel", "A ft. B")).map { it.name })
    }
}
