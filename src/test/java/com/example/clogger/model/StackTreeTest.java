package com.example.clogger.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StackTreeTest {
    @Test void countsAndSelf() {
        StackTree t = new StackTree(50);
        t.add(List.of("main", "tick", "a"));
        t.add(List.of("main", "tick", "a"));
        t.add(List.of("main", "tick", "b"));
        t.add(List.of("main", "tick"));
        assertEquals(4, t.samples());
        StackTree.Node tick = t.root.children.get("main").children.get("tick");
        assertEquals(4, tick.total);
        assertEquals(1, tick.self);
        assertEquals(2, tick.children.get("a").total);
    }

    @Test void depthIsCapped() {
        StackTree t = new StackTree(2);
        t.add(List.of("a", "b", "c", "d"));
        StackTree.Node b = t.root.children.get("a").children.get("b");
        assertTrue(b.children.isEmpty());
        assertEquals(1, b.self);
    }

    @Test void renderFoldsSharedPrefixAndSmallBranches() {
        StackTree t = new StackTree(50);
        for (int i = 0; i < 97; i++) t.add(List.of("run", "loop", "tick", "mod.work"));
        for (int i = 0; i < 2; i++) t.add(List.of("run", "loop", "tick", "tiny"));
        t.add(List.of("run", "loop", "tick", "tiny2"));
        String s = t.render(5, 100, x -> x);
        assertTrue(s.contains("mod.work"), s);
        assertFalse(s.contains("tiny2"), s);
        assertTrue(s.contains("many small ones") || !s.contains("tiny"), s);
        assertTrue(s.contains("frames all samples share"), s);
    }
}
